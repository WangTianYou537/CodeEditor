package com.editor.lang;

import com.editor.plugin.LanguagePlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry of languages available to the editor.
 *
 * Lookup is by language name (case-insensitive) or by file extension.
 * Built-in grammars (java, go) are registered at construction; additional
 * grammars and plugins can be added at runtime.
 *
 * Thread-safe for concurrent reads; registration is expected to happen
 * during app startup or on the UI thread.
 */
public final class LanguageRegistry {

    private static final LanguageRegistry INSTANCE = new LanguageRegistry();

    public static LanguageRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, Entry> byName = new LinkedHashMap<>();
    private final Map<String, String> extensionToName = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onLanguageRegistered(String name);
    }

    private static final class Entry {
        final LanguageSpec spec;
        final LanguagePlugin plugin; // null for pure grammar entries

        Entry(LanguageSpec spec, LanguagePlugin plugin) {
            this.spec = spec;
            this.plugin = plugin;
        }
    }

    private LanguageRegistry() {
        // Built-ins are loaded lazily via ensureBuiltins() so that grammar
        // files sitting next to the classpath / assets can be found once the
        // app has a context. Callers that only need the API can also register
        // specs programmatically without files.
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Registers a language from a pure grammar spec (no custom lexer). */
    public synchronized void register(LanguageSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec required");
        registerInternal(spec, null);
    }

    /** Registers a language contributed by a plugin. */
    public synchronized void register(LanguagePlugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin required");
        LanguageSpec spec = plugin.getSpec();
        if (spec == null) {
            throw new IllegalArgumentException(
                    "plugin " + plugin.getName() + " returned null spec");
        }
        registerInternal(spec, plugin);
    }

    private void registerInternal(LanguageSpec spec, LanguagePlugin plugin) {
        String key = normalize(spec.name);
        byName.put(key, new Entry(spec, plugin));
        for (String ext : spec.extensions) {
            extensionToName.put(normalize(ext), key);
        }
        // Plugin may claim extra extensions beyond the spec.
        if (plugin != null && plugin.getExtensions() != null) {
            for (String ext : plugin.getExtensions()) {
                extensionToName.put(normalize(ext), key);
            }
        }
        for (Listener l : listeners) {
            l.onLanguageRegistered(spec.name);
        }
    }

    public synchronized void unregister(String name) {
        String key = normalize(name);
        Entry e = byName.remove(key);
        if (e == null) return;
        extensionToName.entrySet().removeIf(en -> en.getValue().equals(key));
    }

    public synchronized LanguageSpec getSpec(String name) {
        Entry e = byName.get(normalize(name));
        return e == null ? null : e.spec;
    }

    public synchronized LanguageSpec getSpecByExtension(String extension) {
        if (extension == null) return null;
        String bare = extension.startsWith(".") ? extension.substring(1) : extension;
        String name = extensionToName.get(normalize(bare));
        return name == null ? null : getSpec(name);
    }

    public synchronized LanguagePlugin getPlugin(String name) {
        Entry e = byName.get(normalize(name));
        return e == null ? null : e.plugin;
    }

    /**
     * Builds a lexer for the named language. Prefers a plugin's custom
     * lexer when present; otherwise wraps the spec in a {@link GrammarLexer}.
     */
    public synchronized Lexer createLexer(String name) {
        Entry e = byName.get(normalize(name));
        if (e == null) return null;
        if (e.plugin != null) {
            Lexer custom = e.plugin.createLexer();
            if (custom != null) return custom;
        }
        return new GrammarLexer(e.spec);
    }

    public synchronized Lexer createLexerByExtension(String extension) {
        LanguageSpec spec = getSpecByExtension(extension);
        return spec == null ? null : createLexer(spec.name);
    }

    public synchronized Collection<String> getLanguageNames() {
        List<String> names = new ArrayList<>();
        for (Entry e : byName.values()) {
            names.add(e.spec.name);
        }
        return Collections.unmodifiableList(names);
    }

    public synchronized boolean isRegistered(String name) {
        return byName.containsKey(normalize(name));
    }

    /**
     * Loads every {@code *.json} grammar in {@code dir} and registers it.
     * Files that fail to parse are skipped (logged via the returned list).
     *
     * @return names of successfully loaded languages
     */
    public List<String> loadGrammarsFrom(File dir) {
        List<String> loaded = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return loaded;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return loaded;
        for (File f : files) {
            try {
                LanguageSpec spec = GrammarLoader.load(f);
                register(spec);
                loaded.add(spec.name);
            } catch (IOException | RuntimeException ex) {
                // Skip broken grammars; caller can inspect the dir itself.
                System.err.println("Grammar load failed for " + f + ": " + ex.getMessage());
            }
        }
        return loaded;
    }

    /** Loads a single grammar from a classpath / asset stream. */
    public LanguageSpec loadGrammar(InputStream in) throws IOException {
        LanguageSpec spec = GrammarLoader.load(in);
        register(spec);
        return spec;
    }

    public LanguageSpec loadGrammar(File file) throws IOException {
        LanguageSpec spec = GrammarLoader.load(file);
        register(spec);
        return spec;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

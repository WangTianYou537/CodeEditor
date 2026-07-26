package cn.wty5.editor.plugin;

import cn.wty5.editor.lang.LanguageRegistry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Installs language plugins from jar files or class names.
 *
 * Discovery order for a jar:
 * <ol>
 *   <li>{@code META-INF/services/cn.wty5.editor.plugin.LanguagePlugin}
 *       (standard {@link ServiceLoader} entry)</li>
 *   <li>{@code META-INF/editor-plugin.txt} — one fully-qualified class name
 *       per line (fallback for simple hand-rolled jars)</li>
 * </ol>
 *
 * Installed plugins are registered into the shared {@link LanguageRegistry}.
 * ClassLoaders are retained so plugin classes stay live for the process
 * lifetime; call {@link #unloadAll()} on shutdown if desired.
 */
public final class PluginManager {

    private final LanguageRegistry registry;
    private final List<URLClassLoader> loaders = new ArrayList<>();
    private final List<LanguagePlugin> installed = new ArrayList<>();

    public PluginManager() {
        this(LanguageRegistry.getInstance());
    }

    public PluginManager(LanguageRegistry registry) {
        this.registry = registry;
    }

    /**
     * Installs every plugin discovered in {@code jarFile}.
     *
     * @return the plugins that were successfully loaded and registered
     */
    public synchronized List<LanguagePlugin> installJar(File jarFile)
            throws IOException, ReflectiveOperationException {
        if (jarFile == null || !jarFile.isFile()) {
            throw new IOException("not a jar file: " + jarFile);
        }
        URL url = jarFile.toURI().toURL();
        URLClassLoader loader = new URLClassLoader(
                new URL[]{url}, getClass().getClassLoader());
        loaders.add(loader);

        List<String> classNames = discoverClassNames(jarFile, loader);
        List<LanguagePlugin> loaded = new ArrayList<>();
        for (String cn : classNames) {
            LanguagePlugin plugin = instantiate(loader, cn);
            registry.register(plugin);
            installed.add(plugin);
            loaded.add(plugin);
        }
        return loaded;
    }

    /**
     * Instantiates a plugin class by name using the given (or context)
     * class loader and registers it.
     */
    public synchronized LanguagePlugin installClass(String className)
            throws ReflectiveOperationException {
        return installClass(className, Thread.currentThread().getContextClassLoader());
    }

    public synchronized LanguagePlugin installClass(String className, ClassLoader loader)
            throws ReflectiveOperationException {
        LanguagePlugin plugin = instantiate(loader, className);
        registry.register(plugin);
        installed.add(plugin);
        return plugin;
    }

    /** Registers an already-constructed plugin instance. */
    public synchronized void install(LanguagePlugin plugin) {
        registry.register(plugin);
        installed.add(plugin);
    }

    public synchronized List<LanguagePlugin> getInstalled() {
        return new ArrayList<>(installed);
    }

    /** Closes all jar class loaders. Does not unregister languages. */
    public synchronized void unloadAll() {
        for (URLClassLoader cl : loaders) {
            try {
                cl.close();
            } catch (IOException ignored) {
            }
        }
        loaders.clear();
    }

    // ------------------------------------------------------------------

    private static LanguagePlugin instantiate(ClassLoader loader, String className)
            throws ReflectiveOperationException {
        Class<?> cls = Class.forName(className, true, loader);
        if (!LanguagePlugin.class.isAssignableFrom(cls)) {
            throw new ClassCastException(className + " does not implement LanguagePlugin");
        }
        return (LanguagePlugin) cls.getDeclaredConstructor().newInstance();
    }

    private static List<String> discoverClassNames(File jarFile, ClassLoader loader)
            throws IOException {
        List<String> names = new ArrayList<>();

        // 1. ServiceLoader manifest
        try (InputStream in = loader.getResourceAsStream(
                "META-INF/services/cn.wty5.editor.plugin.LanguagePlugin")) {
            if (in != null) {
                names.addAll(readLines(in));
            }
        }

        // 2. Simple fallback file
        if (names.isEmpty()) {
            try (InputStream in = loader.getResourceAsStream("META-INF/editor-plugin.txt")) {
                if (in != null) {
                    names.addAll(readLines(in));
                }
            }
        }

        // 3. Last resort: scan jar for *Plugin.class implementing the interface
        //    is expensive and reflection-heavy; skip unless nothing else found.
        if (names.isEmpty()) {
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String n = e.getName();
                    if (n.endsWith("Plugin.class") && !n.contains("$")) {
                        names.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
            }
        }
        return names;
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        byte[] buf = in.readAllBytes();
        String text = new String(buf, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            lines.add(line);
        }
        return lines;
    }
}

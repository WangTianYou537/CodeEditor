package cn.wty5.editor.complete;

import cn.wty5.editor.core.Document;
import cn.wty5.editor.lang.LanguageSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/**
 * Completion sources driven by a {@link LanguageSpec}: keywords, types,
 * snippets from the grammar, plus identifiers harvested from the document.
 *
 * The identifier index is a word → count map rebuilt lazily (at most once
 * per document version) from a text snapshot taken on the UI thread.
 *
 * Ranking: prefix matches first (shorter word wins), then camel-hump /
 * substring matches; snippets rank highest when their trigger matches.
 */
public final class CompletionProvider {

    private final Document document;
    private volatile LanguageSpec language;
    private final Map<String, Integer> wordIndex = new HashMap<>();
    /** Written on the worker thread, read on the UI thread (staleness check). */
    private volatile long indexedVersion = -1;

    public CompletionProvider(Document document, LanguageSpec language) {
        this.document = document;
        this.language = language;
    }

    /** Swap language (e.g. after setLanguage); forces a re-index next time. */
    public void setLanguage(LanguageSpec language) {
        this.language = language;
        indexedVersion = -1;
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    /** UI thread: does the engine need to snapshot the text for us? */
    public boolean isIndexCurrent(long version) {
        return indexedVersion == version;
    }

    /**
     * Computes suggestions for the given prefix. Called on the completion
     * worker thread.
     *
     * @param textSnapshot full document text captured on the UI thread, or
     *                     null when {@link #isIndexCurrent} reported the
     *                     cached word index is still valid.
     * @param version      document version the snapshot was taken at
     */
    public List<CompletionItem> complete(String prefix, int caretOffset,
                                         String textSnapshot, long version) {
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }
        rebuildIndexIfStale(textSnapshot, version, caretOffset, prefix.length());

        LanguageSpec lang = this.language;
        List<Scored> scored = new ArrayList<>();

        String lowerPrefix = prefix.toLowerCase();

        if (lang != null) {
            for (LanguageSpec.Snippet snip : lang.snippets) {
                if (snip.trigger.startsWith(lowerPrefix)
                        && !snip.trigger.equals(prefix)) {
                    scored.add(new Scored(1000 - snip.trigger.length(),
                            new CompletionItem(CompletionItem.Kind.SNIPPET,
                                    snip.trigger, snip.insert, snip.detail)));
                }
            }
            for (String kw : lang.keywords) {
                if (kw.startsWith(prefix) && !kw.equals(prefix)) {
                    scored.add(new Scored(400 - kw.length(),
                            new CompletionItem(CompletionItem.Kind.KEYWORD,
                                    kw, kw, "keyword")));
                }
            }
            for (String ty : lang.types) {
                if (ty.startsWith(prefix) && !ty.equals(prefix)) {
                    scored.add(new Scored(420 - ty.length(),
                            new CompletionItem(CompletionItem.Kind.TYPE,
                                    ty, ty, "type")));
                }
            }
        }

        for (Map.Entry<String, Integer> e : wordIndex.entrySet()) {
            String word = e.getKey();
            int score = match(word, prefix, lowerPrefix);
            if (score > 0 && !word.equals(prefix)) {
                scored.add(new Scored(score + Math.min(e.getValue(), 50) - word.length(),
                        new CompletionItem(CompletionItem.Kind.IDENTIFIER,
                                word, word, "in file")));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<CompletionItem> out = new ArrayList<>(Math.min(scored.size(), 20));
        HashSet<String> seen = new HashSet<>();
        for (Scored s : scored) {
            if (seen.add(s.item.label)) {
                out.add(s.item);
                if (out.size() == 20) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Match quality: 500 = case-sensitive prefix, 450 = case-insensitive
     * prefix, 300 = camel-hump, 200 = substring, 0 = no match.
     */
    private static int match(String word, String prefix, String lowerPrefix) {
        if (word.startsWith(prefix)) {
            return 500;
        }
        String lowerWord = word.toLowerCase();
        if (lowerWord.startsWith(lowerPrefix)) {
            return 450;
        }
        if (camelMatch(word, prefix)) {
            return 300;
        }
        if (lowerWord.contains(lowerPrefix)) {
            return 200;
        }
        return 0;
    }

    /** "gTB" matches "getThreadBinding": each prefix char eats a hump. */
    private static boolean camelMatch(String word, String prefix) {
        int wi = 0;
        for (int pi = 0; pi < prefix.length(); pi++) {
            char pc = prefix.charAt(pi);
            boolean found = false;
            while (wi < word.length()) {
                char wc = word.charAt(wi);
                boolean humpStart = wi == 0 || Character.isUpperCase(wc)
                        || (wi > 0 && word.charAt(wi - 1) == '_');
                wi++;
                if (humpStart && Character.toLowerCase(wc) == Character.toLowerCase(pc)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Full scan of the snapshot collecting identifier frequencies. */
    private void rebuildIndexIfStale(String text, long version,
                                     int caretOffset, int prefixLen) {
        if (indexedVersion == version || text == null) {
            return;
        }
        wordIndex.clear();
        int typingStart = caretOffset - prefixLen;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                // Skip the half-typed word under the caret.
                if (s == typingStart) {
                    continue;
                }
                if (i - s >= 2) { // single letters aren't useful suggestions
                    String word = text.substring(s, i);
                    Integer prev = wordIndex.get(word);
                    wordIndex.put(word, prev == null ? 1 : prev + 1);
                }
            } else {
                i++;
            }
        }
        indexedVersion = version;
    }

    private static final class Scored {
        final int score;
        final CompletionItem item;

        Scored(int score, CompletionItem item) {
            this.score = score;
            this.item = item;
        }
    }
}

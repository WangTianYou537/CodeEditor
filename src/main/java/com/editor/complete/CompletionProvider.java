package com.editor.complete;

import com.editor.core.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Completion sources: Java keywords, common snippets, and identifiers
 * harvested from the document itself.
 *
 * The identifier index is a word → count map rebuilt lazily (at most once
 * per document version) by a full scan; scanning is a simple linear pass
 * over the piece table's CharSequence view, cheap enough for editor-sized
 * files and always run on the completion thread, never the UI thread.
 *
 * Ranking: prefix matches first (shorter word wins), then camel-hump /
 * substring matches; keywords slightly below identifiers that match exactly
 * by prefix, snippets on top when the prefix matches their trigger.
 */
public final class CompletionProvider {

    private static final String[] KEYWORDS = {
            "abstract", "assert", "break", "case", "catch", "class", "continue",
            "default", "do", "else", "enum", "extends", "final", "finally",
            "for", "if", "implements", "import", "instanceof", "interface",
            "native", "new", "package", "private", "protected", "public",
            "return", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "record", "sealed", "yield", "var",
            "boolean", "byte", "char", "double", "float", "int", "long",
            "short", "true", "false", "null", "String", "Object", "Integer",
            "List", "Map", "Set", "ArrayList", "HashMap", "HashSet",
            "StringBuilder", "System", "Math", "Exception", "Thread",
    };

    private static final String[][] SNIPPETS = {
            {"sout", "System.out.println($0);", "System.out.println"},
            {"fori", "for (int i = 0; i < $0; i++) {\n}", "for loop"},
            {"main", "public static void main(String[] args) {\n    $0\n}", "main method"},
            {"psvm", "public static void main(String[] args) {\n    $0\n}", "main method"},
            {"trycatch", "try {\n    $0\n} catch (Exception e) {\n}", "try/catch"},
            {"ifn", "if ($0 == null) {\n}", "if null"},
    };

    private final Document document;
    private final Map<String, Integer> wordIndex = new HashMap<>();
    /** Written on the worker thread, read on the UI thread (staleness check). */
    private volatile long indexedVersion = -1;

    public CompletionProvider(Document document) {
        this.document = document;
    }

    /** UI thread: does the engine need to snapshot the text for us? */
    public boolean isIndexCurrent(long version) {
        return indexedVersion == version;
    }

    /**
     * Computes suggestions for the given prefix. Called on the completion
     * worker thread. {@code caretOffset} lets the scanner skip the word
     * currently being typed so it doesn't suggest itself.
     *
     * @param textSnapshot full document text captured on the UI thread, or
     *                     null when {@link #isIndexCurrent} reported the
     *                     cached word index is still valid. The worker never
     *                     reads the live document — the piece table is not
     *                     thread-safe.
     * @param version      document version the snapshot was taken at
     */
    public List<CompletionItem> complete(String prefix, int caretOffset,
                                         String textSnapshot, long version) {
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }
        rebuildIndexIfStale(textSnapshot, version, caretOffset, prefix.length());

        List<Scored> scored = new ArrayList<>();
        String lowerPrefix = prefix.toLowerCase();

        for (String[] snip : SNIPPETS) {
            if (snip[0].startsWith(lowerPrefix) && !snip[0].equals(prefix)) {
                scored.add(new Scored(1000 - snip[0].length(),
                        new CompletionItem(CompletionItem.Kind.SNIPPET,
                                snip[0], snip[1], snip[2])));
            }
        }
        for (Map.Entry<String, Integer> e : wordIndex.entrySet()) {
            String word = e.getKey();
            int score = match(word, prefix, lowerPrefix);
            if (score > 0 && !word.equals(prefix)) {
                // Frequent words rank higher; length breaks ties.
                scored.add(new Scored(score + Math.min(e.getValue(), 50) - word.length(),
                        new CompletionItem(CompletionItem.Kind.IDENTIFIER,
                                word, word, "in file")));
            }
        }
        for (String kw : KEYWORDS) {
            if (kw.startsWith(prefix) && !kw.equals(prefix)) {
                boolean type = Character.isUpperCase(kw.charAt(0));
                scored.add(new Scored(400 - kw.length(),
                        new CompletionItem(
                                type ? CompletionItem.Kind.TYPE : CompletionItem.Kind.KEYWORD,
                                kw, kw, type ? "type" : "keyword")));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<CompletionItem> out = new ArrayList<>(Math.min(scored.size(), 20));
        TreeSet<String> seen = new TreeSet<>();
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

    // Exposed for tests.
    static List<String> keywordList() {
        return Arrays.asList(KEYWORDS);
    }
}

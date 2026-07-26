package com.editor.lang;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads a {@link LanguageSpec} from a grammar JSON file.
 *
 * <p>Schema (all fields optional except {@code name}):
 * <pre>
 * {
 *   "name": "java",
 *   "extensions": ["java"],
 *   "keywords": ["if", "else", ...],
 *   "types": ["int", "String", ...],
 *   "snippets": [
 *     {"trigger": "sout", "insert": "System.out.println($0);", "detail": "..."}
 *   ],
 *   "lineComment": "//",
 *   "blockComment": ["slash-star", "star-slash"],
 *   "textBlock": ["triple-quote", "triple-quote"],
 *   "rawStringDelimiter": "`",
 *   "doubleQuotedStrings": true,
 *   "singleQuotedChars": true,
 *   "singleQuotedStrings": false,
 *   "operatorChars": "+- star / % = &lt;&gt; ! &amp; | ^ ~ ? :",
 *   "punctuationChars": "(){}[];,.",
 *   "hexNumbers": true,
 *   "binNumbers": true,
 *   "underscoreInNumbers": true,
 *   "numberSuffixes": "lLfFdD"
 * }
 * </pre>
 * See {@code grammars/java.json} and {@code grammars/go.json} for real examples.
 */
public final class GrammarLoader {

    private GrammarLoader() {}

    public static LanguageSpec load(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return load(in);
        }
    }

    public static LanguageSpec load(InputStream in) throws IOException {
        String text = readAll(in);
        return fromJson(text);
    }

    public static LanguageSpec fromJson(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        LanguageSpec.Builder b = new LanguageSpec.Builder();

        String name = str(root.get("name"));
        if (name != null) b.name(name);

        List<String> exts = stringList(root.get("extensions"));
        if (exts != null) b.extensions(exts);

        List<String> kws = stringList(root.get("keywords"));
        if (kws != null) b.keywords(kws);

        List<String> types = stringList(root.get("types"));
        if (types != null) b.types(types);

        Object snips = root.get("snippets");
        if (snips instanceof List) {
            for (Object item : (List<?>) snips) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) item;
                String trigger = str(m.get("trigger"));
                String insert = str(m.get("insert"));
                if (trigger == null || insert == null) continue;
                b.snippet(trigger, insert, str(m.get("detail")));
            }
        }

        String lineComment = str(root.get("lineComment"));
        if (root.containsKey("lineComment")) b.lineComment(lineComment);

        List<String> block = stringList(root.get("blockComment"));
        if (root.containsKey("blockComment")) {
            if (block != null && block.size() >= 2) {
                b.blockComment(block.get(0), block.get(1));
            } else {
                b.noBlockComment();
            }
        }

        List<String> textBlock = stringList(root.get("textBlock"));
        if (textBlock != null && textBlock.size() >= 2) {
            b.textBlock(textBlock.get(0), textBlock.get(1));
        }

        if (root.containsKey("rawStringDelimiter")) {
            b.rawStringDelimiter(str(root.get("rawStringDelimiter")));
        }
        if (root.containsKey("doubleQuotedStrings")) {
            b.doubleQuotedStrings(bool(root.get("doubleQuotedStrings"), true));
        }
        if (root.containsKey("singleQuotedChars")) {
            b.singleQuotedChars(bool(root.get("singleQuotedChars"), false));
        }
        if (root.containsKey("singleQuotedStrings")) {
            b.singleQuotedStrings(bool(root.get("singleQuotedStrings"), false));
        }
        if (root.containsKey("operatorChars")) {
            String ops = str(root.get("operatorChars"));
            if (ops != null) b.operatorChars(ops);
        }
        if (root.containsKey("punctuationChars")) {
            String p = str(root.get("punctuationChars"));
            if (p != null) b.punctuationChars(p);
        }
        if (root.containsKey("hexNumbers")) {
            b.hexNumbers(bool(root.get("hexNumbers"), true));
        }
        if (root.containsKey("binNumbers")) {
            b.binNumbers(bool(root.get("binNumbers"), true));
        }
        if (root.containsKey("underscoreInNumbers")) {
            b.underscoreInNumbers(bool(root.get("underscoreInNumbers"), true));
        }
        if (root.containsKey("numberSuffixes")) {
            String suf = str(root.get("numberSuffixes"));
            if (suf != null) b.numberSuffixes(suf);
        }

        return b.build();
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return def;
        return Boolean.parseBoolean(o.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (!(o instanceof List)) return null;
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) o) {
            if (item != null) out.add(item.toString());
        }
        return out;
    }
}

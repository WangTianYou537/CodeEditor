package cn.wty5.editor.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Bootstraps built-in languages (Java, Go) into the shared
 * {@link LanguageRegistry}.
 *
 * Resolution order for each language:
 * <ol>
 *   <li>Classpath resource {@code /grammars/&lt;name&gt;.json}</li>
 *   <li>Filesystem path relative to CWD: {@code grammars/&lt;name&gt;.json}</li>
 *   <li>Hard-coded fallback built with {@link LanguageSpec.Builder}
 *       (so the editor always works even without the JSON files packaged)</li>
 * </ol>
 */
public final class Languages {

    private Languages() {}

    private static volatile boolean loaded;

    /** Idempotent: loads java + go once into the global registry. */
    public static void ensureBuiltins() {
        if (loaded) return;
        synchronized (Languages.class) {
            if (loaded) return;
            LanguageRegistry reg = LanguageRegistry.getInstance();
            registerOrFallback(reg, "java", Languages::javaFallback);
            registerOrFallback(reg, "go", Languages::goFallback);
            loaded = true;
        }
    }

    /** Loads every grammar in a directory (user/plugin grammars). */
    public static List<String> loadFromDirectory(File dir) {
        ensureBuiltins();
        return LanguageRegistry.getInstance().loadGrammarsFrom(dir);
    }

    public static LanguageSpec java() {
        ensureBuiltins();
        return LanguageRegistry.getInstance().getSpec("java");
    }

    public static LanguageSpec go() {
        ensureBuiltins();
        return LanguageRegistry.getInstance().getSpec("go");
    }

    public static Lexer lexerFor(String languageName) {
        ensureBuiltins();
        Lexer lex = LanguageRegistry.getInstance().createLexer(languageName);
        if (lex == null) {
            // Plain text fallback: empty spec still produces a working lexer.
            return new GrammarLexer(new LanguageSpec.Builder().name("plain").build());
        }
        return lex;
    }

    public static Lexer lexerForExtension(String extension) {
        ensureBuiltins();
        Lexer lex = LanguageRegistry.getInstance().createLexerByExtension(extension);
        if (lex == null) {
            return new GrammarLexer(new LanguageSpec.Builder().name("plain").build());
        }
        return lex;
    }

    // ------------------------------------------------------------------

    private interface Fallback {
        LanguageSpec build();
    }

    private static void registerOrFallback(LanguageRegistry reg, String name,
                                           Fallback fallback) {
        if (reg.isRegistered(name)) return;

        // 1. Classpath
        InputStream in = Languages.class.getResourceAsStream("/grammars/" + name + ".json");
        if (in != null) {
            try (InputStream stream = in) {
                reg.loadGrammar(stream);
                return;
            } catch (IOException | RuntimeException ignored) {
            }
        }

        // 2. Filesystem next to CWD / project root
        File file = new File("grammars", name + ".json");
        if (file.isFile()) {
            try {
                reg.loadGrammar(file);
                return;
            } catch (IOException | RuntimeException ignored) {
            }
        }

        // 3. Hard-coded
        reg.register(fallback.build());
    }

    static LanguageSpec javaFallback() {
        return new LanguageSpec.Builder()
                .name("java")
                .extension("java")
                .keywords(Arrays.asList(
                        "abstract", "assert", "break", "case", "catch", "class", "const",
                        "continue", "default", "do", "else", "enum", "extends", "final",
                        "finally", "for", "goto", "if", "implements", "import",
                        "instanceof", "interface", "native", "new", "package", "private",
                        "protected", "public", "return", "static", "strictfp", "super",
                        "switch", "synchronized", "this", "throw", "throws", "transient",
                        "try", "volatile", "while", "record", "sealed", "permits",
                        "yield", "var", "true", "false", "null"))
                .types(Arrays.asList(
                        "boolean", "byte", "char", "double", "float", "int", "long",
                        "short", "void",
                        "String", "Object", "Integer", "Long", "Double", "Float",
                        "Boolean", "Byte", "Short", "Character", "Void", "Number",
                        "CharSequence", "StringBuilder", "List", "Map", "Set",
                        "ArrayList", "HashMap", "HashSet", "Exception", "RuntimeException",
                        "Thread", "Runnable", "Math", "System"))
                .snippet("sout", "System.out.println($0);", "System.out.println")
                .snippet("fori", "for (int i = 0; i < $0; i++) {\n}", "for loop")
                .snippet("main", "public static void main(String[] args) {\n    $0\n}", "main method")
                .snippet("psvm", "public static void main(String[] args) {\n    $0\n}", "main method")
                .snippet("trycatch", "try {\n    $0\n} catch (Exception e) {\n}", "try/catch")
                .snippet("ifn", "if ($0 == null) {\n}", "if null")
                .lineComment("//")
                .blockComment("/*", "*/")
                .textBlock("\"\"\"", "\"\"\"")
                .doubleQuotedStrings(true)
                .singleQuotedChars(true)
                .numberSuffixes("lLfFdD")
                .build();
    }

    static LanguageSpec goFallback() {
        return new LanguageSpec.Builder()
                .name("go")
                .extension("go")
                .keywords(Arrays.asList(
                        "break", "case", "chan", "const", "continue", "default", "defer",
                        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
                        "interface", "map", "package", "range", "return", "select", "struct",
                        "switch", "type", "var", "true", "false", "iota", "nil"))
                .types(Arrays.asList(
                        "bool", "byte", "complex64", "complex128", "error", "float32",
                        "float64", "int", "int8", "int16", "int32", "int64", "rune",
                        "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
                        "any", "comparable"))
                .snippet("main", "func main() {\n\t$0\n}", "main function")
                .snippet("func", "func $0() {\n}", "function")
                .snippet("fori", "for i := 0; i < $0; i++ {\n}", "C-style for")
                .snippet("forr", "for _, v := range $0 {\n}", "range loop")
                .snippet("iferr", "if err != nil {\n\treturn $0\n}", "if err != nil")
                .snippet("pf", "fmt.Printf(\"$0\\n\")", "fmt.Printf")
                .snippet("pl", "fmt.Println($0)", "fmt.Println")
                .snippet("struct", "type $0 struct {\n}", "struct type")
                .lineComment("//")
                .blockComment("/*", "*/")
                .rawStringDelimiter("`")
                .doubleQuotedStrings(true)
                .singleQuotedChars(true)
                .numberSuffixes("")
                .build();
    }
}

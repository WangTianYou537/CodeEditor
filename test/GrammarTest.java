import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.complete.CompletionProvider;
import cn.wty5.editor.core.Document;
import cn.wty5.editor.lang.GrammarLexer;
import cn.wty5.editor.lang.GrammarLoader;
import cn.wty5.editor.lang.LanguageRegistry;
import cn.wty5.editor.lang.LanguageSpec;
import cn.wty5.editor.lang.Languages;
import cn.wty5.editor.lang.Lexer;
import cn.wty5.editor.lang.MiniJson;
import cn.wty5.editor.lang.TokenType;
import cn.wty5.editor.plugin.LanguagePlugin;
import cn.wty5.editor.plugin.PluginManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Tests for grammar loading, Go/Java lexing via specs, registry, plugins. */
public class GrammarTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void checkEq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: " + what
                    + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        testMiniJson();
        testLoadJavaGrammarFile();
        testLoadGoGrammarFile();
        testJavaLexViaGrammar();
        testGoLexViaGrammar();
        testGoRawString();
        testRegistry();
        testCompletionFromGrammar();
        testGoCompletion();
        testPluginInstall();
        testLspConfigFromGrammar();
        testLspConfigTransports();
        testLspWorkspacePlaceholders();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------

    static void testMiniJson() {
        Map<String, Object> m = MiniJson.parseObject(
                "{\"a\":1,\"b\":[\"x\",true,null],\"c\":{\"d\":\"hi\\n\"}}");
        checkEq(1, m.get("a"), "json int");
        check(m.get("b") instanceof List, "json array");
        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) m.get("b");
        checkEq("x", arr.get(0), "json array[0]");
        checkEq(Boolean.TRUE, arr.get(1), "json true");
        checkEq(null, arr.get(2), "json null");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) m.get("c");
        checkEq("hi\n", nested.get("d"), "json escaped newline");
    }

    static void testLoadJavaGrammarFile() throws Exception {
        File f = new File("grammars/java.json");
        check(f.isFile(), "java.json exists");
        LanguageSpec spec = GrammarLoader.load(f);
        checkEq("java", spec.name, "java name");
        check(spec.extensions.contains("java"), "java extension");
        check(spec.keywords.contains("public"), "java has public");
        check(spec.types.contains("String"), "java has String");
        checkEq("//", spec.lineComment, "java line comment");
        checkEq("/*", spec.blockCommentOpen, "java block open");
        checkEq("\"\"\"", spec.textBlockOpen, "java text block");
        check(spec.singleQuotedChars, "java char literals");
        check(!spec.snippets.isEmpty(), "java has snippets");
    }

    static void testLoadGoGrammarFile() throws Exception {
        File f = new File("grammars/go.json");
        check(f.isFile(), "go.json exists");
        LanguageSpec spec = GrammarLoader.load(f);
        checkEq("go", spec.name, "go name");
        check(spec.extensions.contains("go"), "go extension");
        check(spec.keywords.contains("func"), "go has func");
        check(spec.keywords.contains("defer"), "go has defer");
        check(spec.types.contains("string"), "go has string type");
        check(spec.types.contains("error"), "go has error type");
        checkEq("`", spec.rawStringDelimiter, "go raw string delim");
        checkEq("//", spec.lineComment, "go line comment");
        boolean hasIferr = false;
        for (LanguageSpec.Snippet s : spec.snippets) {
            if ("iferr".equals(s.trigger)) hasIferr = true;
        }
        check(hasIferr, "go has iferr snippet");
    }

    static final class Tok {
        final TokenType type;
        final int start, end;
        Tok(TokenType t, int s, int e) { type = t; start = s; end = e; }
    }

    static List<Tok> lex(Lexer lexer, String line, int inState, int[] outState) {
        List<Tok> toks = new ArrayList<>();
        outState[0] = lexer.tokenizeLine(line, inState,
                (t, s, e) -> toks.add(new Tok(t, s, e)));
        return toks;
    }

    static Tok tokenAt(List<Tok> toks, int col) {
        for (Tok t : toks) {
            if (col >= t.start && col < t.end) return t;
        }
        return null;
    }

    static void testJavaLexViaGrammar() throws Exception {
        LanguageSpec spec = GrammarLoader.load(new File("grammars/java.json"));
        Lexer lexer = new GrammarLexer(spec);
        int[] out = new int[1];
        String line = "public static int x = 0x1F; // hi";
        List<Tok> toks = lex(lexer, line, Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.KEYWORD, tokenAt(toks, 0).type, "public via grammar");
        checkEq(TokenType.TYPE, tokenAt(toks, line.indexOf("int")).type, "int via grammar");
        checkEq(TokenType.NUMBER, tokenAt(toks, line.indexOf("0x1F")).type, "hex via grammar");
        checkEq(TokenType.COMMENT, tokenAt(toks, line.indexOf("//")).type, "comment via grammar");

        // text block state
        lex(lexer, "String s = \"\"\"", Lexer.STATE_DEFAULT, out);
        checkEq(GrammarLexer.STATE_IN_TEXT_BLOCK, out[0], "text block open via grammar");
    }

    static void testGoLexViaGrammar() throws Exception {
        LanguageSpec spec = GrammarLoader.load(new File("grammars/go.json"));
        Lexer lexer = new GrammarLexer(spec);
        int[] out = new int[1];

        String line = "func main() { defer fmt.Println(42) }";
        List<Tok> toks = lex(lexer, line, Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.KEYWORD, tokenAt(toks, 0).type, "func keyword");
        checkEq(TokenType.KEYWORD, tokenAt(toks, line.indexOf("defer")).type, "defer keyword");
        checkEq(TokenType.IDENTIFIER, tokenAt(toks, line.indexOf("fmt")).type, "fmt ident");
        checkEq(TokenType.NUMBER, tokenAt(toks, line.indexOf("42")).type, "number");

        // package / import / type keywords
        toks = lex(lexer, "package main", Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.KEYWORD, tokenAt(toks, 0).type, "package keyword");

        String l2 = "var x error = nil";
        toks = lex(lexer, l2, Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.KEYWORD, tokenAt(toks, 0).type, "var keyword");
        checkEq(TokenType.TYPE, tokenAt(toks, l2.indexOf("error")).type, "error is type");
        checkEq(TokenType.KEYWORD, tokenAt(toks, l2.indexOf("nil")).type, "nil keyword");

        // Go types
        toks = lex(lexer, "var b bool", Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.TYPE, tokenAt(toks, "var b ".length()).type, "bool type");
    }

    static void testGoRawString() throws Exception {
        LanguageSpec spec = GrammarLoader.load(new File("grammars/go.json"));
        Lexer lexer = new GrammarLexer(spec);
        int[] out = new int[1];

        // Single-line raw string
        String line = "s := `hello world`";
        List<Tok> toks = lex(lexer, line, Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.STRING, tokenAt(toks, line.indexOf('`')).type, "raw string");
        checkEq(Lexer.STATE_DEFAULT, out[0], "raw string closed");

        // Multi-line raw string
        lex(lexer, "s := `start", Lexer.STATE_DEFAULT, out);
        checkEq(GrammarLexer.STATE_IN_RAW_STRING, out[0], "raw string open");
        toks = lex(lexer, "middle", GrammarLexer.STATE_IN_RAW_STRING, out);
        checkEq(TokenType.STRING, toks.get(0).type, "raw string middle");
        checkEq(GrammarLexer.STATE_IN_RAW_STRING, out[0], "still in raw string");
        toks = lex(lexer, "end` + x", GrammarLexer.STATE_IN_RAW_STRING, out);
        checkEq(Lexer.STATE_DEFAULT, out[0], "raw string closed multi");
        checkEq(TokenType.STRING, toks.get(0).type, "closing raw is string");
    }

    static void testRegistry() throws Exception {
        // Use a fresh local registry path via the singleton after loading files.
        Languages.ensureBuiltins();
        LanguageRegistry reg = LanguageRegistry.getInstance();

        // Load from directory (re-registers, fine).
        List<String> loaded = reg.loadGrammarsFrom(new File("grammars"));
        check(loaded.contains("java") || reg.isRegistered("java"), "registry has java");
        check(loaded.contains("go") || reg.isRegistered("go"), "registry has go");

        check(reg.getSpec("java") != null, "getSpec java");
        check(reg.getSpec("go") != null, "getSpec go");
        check(reg.getSpecByExtension("go") != null, "by ext go");
        check(reg.getSpecByExtension(".java") != null, "by ext .java");
        checkEq("go", reg.getSpecByExtension("go").name, "ext maps to go");

        Lexer jl = reg.createLexer("java");
        Lexer gl = reg.createLexer("go");
        check(jl != null && gl != null, "createLexer both");
        check(jl instanceof GrammarLexer, "default lexer is GrammarLexer");
    }

    static void testCompletionFromGrammar() throws Exception {
        LanguageSpec java = GrammarLoader.load(new File("grammars/java.json"));
        Document doc = new Document(
                "public class Foo {\n"
                + "    private int counter;\n"
                + "    void increment() { counter++; }\n"
                + "}\n"
                + "cou");
        CompletionProvider provider = new CompletionProvider(doc, java);
        int caret = doc.length();
        String snap = doc.toString();
        long ver = doc.version();

        List<CompletionItem> items = provider.complete("cou", caret, snap, ver);
        boolean hasCounter = false;
        for (CompletionItem it : items) {
            if ("counter".equals(it.label)) hasCounter = true;
        }
        check(hasCounter, "grammar completion: counter from file");

        items = provider.complete("pub", caret, snap, ver);
        boolean hasPublic = false;
        for (CompletionItem it : items) {
            if ("public".equals(it.label)) hasPublic = true;
        }
        check(hasPublic, "grammar completion: public keyword");

        items = provider.complete("sou", caret, snap, ver);
        boolean hasSout = false;
        for (CompletionItem it : items) {
            if ("sout".equals(it.label)) hasSout = true;
        }
        check(hasSout, "grammar completion: sout snippet");
    }

    static void testGoCompletion() throws Exception {
        LanguageSpec go = GrammarLoader.load(new File("grammars/go.json"));
        Document doc = new Document(
                "package main\n"
                + "func computeTotal() int { return 0 }\n"
                + "func main() {\n"
                + "    com\n");
        // caret after "com"
        int caret = doc.toString().lastIndexOf("com") + 3;
        CompletionProvider provider = new CompletionProvider(doc, go);
        List<CompletionItem> items = provider.complete("com", caret, doc.toString(), doc.version());
        boolean hasCompute = false;
        for (CompletionItem it : items) {
            if ("computeTotal".equals(it.label)) hasCompute = true;
        }
        check(hasCompute, "go completion: computeTotal from file");

        items = provider.complete("def", caret, doc.toString(), doc.version());
        boolean hasDefer = false;
        for (CompletionItem it : items) {
            if ("defer".equals(it.label)) hasDefer = true;
        }
        check(hasDefer, "go completion: defer keyword");

        items = provider.complete("ife", caret, doc.toString(), doc.version());
        boolean hasIferr = false;
        for (CompletionItem it : items) {
            if ("iferr".equals(it.label)) hasIferr = true;
        }
        check(hasIferr, "go completion: iferr snippet");
    }

    /**
     * Builds a tiny plugin jar on the fly that registers a "python-lite"
     * language, installs it via PluginManager, and verifies registry lookup.
     */
    static void testPluginInstall() throws Exception {
        File dir = new File("build/plugin-src");
        dir.mkdirs();
        File src = new File(dir, "DemoPlugin.java");
        String code = ""
                + "package demo;\n"
                + "import cn.wty5.editor.plugin.LanguagePlugin;\n"
                + "import cn.wty5.editor.lang.LanguageSpec;\n"
                + "import java.util.Arrays;\n"
                + "public class DemoPlugin implements LanguagePlugin {\n"
                + "  public String getName() { return \"python-lite\"; }\n"
                + "  public String[] getExtensions() { return new String[]{\"py\"}; }\n"
                + "  public LanguageSpec getSpec() {\n"
                + "    return new LanguageSpec.Builder()\n"
                + "      .name(\"python-lite\")\n"
                + "      .extension(\"py\")\n"
                + "      .keywords(Arrays.asList(\"def\",\"class\",\"import\",\"from\",\"if\",\"else\",\"for\",\"while\",\"return\",\"None\",\"True\",\"False\"))\n"
                + "      .types(Arrays.asList(\"int\",\"str\",\"list\",\"dict\"))\n"
                + "      .lineComment(\"#\")\n"
                + "      .noBlockComment()\n"
                + "      .singleQuotedStrings(true)\n"
                + "      .snippet(\"main\", \"def main():\\n    $0\", \"main\")\n"
                + "      .build();\n"
                + "  }\n"
                + "}\n";
        try (FileOutputStream fos = new FileOutputStream(src)) {
            fos.write(code.getBytes(StandardCharsets.UTF_8));
        }

        File classes = new File("build/plugin-classes");
        classes.mkdirs();
        // Compile plugin against our classes.
        Process p = new ProcessBuilder(
                "javac", "-cp", "build/core-classes", "-d", classes.getPath(), src.getPath())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        checkEq(0, rc, "plugin javac exit (out=" + compileOut + ")");

        File jar = new File("build/demo-plugin.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            // service file
            jos.putNextEntry(new JarEntry("META-INF/services/cn.wty5.editor.plugin.LanguagePlugin"));
            jos.write("demo.DemoPlugin\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            // class file
            File cls = new File(classes, "demo/DemoPlugin.class");
            jos.putNextEntry(new JarEntry("demo/DemoPlugin.class"));
            jos.write(java.nio.file.Files.readAllBytes(cls.toPath()));
            jos.closeEntry();
        }

        PluginManager pm = new PluginManager(LanguageRegistry.getInstance());
        List<LanguagePlugin> installed = pm.installJar(jar);
        checkEq(1, installed.size(), "one plugin installed");
        checkEq("python-lite", installed.get(0).getName(), "plugin name");

        LanguageSpec py = LanguageRegistry.getInstance().getSpec("python-lite");
        check(py != null, "python-lite registered");
        check(py.keywords.contains("def"), "python has def");
        checkEq("#", py.lineComment, "python line comment is #");

        Lexer lex = LanguageRegistry.getInstance().createLexer("python-lite");
        int[] out = new int[1];
        List<Tok> toks = lex(lex, "def foo(): # hi", Lexer.STATE_DEFAULT, out);
        checkEq(TokenType.KEYWORD, tokenAt(toks, 0).type, "python def keyword");
        checkEq(TokenType.COMMENT, tokenAt(toks, "def foo(): ".length()).type,
                "python # comment");

        // Completion from plugin language
        Document doc = new Document("def compute():\n    pass\ncom");
        CompletionProvider provider = new CompletionProvider(doc, py);
        List<CompletionItem> items = provider.complete("com", doc.length(),
                doc.toString(), doc.version());
        boolean hasCompute = false;
        for (CompletionItem it : items) {
            if ("compute".equals(it.label)) hasCompute = true;
        }
        check(hasCompute, "plugin completion: compute from file");

        pm.unloadAll();
    }

    static void testLspConfigFromGrammar() throws Exception {
        LanguageSpec go = GrammarLoader.load(new File("grammars/go.json"));
        check(go.lsp != null, "go.json has lsp block");
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.STDIO, go.lsp.transport,
                "go lsp transport stdio");
        check(!go.lsp.command.isEmpty(), "go lsp command present");
        checkEq("gopls", go.lsp.command.get(0), "go lsp command[0]");
        check(!go.lsp.enabled, "go lsp disabled by default in sample");
        checkEq("go", go.lsp.languageId, "go lsp languageId");
        checkEq("${workspaceFolder}", go.lsp.cwd, "go lsp cwd placeholder");

        LanguageSpec java = GrammarLoader.load(new File("grammars/java.json"));
        check(java.lsp != null, "java.json has lsp block");
        checkEq("jdtls", java.lsp.command.get(0), "java lsp command");
        check(!java.lsp.enabled, "java lsp disabled by default");

        // resolveLspConfig returns the grammar block even when disabled
        Languages.ensureBuiltins();
        // Force re-register from file so the in-memory builtins pick up lsp.
        LanguageRegistry.getInstance().register(go);
        cn.wty5.editor.lsp.LspConfig resolved =
                LanguageRegistry.getInstance().resolveLspConfig("go");
        check(resolved != null, "resolveLspConfig finds go");
        check(!resolved.isConfigured(),
                "disabled lsp is not isConfigured()");
    }

    static void testLspConfigTransports() {
        // stdio via command string
        cn.wty5.editor.lsp.LspConfig stdio = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject("{\"command\":\"gopls serve\",\"cwd\":\"/tmp\"}"));
        check(stdio != null, "stdio fromJson");
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.STDIO, stdio.transport,
                "default transport stdio");
        checkEq(2, stdio.command.size(), "command split on whitespace");
        check(stdio.isConfigured(), "stdio configured");

        // tcp
        cn.wty5.editor.lsp.LspConfig tcp = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject(
                        "{\"transport\":\"tcp\",\"host\":\"10.0.0.1\",\"port\":2087}"));
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.TCP, tcp.transport, "tcp type");
        checkEq(2087, tcp.port, "tcp port");
        check(tcp.isConfigured(), "tcp configured");

        // tcp via url shorthand
        cn.wty5.editor.lsp.LspConfig tcpUrl = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject("{\"url\":\"tcp://127.0.0.1:9999\"}"));
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.TCP, tcpUrl.transport,
                "tcp from url scheme");
        checkEq(9999, tcpUrl.port, "tcp port from url");

        // websocket
        cn.wty5.editor.lsp.LspConfig ws = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject(
                        "{\"type\":\"websocket\",\"url\":\"ws://127.0.0.1:2087/lsp\"}"));
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.WEBSOCKET, ws.transport,
                "ws type");
        check(ws.isConfigured(), "ws configured");

        // http + sse
        cn.wty5.editor.lsp.LspConfig http = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject("{"
                        + "\"transport\":\"http\","
                        + "\"url\":\"http://127.0.0.1:3000/lsp\","
                        + "\"sseUrl\":\"http://127.0.0.1:3000/events\""
                        + "}"));
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.HTTP, http.transport, "http");
        checkEq("http://127.0.0.1:3000/events", http.sseUrl, "sse url");
        check(http.isConfigured(), "http configured");

        // http inferred from url
        cn.wty5.editor.lsp.LspConfig httpInf = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject("{\"url\":\"https://lsp.example/rpc\"}"));
        checkEq(cn.wty5.editor.lsp.LspConfig.Transport.HTTP, httpInf.transport,
                "https infers http transport");

        // explicit disable
        checkEq(null, cn.wty5.editor.lsp.LspConfig.fromJson(Boolean.FALSE),
                "false → null");
        cn.wty5.editor.lsp.LspConfig off = cn.wty5.editor.lsp.LspConfig.fromJson(
                MiniJson.parseObject("{\"enabled\":false,\"command\":[\"x\"]}"));
        check(off != null && !off.enabled, "enabled:false kept");
        check(!off.isConfigured(), "disabled not configured");
    }

    static void testLspWorkspacePlaceholders() {
        cn.wty5.editor.lsp.LspConfig.LspWorkspace ws =
                new cn.wty5.editor.lsp.LspConfig.LspWorkspace(
                        "/home/u/proj", "/home/u/proj/main.go", "go");
        cn.wty5.editor.lsp.LspConfig raw = cn.wty5.editor.lsp.LspConfig.builder()
                .command("gopls", "serve")
                .cwd("${workspaceFolder}")
                .rootUri("${workspaceFolderUri}")
                .url("ws://localhost/lsp?file=${fileBasename}&lang=${languageId}")
                .build();
        cn.wty5.editor.lsp.LspConfig r = raw.resolve(ws);
        checkEq("/home/u/proj", r.cwd, "cwd expanded");
        checkEq("file:///home/u/proj", r.rootUri, "rootUri expanded");
        check(r.url.contains("file=main.go"), "fileBasename expanded");
        check(r.url.contains("lang=go"), "languageId expanded");
        checkEq("file:///home/u/proj/main.go",
                cn.wty5.editor.lsp.LspConfig.LspWorkspace.toFileUri(
                        "/home/u/proj/main.go"),
                "toFileUri");
    }
}

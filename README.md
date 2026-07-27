# CodeEditor

A high-performance Android code editor widget built from scratch in Java —
no `EditText`, no `Spannable`. Supports **grammar-file** languages and
**jar plugins** for syntax highlighting and code completion. Java and Go
ship built-in.

## Architecture

```
cn.wty5.editor.core        PieceTable / Document / UndoManager
cn.wty5.editor.lang        Lexer, GrammarLexer, LanguageSpec, GrammarLoader,
                           MiniJson, LanguageRegistry, Languages, JavaLexer
cn.wty5.editor.plugin      LanguagePlugin, PluginManager
cn.wty5.editor.highlight   Highlighter (pluggable Lexer), LineSpans, ColorScheme
cn.wty5.editor.complete    CompletionEngine / Provider (driven by LanguageSpec)
cn.wty5.editor.view        CodeEditorView, EditorInputConnection, CompletionPopup
grammars/                  java.json, go.json
```

## Languages: grammar files

A language is a JSON file describing keywords, types, snippets and lexical
rules. The generic `GrammarLexer` turns that into line-incremental
highlighting; `CompletionProvider` reads the same spec for keyword / snippet
suggestions (plus identifiers harvested from the open document).

```json
{
  "name": "go",
  "extensions": ["go"],
  "keywords": ["func", "defer", "go", "chan", ...],
  "types": ["string", "error", "int", ...],
  "snippets": [
    {"trigger": "iferr", "insert": "if err != nil {\n\treturn $0\n}", "detail": "..."}
  ],
  "lineComment": "//",
  "blockComment": ["/*", "*/"],
  "rawStringDelimiter": "`",
  "doubleQuotedStrings": true,
  "singleQuotedChars": true
}
```

Load extra grammars at runtime:

```java
editor.loadGrammars(new File("/sdcard/grammars"));
editor.setLanguage("go");          // by name
editor.setLanguageByExtension("py"); // by file extension
```

Built-ins (`java`, `go`) are registered automatically via
`Languages.ensureBuiltins()` — from classpath `/grammars/*.json`, then
`./grammars/*.json`, then a hard-coded fallback so the editor always works.

## Plugins

Implement `LanguagePlugin` and ship it as a jar:

```java
public class PythonPlugin implements LanguagePlugin {
    public String getName() { return "python"; }
    public String[] getExtensions() { return new String[]{"py"}; }
    public LanguageSpec getSpec() { /* builder or GrammarLoader */ }
    // optional: public Lexer createLexer() { return new MyLexer(); }
}
```

Declare the class in `META-INF/services/cn.wty5.editor.plugin.LanguagePlugin`
(or `META-INF/editor-plugin.txt`) and install:

```java
editor.getPluginManager().installJar(new File("python-plugin.jar"));
editor.setLanguage("python");
```

`PluginManager` also accepts `installClass(fqcn)` and direct
`install(pluginInstance)`.

## Editor usage

```java
CodeEditorView editor = new CodeEditorView(context);
editor.setLanguage("java");
editor.setText(sourceCode);
layout.addView(editor);

// later, open a Go file:
editor.setLanguageByExtension("go");
editor.setText(goSource);

editor.undo(); editor.redo();
String text = editor.getText();
```

## Language Server Protocol (LSP)

LSP is configured **declaratively** on each language — either in the grammar
JSON or by a plugin — and the editor auto-connects when the language is set.

### Grammar JSON (`"lsp"` block)

```json
{
  "name": "go",
  "extensions": ["go"],
  "keywords": ["func", "defer", "..."],
  "lsp": {
    "transport": "stdio",
    "command": ["gopls", "serve"],
    "cwd": "${workspaceFolder}",
    "rootUri": "${workspaceFolderUri}",
    "languageId": "go",
    "enabled": true
  }
}
```

Supported transports:

| `transport` / `type` | How it connects |
|---|---|
| `stdio` (default) | Spawn `command` (+ optional `args` / `env` / `cwd`) |
| `tcp` / `socket` | Raw TCP to `host`:`port` (or `url: "tcp://host:port"`) |
| `websocket` / `ws` | RFC 6455 text frames to `url` (`ws://…`) |
| `http` / `https` | JSON-RPC POST to `url` + optional SSE stream on `sseUrl` |

Placeholders expanded against `editor.setLspWorkspace(folder, file)`:

`${workspaceFolder}` `${workspaceFolderUri}` `${workspaceFolderBasename}`
`${file}` `${fileUri}` `${fileBasename}` `${languageId}`

Sample grammars ship with `enabled: false` so the demo APK does not try to
spawn `gopls`/`jdtls` on devices that lack them. Flip to `true` (or override
from a plugin) once the binary is on `PATH`.

### Plugin override

```java
public final class GoPlugin implements LanguagePlugin {
    public String getName() { return "go"; }
    public String[] getExtensions() { return new String[]{"go"}; }
    public LanguageSpec getSpec() { return Languages.go(); }

    @Override public LspConfig getLspConfig() {
        // Wins over the grammar's "lsp" block.
        return LspConfig.builder()
            .transport(LspConfig.Transport.STDIO)
            .command(extractedGoplsPath, "serve")
            .cwd("${workspaceFolder}")
            .build();
    }
}
```

Resolution order: `plugin.getLspConfig()` → `LanguageSpec.lsp` → none.

### Editor API

```java
// Tell the editor where the project / file live (drives placeholders + URI).
editor.setLspWorkspace("/sdcard/MyProject", "/sdcard/MyProject/main.go");
editor.setLanguage("go");   // auto-starts LSP when the grammar enables it

// Or drive it imperatively:
editor.startLsp(LspConfig.builder()
    .transport(LspConfig.Transport.WEBSOCKET)
    .url("ws://127.0.0.1:2087/lsp")
    .rootUri("file:///sdcard/MyProject")
    .build());

editor.startLsp(Arrays.asList("gopls", "serve"),
    "file:///sdcard/MyProject",
    "file:///sdcard/MyProject/main.go",
    "go");

editor.setAutoStartLsp(false); // opt out of grammar-driven connect
editor.stopLsp();
```

What you get automatically once connected:

- `textDocument/didOpen` / `didChange` (full sync, debounced 250 ms) / `didClose`
- Completions from the server **merged** with the local grammar list
- `publishDiagnostics` → squiggly underlines + gutter severity ticks

### Design notes

- **Piece table**: original + append-only add buffer; O(log n) line index via
  cached prefix sums. ~0.02 ms/edit on a 2 MB file after 10k edits.
- **Undo**: merges typing/backspace runs (800 ms); newlines seal steps;
  `beginBatch`/`endBatch` for completions.
- **Highlighting**: pluggable `Lexer`; ≤256 dirty lines per async round with
  state-cascade; version-checked publish.
- **Completion**: debounced; keywords/snippets from `LanguageSpec`; document
  words ranked by prefix / camel-hump / substring; optional LSP merge.
- **Selection handles**: platform `textSelectHandleLeft/Right` drawables from
  the activity theme (Material teardrops), tinted to the colour scheme.
- **Threading**: piece table is UI-thread only; workers receive immutable
  string snapshots; LSP I/O is off-thread with UI-posted callbacks.

## Build & test

The Android platform package is **not** in git (it is ~100 MB+ and
redistributable only under the Android SDK license). Fetch `android.jar`
once, then build:

```bash
# Downloads platform-35 android.jar into android-sdk/ (gitignored)
./scripts/fetch-android-platform.sh 35

# Core tests (plain JDK) + full compile against android.jar
./build.sh
```

Or point at an existing SDK install:

```bash
export ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar
./build.sh
```

Step by step without the script:

```bash
# 1. Core (no Android):
javac -d build/core-classes $(find src/main/java/cn/wty5/editor -name '*.java' \
  ! -path '*/view/*' ! -name Highlighter.java ! -name CompletionEngine.java)
javac -cp build/core-classes -d build/core-classes test/*.java
java -cp build/core-classes CoreTest
java -cp build/core-classes GrammarTest
java -cp build/core-classes LspTest

# 2. Full tree vs platform jar:
javac --release 17 -cp "$ANDROID_JAR" -d build/android-classes \
  $(find src -name '*.java')
```

## Layout

```
src/main/java/cn/wty5/editor/...   library sources
  complete/   CompletionEngine (+ ExternalSource for LSP merge)
  lsp/        LspClient, LspConfig, LspConnector, LspTransport, Diagnostic
  lang/       LanguageSpec (+ lsp field), GrammarLoader, MiniJson
  plugin/     LanguagePlugin (+ getLspConfig), PluginManager
  view/       CodeEditorView (native handles, diagnostics, auto LSP)
src/main/resources/grammars/   java.json, go.json (classpath, with "lsp" samples)
grammars/                      same files at project root (dev / loadFromDirectory)
test/                          CoreTest, GrammarTest, LspTest (plain JDK)
scripts/fetch-android-platform.sh
scripts/build-demo-apk.sh
build.sh                       compile + test entry point
android-sdk/                   local only (gitignored) — platform android.jar
build/                         local only (gitignored) — class output
dist/                          local only (gitignored) — demo APK
```

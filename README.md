# CodeEditor

A high-performance Android code editor widget built from scratch in Java —
no `EditText`, no `Spannable`. Supports **grammar-file** languages and
**jar plugins** for syntax highlighting and code completion. Java and Go
ship built-in.

## Architecture

```
com.editor.core       PieceTable / Document / UndoManager
com.editor.lang       Lexer, GrammarLexer, LanguageSpec, GrammarLoader,
                      MiniJson, LanguageRegistry, Languages, JavaLexer
com.editor.plugin     LanguagePlugin, PluginManager
com.editor.highlight  Highlighter (pluggable Lexer), LineSpans, ColorScheme
com.editor.complete   CompletionEngine / Provider (driven by LanguageSpec)
com.editor.view       CodeEditorView, EditorInputConnection, CompletionPopup
grammars/             java.json, go.json
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

Declare the class in `META-INF/services/com.editor.plugin.LanguagePlugin`
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

## Design notes

- **Piece table**: original + append-only add buffer; O(log n) line index via
  cached prefix sums. ~0.02 ms/edit on a 2 MB file after 10k edits.
- **Undo**: merges typing/backspace runs (800 ms); newlines seal steps;
  `beginBatch`/`endBatch` for completions.
- **Highlighting**: pluggable `Lexer`; ≤256 dirty lines per async round with
  state-cascade; version-checked publish.
- **Completion**: debounced; keywords/snippets from `LanguageSpec`; document
  words ranked by prefix / camel-hump / substring.
- **Threading**: piece table is UI-thread only; workers receive immutable
  string snapshots.

## Build & test

```bash
# One-shot: core tests + full compile against android.jar
./build.sh

# Or step by step —
# 1. Core (no Android):
javac -d build/core-classes $(find src/main/java/com/editor -name '*.java' \
  ! -path '*/view/*' ! -name Highlighter.java ! -name CompletionEngine.java)
javac -cp build/core-classes -d build/core-classes test/*.java
java -cp build/core-classes CoreTest
java -cp build/core-classes GrammarTest

# 2. Full tree vs Android platform 35:
javac --release 17 \
  -cp android-sdk/platforms/android-35/android.jar \
  -d build/android-classes $(find src -name '*.java')
```

The Android platform jar was fetched from
`https://dl.google.com/android/repository/platform-35_r01.zip` into
`android-sdk/platforms/android-35/`.

## Layout

```
src/main/java/com/editor/...   library sources
src/main/resources/grammars/   java.json, go.json (classpath)
grammars/                      same files at project root (dev / loadFromDirectory)
test/                          CoreTest, GrammarTest (plain JDK)
android-sdk/                   platform-35 android.jar
build.sh                       compile + test entry point
```

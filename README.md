# CodeEditor

A high-performance Android code editor widget built from scratch in Java —
no `EditText`, no `Spannable`. Supports **grammar-file** languages and
**jar plugins** for syntax highlighting and code completion. Java and Go
ship built-in. Includes grammar/plugin-driven **LSP**, Android-style
selection handles, a self-drawn floating toolbar, and a TextView-like
IME bridge for English predictive input.

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

// Floating selection toolbar idle auto-hide (default 3000 ms; <=0 = never).
editor.setSelectionToolbarAutoHideDelay(5000L);
// Insertion handle idle auto-hide (default 5000 ms; <=0 = never).
editor.setInsertionHandleAutoHideDelay(5000L);
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

## Selection & floating toolbar

Selection uses Android theme handle drawables (`textSelectHandleLeft/Right/
Middle`) with AOSP hotspot placement so Material teardrops sit flush against
the caret. Dragging past the opposite edge **swaps handle roles** without
walking the fixed edge.

### Insertion handle (bare caret)

- **Shown on caret placement** (no toolbar) so the user can drag the caret.
- **Auto-hides after idle** (default 5 s; `setInsertionHandleAutoHideDelay(ms)`,
  `<= 0` disables).
- **Re-shown after pan / fling / pinch** finishes, so the caret stays locatable
  after viewport changes.
- Independent of the floating toolbar: first tap places caret + handle; second
  tap on the caret wakes Paste / Select all.

### Floating toolbar

A self-drawn floating toolbar (Cut / Copy / Paste / Select all) replaces
`ActionMode`:

- Pinned in **view coordinates** above the caret / selection midpoint so it
  tracks the on-screen anchor while the document scrolls underneath.
- **Not shown on first caret placement** — second tap on the caret, tap inside
  a selection, or long-press empty space (paste menu) shows it.
- **Hidden on pan / handle drag / pinch**, selection kept.
- **Re-shown by tapping inside the selection** (does not collapse the range).
- **Auto-hides after idle** (default 3 s; configurable via
  `setSelectionToolbarAutoHideDelay(ms)`, `<= 0` disables); tapping a toolbar
  item resets the timer.
- Hidden while the anchor is fully off-screen.
- Long-press on a **word** selects it + shows the selection toolbar; long-press
  on **empty / whitespace** places the caret and shows Paste / Select all.

## IME (English predictive input)

`EditorInputConnection` mirrors the piece table into a real
`SpannableStringBuilder` (`BaseInputConnection(fullEditor=true)`), so Gboard /
Samsung / etc. see the same `Editable` + `SPAN_COMPOSING` model as `TextView`:

- `getEditable()` / `getSurroundingText` / `getExtractedText` / cursor updates
- Composing region survives backspace (`girl` → delete `l` → still suggests for
  `gir`)
- Caret taps push the new selection into the Editable so the next commit /
  delete lands at the drawn caret
- API 34 `replaceText` lives in `EditorInputConnectionApi34` so older devices
  never resolve `TextAttribute`

## Auto-indent & grapheme clusters

- Enter / IME newline copies the previous line's indent; empty `{` blocks split
  with an extra indent level; typing `}` outdents to the matching open.
- Tab / Shift+Tab indent or outdent the current line (or every line of a
  multi-line selection); multi-line paste is re-indented to the caret column.
- Caret, selection, delete and word-select all snap to grapheme-cluster
  boundaries via `Paint.getTextRunCursor` (emoji / CJK / combining marks).

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
  the activity theme (Material teardrops), tinted to the colour scheme;
  hotspot-relative hit regions cancel the PNG's transparent padding.
- **IME**: Editable mirror + `SPAN_COMPOSING`; selection sync on every caret
  move so predictive candidates track the drawn caret.
- **Toolbar**: self-drawn, view-anchored; dismiss on pan / handle drag; re-show
  by tapping the selection; 3 s idle auto-hide.
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

### Package as JAR

```bash
# Runs core tests, compiles the library, writes dist/:
#   codeeditor-<ver>.jar
#   codeeditor-<ver>-sources.jar
#   codeeditor-<ver>.pom
#   maven-repo/   (local Maven layout)
./scripts/build-jar.sh

# Pin a version (otherwise VERSION file → git tag → 0.1.0-SNAPSHOT):
VERSION=1.2.3 ./scripts/build-jar.sh
SKIP_TESTS=1 ./scripts/build-jar.sh
```

Coordinates: **`cn.wty5:codeeditor:<version>`**.

Local Gradle install from the generated repo layout:

```kotlin
repositories {
    maven { url = uri("/path/to/CodeEditor/dist/maven-repo") }
}
dependencies {
    implementation("cn.wty5:codeeditor:0.1.0")
}
// or
implementation(files("path/to/codeeditor-0.1.0.jar"))
```

### Publish & release

GitHub Actions workflows (no Gradle required):

| Workflow | Trigger | What it does |
|---|---|---|
| **CI** (`.github/workflows/ci.yml`) | push / PR to `main` | fetch `android.jar`, run tests, build JAR, upload artifact |
| **Release** (`.github/workflows/release.yml`) | tag `v*.*.*` or manual dispatch | build JAR (+sources/javadoc), publish **GitHub Packages**, optionally **Maven Central**, create GitHub Release |

Release from a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
# → Release workflow builds, publishes, opens GitHub Release
```

Or **Actions → Release → Run workflow** and enter `0.1.0`.

### Maven Central

Coordinates: **`cn.wty5:codeeditor:<version>`** (same as GitHub Packages).

This is **not** automatic from a plain git push — you must complete Sonatype Portal setup once, then either run the script locally or let the Release workflow upload with secrets.

#### One-time setup

1. **Account** — sign in at [central.sonatype.com](https://central.sonatype.com) (GitHub login is fine).
2. **Namespace** — claim and verify a namespace that matches `groupId`:
   - `cn.wty5` → you must prove control of domain **`wty5.cn`** (DNS TXT) on the Portal; **or**
   - easier for GitHub-only projects: claim `io.github.WangTianYou537` and publish with  
     `GROUP_ID=io.github.WangTianYou537 ./scripts/build-jar.sh`  
     (package `cn.wty5.editor` can stay; only Maven coordinates change).
3. **User token** — Account → *Generate User Token* → keep **username + password** pair.
4. **GPG key** (signing is mandatory):

```bash
gpg --full-generate-key   # RSA 4096, no expiry (or long)
gpg --list-secret-keys --keyid-format LONG
# publish the public key so Central can verify signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --export-secret-keys -a YOUR_KEY_ID > secring.asc
```

5. **GitHub repo secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | Portal user-token username |
| `CENTRAL_PASSWORD` | Portal user-token password |
| `GPG_PRIVATE_KEY` | Full ASCII-armored private key (`secring.asc` contents) |
| `GPG_PASSPHRASE` | Key passphrase (empty string if none) |
| `GPG_KEY_ID` | Long key id |

After secrets are set, the next `v*.*.*` tag Release run signs a Central bundle and uploads with `publishingType=AUTOMATIC`. Once validated it appears on Maven Central (search.maven.org / repo1) — usually within minutes to a few hours.

#### Local publish

```bash
VERSION=0.1.0 ./scripts/build-jar.sh          # jar + sources + javadoc + pom
# Sign + zip only (no upload):
./scripts/publish-maven-central.sh
# Sign + upload + wait for PUBLISHED:
CENTRAL_USERNAME=... CENTRAL_PASSWORD=... \
  GPG_KEY_ID=... GPG_PASSPHRASE=... \
  ./scripts/publish-maven-central.sh --upload --wait
```

Bundle path: `dist/codeeditor-<ver>-central-bundle.zip` (also attachable to the GitHub Release).

#### Consume from Maven Central (after publish)

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("cn.wty5:codeeditor:0.1.0")
}
```

No token needed for consumers once the component is on Central.

### GitHub Packages (optional mirror)

Manual publish (needs a PAT with `write:packages`):

```bash
VERSION=0.1.0 ./scripts/build-jar.sh
GITHUB_TOKEN=ghp_... ./scripts/publish-github-packages.sh
```

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/WangTianYou537/CodeEditor")
        credentials {
            username = project.findProperty("gpr.user") as String?
                ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String?
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
dependencies {
    implementation("cn.wty5:codeeditor:0.1.0")
}
```

`~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PAT   # read:packages (and repo if private)
```

### Step by step without the scripts

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

# 3. JAR:
jar cf dist/codeeditor.jar -C build/android-classes .
```

## Layout

```
src/main/java/cn/wty5/editor/...   library sources
  complete/   CompletionEngine (+ ExternalSource for LSP merge)
  lsp/        LspClient, LspConfig, LspConnector, LspTransport, Diagnostic
  lang/       LanguageSpec (+ lsp field), GrammarLoader, MiniJson
  plugin/     LanguagePlugin (+ getLspConfig), PluginManager
  view/       CodeEditorView, EditorInputConnection (+Api34),
              CompletionPopup, self-drawn selection toolbar
src/main/resources/grammars/   java.json, go.json (classpath, with "lsp" samples)
grammars/                      same files at project root (dev / loadFromDirectory)
test/                          CoreTest, GrammarTest, LspTest (plain JDK)
scripts/fetch-android-platform.sh
scripts/build-demo-apk.sh
scripts/build-jar.sh           library JAR + sources + javadoc + POM + local Maven repo
scripts/publish-github-packages.sh
scripts/publish-maven-central.sh  GPG-sign + Central Portal bundle / upload
.github/workflows/ci.yml       test + package on push/PR
.github/workflows/release.yml  tag → GitHub Release + Packages + Maven Central
VERSION                        default package version (overridden by tag / env)
LICENSE                        Apache-2.0
build.sh                       compile + test entry point
android-sdk/                   local only (gitignored) — platform android.jar
build/                         local only (gitignored) — class output
dist/                          local only (gitignored) — JAR / demo APK / maven-repo
```

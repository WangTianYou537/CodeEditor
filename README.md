# CodeEditor

A high-performance Android code editor widget built from scratch in Java —
no `EditText`, no `Spannable`.

## Architecture

```
com.editor.core       PieceTable        piece-table text buffer, O(log n) line index
                      Document          change events + versioning over the table
                      UndoManager       merged typing runs, batches, undo/redo stacks
com.editor.lang       JavaLexer         line-incremental Java lexer (3-state)
                      TokenType         token categories
com.editor.highlight  Highlighter       async chunked re-lexing with cascade
                      LineSpans         per-line spans in primitive arrays
                      ColorScheme       theme
com.editor.complete   CompletionEngine  debounced async orchestration
                      CompletionProvider keywords + snippets + document words
                      CompletionItem    suggestion model
com.editor.view       CodeEditorView    canvas rendering, IME, gestures, caret
                      EditorInputConnection  soft-keyboard bridge
                      CompletionPopup   anchored suggestion list
```

## Design notes

- **Piece table**: original + append-only add buffer; edits splice a piece
  list. Adjacent typing merges pieces. Each piece caches line-break counts
  and lazy prefix sums → `lineStart`/`lineOfOffset` are binary searches.
  Measured: ~0.02 ms/edit on a 2 MB document after 10k edits.
- **Undo**: listens to Document deltas; consecutive 1-char inserts (or a
  backspace run) within 800 ms merge into one step; newlines seal steps;
  `beginBatch`/`endBatch` groups programmatic edits (e.g. completions).
- **Highlighting**: only lines whose lexer in-state could have changed are
  re-lexed, ≤256 lines per round on a worker thread; results are dropped if
  the document version moved. Rendering reads only cached spans.
- **Threading**: the piece table is single-threaded (UI). Workers only ever
  receive immutable string snapshots; staleness is detected by version.
- **View**: draws only visible lines; monospace fast-path uses column ×
  charWidth for all x-positions.

## Testing

Core (non-Android) classes compile and run against a plain JDK:

```
javac -d build/classes $(find src -path "*view*" -prune -o -name "*.java" -print)
javac -cp build/classes -d build/classes test/CoreTest.java
java  -cp build/classes CoreTest        # smoke + fuzz vs StringBuilder
```

The view/highlighter/engine classes compile against the API stubs in
`stubs/` (compile-time only — never package them):

```
javac -d build/stub-classes $(find stubs -name "*.java")
javac -cp build/stub-classes -d build/all-classes $(find src -name "*.java")
```

## Usage

```java
CodeEditorView editor = new CodeEditorView(context);
editor.setText(sourceCode);
layout.addView(editor);
// editor.undo(); editor.redo(); editor.getText();
```

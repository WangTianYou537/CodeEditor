package com.editor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import com.editor.complete.CompletionEngine;
import com.editor.complete.CompletionItem;
import com.editor.core.Document;
import com.editor.core.UndoManager;
import com.editor.highlight.ColorScheme;
import com.editor.highlight.Highlighter;
import com.editor.highlight.LineSpans;
import com.editor.lang.LanguageRegistry;
import com.editor.lang.LanguageSpec;
import com.editor.lang.Languages;
import com.editor.lang.Lexer;
import com.editor.lang.TokenType;
import com.editor.plugin.PluginManager;

import java.io.File;
import java.util.List;

/**
 * The editor widget.
 *
 * Renders the {@link Document} directly onto the canvas — no Spannable, no
 * TextView. Only visible lines are drawn; with the piece table's O(log n)
 * line lookups and per-line cached spans, frame cost is proportional to the
 * viewport, not the file.
 *
 * Owns caret/selection state, IME plumbing, scrolling, undo wiring and the
 * completion popup.
 */
public class CodeEditorView extends View
        implements Highlighter.Callback, CompletionEngine.Callback {

    private Document document;
    private UndoManager undoManager;
    private Highlighter highlighter;
    private CompletionEngine completionEngine;
    private CompletionPopup completionPopup;
    private final ColorScheme scheme = new ColorScheme();
    private LanguageSpec language;
    private final PluginManager pluginManager = new PluginManager();

    // -- caret & selection (offsets into the document) -------------------
    private int caret;
    private int selectionAnchor = -1; // -1 = no selection
    private boolean caretVisible = true;

    // -- composing region for IME (simplified) ---------------------------
    private int composingStart = -1;
    private int composingEnd = -1;

    // -- metrics ---------------------------------------------------------
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private float charWidth;
    private float lineHeight;
    private float baselineShift;
    private float gutterWidth;

    // -- scrolling --------------------------------------------------------
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;

    private final Runnable caretBlink = new Runnable() {
        @Override
        public void run() {
            caretVisible = !caretVisible;
            invalidate();
            postDelayed(this, 500);
        }
    };

    public CodeEditorView(Context context) {
        this(context, null);
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        float density = context.getResources().getDisplayMetrics().density;
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(15 * density);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setTextSize(13 * density);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = fm.descent - fm.ascent + fm.leading;
        baselineShift = -fm.ascent;
        charWidth = textPaint.measureText("M");

        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureListener());

        Languages.ensureBuiltins();
        this.language = Languages.java();
        setDocument(new Document());
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void setDocument(Document doc) {
        if (highlighter != null) {
            highlighter.shutdown();
        }
        if (completionEngine != null) {
            completionEngine.shutdown();
        }
        this.document = doc;
        this.undoManager = new UndoManager(doc);
        Lexer lexer = Languages.lexerFor(language == null ? "java" : language.name);
        this.highlighter = new Highlighter(doc, lexer, this);
        this.completionEngine = new CompletionEngine(doc, language, this);
        this.completionPopup = new CompletionPopup(this, scheme);
        this.completionPopup.setListener(this::applyCompletion);
        this.caret = 0;
        this.selectionAnchor = -1;
        updateGutterWidth();
        invalidate();
    }

    /**
     * Switches syntax highlighting and completion to the named language
     * (e.g. {@code "java"}, {@code "go"}). Unknown names fall back to a
     * plain-text lexer with no keywords.
     */
    public void setLanguage(String name) {
        Languages.ensureBuiltins();
        LanguageSpec spec = LanguageRegistry.getInstance().getSpec(name);
        if (spec == null) {
            spec = new LanguageSpec.Builder().name(name == null ? "plain" : name).build();
        }
        setLanguage(spec);
    }

    /** Switches language from a file extension (without or with leading dot). */
    public void setLanguageByExtension(String extension) {
        Languages.ensureBuiltins();
        LanguageSpec spec = LanguageRegistry.getInstance().getSpecByExtension(extension);
        if (spec == null) {
            spec = new LanguageSpec.Builder().name("plain").build();
        }
        setLanguage(spec);
    }

    public void setLanguage(LanguageSpec spec) {
        this.language = spec;
        if (highlighter != null) {
            highlighter.setLexer(Languages.lexerFor(spec.name));
        }
        if (completionEngine != null) {
            completionEngine.setLanguage(spec);
        }
        dismissCompletions();
        invalidate();
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * Loads every {@code *.json} grammar in {@code dir} into the shared
     * registry. Already-open editors pick up a newly loaded language the
     * next time {@link #setLanguage(String)} is called.
     */
    public List<String> loadGrammars(File dir) {
        return Languages.loadFromDirectory(dir);
    }

    public void setText(String text) {
        document.setText(text);
        undoManager.clear();
        caret = 0;
        selectionAnchor = -1;
        highlighter.invalidateAll();
        updateGutterWidth();
        scrollTo(0, 0);
        invalidate();
    }

    public Document getDocument() {
        return document;
    }

    public String getText() {
        return document.toString();
    }

    public void undo() {
        int c = undoManager.undo();
        if (c >= 0) {
            moveCaretTo(c, false);
            afterEdit();
        }
    }

    public void redo() {
        int c = undoManager.redo();
        if (c >= 0) {
            moveCaretTo(c, false);
            afterEdit();
        }
    }

    public boolean canUndo() {
        return undoManager.canUndo();
    }

    public boolean canRedo() {
        return undoManager.canRedo();
    }

    // ------------------------------------------------------------------
    // Editing primitives (all IME/keyboard paths funnel through these)
    // ------------------------------------------------------------------

    /** Inserts at the caret, replacing any active selection. */
    public void insertAtCaret(String text) {
        deleteSelectionIfAny();
        document.insert(caret, text);
        caret += text.length();
        afterEdit();
        requestCompletionsAtCaret();
    }

    public void deleteBackward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            return;
        }
        if (caret > 0) {
            document.delete(caret - 1, caret);
            caret--;
            afterEdit();
            requestCompletionsAtCaret();
        }
    }

    public void deleteForward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            return;
        }
        if (caret < document.length()) {
            document.delete(caret, caret + 1);
            afterEdit();
        }
    }

    private boolean deleteSelectionIfAny() {
        if (!hasSelection()) {
            return false;
        }
        int s = Math.min(caret, selectionAnchor);
        int e = Math.max(caret, selectionAnchor);
        document.delete(s, e);
        caret = s;
        selectionAnchor = -1;
        return true;
    }

    private void afterEdit() {
        composingStart = composingEnd = -1;
        clampCaret();
        updateGutterWidth();
        ensureCaretVisible();
        resetCaretBlink();
        invalidate();
    }

    private void clampCaret() {
        caret = Math.max(0, Math.min(caret, document.length()));
        if (selectionAnchor > document.length()) {
            selectionAnchor = -1;
        }
    }

    // ------------------------------------------------------------------
    // Selection & caret movement
    // ------------------------------------------------------------------

    public boolean hasSelection() {
        return selectionAnchor >= 0 && selectionAnchor != caret;
    }

    public void moveCaretTo(int offset, boolean extendSelection) {
        offset = Math.max(0, Math.min(offset, document.length()));
        if (extendSelection) {
            if (selectionAnchor < 0) {
                selectionAnchor = caret;
            }
        } else {
            selectionAnchor = -1;
        }
        caret = offset;
        undoManager.sealCurrent(); // caret jump ends the typing merge run
        ensureCaretVisible();
        resetCaretBlink();
        invalidate();
    }

    private void moveCaretVertically(int lineDelta, boolean extend) {
        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        int target = Math.max(0, Math.min(line + lineDelta, document.lineCount() - 1));
        moveCaretTo(document.offsetAt(target, col), extend);
    }

    /** Selects the identifier/word under the given offset. */
    private void selectWordAt(int offset) {
        int s = offset;
        int e = offset;
        while (s > 0 && Character.isJavaIdentifierPart(document.charAt(s - 1))) {
            s--;
        }
        while (e < document.length() && Character.isJavaIdentifierPart(document.charAt(e))) {
            e++;
        }
        if (s == e && e < document.length()) {
            e++; // no word: select the single char
        }
        selectionAnchor = s;
        caret = e;
        invalidate();
    }

    // ------------------------------------------------------------------
    // IME plumbing (called by EditorInputConnection)
    // ------------------------------------------------------------------

    void commitTextFromIme(String text) {
        if (composingStart >= 0) {
            document.replace(composingStart, composingEnd, text);
            caret = composingStart + text.length();
            composingStart = composingEnd = -1;
            afterEdit();
            requestCompletionsAtCaret();
        } else {
            insertAtCaret(text);
        }
    }

    void replaceComposingFromIme(String text) {
        if (composingStart < 0) {
            deleteSelectionIfAny();
            composingStart = caret;
            composingEnd = caret;
        }
        document.replace(composingStart, composingEnd, text);
        composingEnd = composingStart + text.length();
        caret = composingEnd;
        clampCaret();
        ensureCaretVisible();
        invalidate();
        requestCompletionsAtCaret();
    }

    void finishComposingFromIme() {
        composingStart = composingEnd = -1;
    }

    void deleteSurroundingFromIme(int before, int after) {
        int s = Math.max(0, caret - before);
        int e = Math.min(document.length(), caret + after);
        if (e > caret) {
            document.delete(caret, e);
        }
        if (caret > s) {
            document.delete(s, caret);
            caret = s;
        }
        afterEdit();
    }

    CharSequence textBeforeCursor(int length) {
        int s = Math.max(0, caret - length);
        return document.substring(s, caret);
    }

    CharSequence textAfterCursor(int length) {
        int e = Math.min(document.length(), caret + length);
        return document.substring(caret, e);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new EditorInputConnection(this);
    }

    // ------------------------------------------------------------------
    // Hardware keys
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean shift = event.isShiftPressed();
        boolean ctrl = event.isCtrlPressed();

        // Completion popup owns navigation keys while visible.
        if (completionPopup.isShowing()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    completionPopup.moveSelection(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    completionPopup.moveSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_TAB:
                    if (completionPopup.pickSelected()) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_ESCAPE:
                    dismissCompletions();
                    return true;
                default:
                    break;
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                deleteBackward();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                deleteForward();
                return true;
            case KeyEvent.KEYCODE_ENTER:
                insertAtCaret("\n" + autoIndentForCaretLine());
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_TAB:
                insertAtCaret("    ");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCaretTo(caret - 1, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCaretTo(caret + 1, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCaretVertically(-1, shift);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCaretVertically(1, shift);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineStart(line), shift);
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_END: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineEnd(line), shift);
                return true;
            }
            case KeyEvent.KEYCODE_Z:
                if (ctrl) {
                    if (shift) {
                        redo();
                    } else {
                        undo();
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_Y:
                if (ctrl) {
                    redo();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_A:
                if (ctrl) {
                    selectionAnchor = 0;
                    caret = document.length();
                    invalidate();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_SPACE:
                if (ctrl) {
                    requestCompletionsAtCaret();
                    return true;
                }
                break;
            default:
                break;
        }

        // Printable characters from a hardware keyboard.
        int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode != 0 && !ctrl) {
            insertAtCaret(String.valueOf((char) unicode));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Leading whitespace of the caret's line, plus one level after '{'. */
    private String autoIndentForCaretLine() {
        int line = document.lineOfOffset(caret);
        int start = document.lineStart(line);
        int end = Math.min(caret, document.lineEnd(line));
        StringBuilder indent = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = document.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        if (end > start && document.charAt(end - 1) == '{') {
            indent.append("    ");
        }
        return indent.toString();
    }

    // ------------------------------------------------------------------
    // Completions
    // ------------------------------------------------------------------

    private void requestCompletionsAtCaret() {
        completionEngine.requestCompletions(caret);
    }

    private void dismissCompletions() {
        completionEngine.cancel();
    }

    @Override
    public void onCompletions(List<CompletionItem> items, int prefixStart, String prefix) {
        if (items.isEmpty()) {
            completionPopup.dismiss();
            return;
        }
        this.completionPrefixStart = prefixStart;
        this.completionPrefixLength = prefix.length();
        int[] xy = new int[2];
        getLocationInWindow(xy);
        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        int x = xy[0] + (int) (gutterWidth + col * charWidth) - getScrollX();
        int y = xy[1] + (int) ((line + 1) * lineHeight) - getScrollY();
        completionPopup.show(items, x, y);
    }

    private int completionPrefixStart;
    private int completionPrefixLength;

    private void applyCompletion(CompletionItem item) {
        String insert = item.insertText;
        int caretMark = insert.indexOf("$0");
        if (caretMark >= 0) {
            insert = insert.substring(0, caretMark) + insert.substring(caretMark + 2);
        }
        undoManager.beginBatch();
        try {
            int end = completionPrefixStart + completionPrefixLength;
            document.replace(completionPrefixStart, Math.min(end, document.length()), insert);
            caret = completionPrefixStart + (caretMark >= 0 ? caretMark : insert.length());
        } finally {
            undoManager.endBatch();
        }
        afterEdit();
        completionPopup.dismiss();
    }

    // ------------------------------------------------------------------
    // Highlighter callback
    // ------------------------------------------------------------------

    @Override
    public void onHighlightUpdated(int firstLine, int lastLine) {
        int firstVisible = (int) (getScrollY() / lineHeight);
        int lastVisible = (int) ((getScrollY() + getHeight()) / lineHeight) + 1;
        if (lastLine >= firstVisible && firstLine <= lastVisible) {
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(scheme.background);

        int firstLine = Math.max(0, (int) (getScrollY() / lineHeight));
        int lastLine = Math.min(document.lineCount() - 1,
                (int) ((getScrollY() + getHeight()) / lineHeight) + 1);

        int caretLine = document.lineOfOffset(caret);
        int selStart = hasSelection() ? Math.min(caret, selectionAnchor) : -1;
        int selEnd = hasSelection() ? Math.max(caret, selectionAnchor) : -1;

        for (int line = firstLine; line <= lastLine; line++) {
            float top = line * lineHeight;
            float baseline = top + baselineShift;
            int lineStart = document.lineStart(line);
            String content = document.lineContent(line);

            // Current-line highlight.
            if (line == caretLine && !hasSelection()) {
                fillPaint.setColor(scheme.currentLine);
                canvas.drawRect(getScrollX() + gutterWidth, top,
                        getScrollX() + getWidth(), top + lineHeight, fillPaint);
            }

            // Selection background for this line's overlap.
            if (selStart >= 0) {
                int lineEnd = lineStart + content.length();
                int s = Math.max(selStart, lineStart);
                int e = Math.min(selEnd, lineEnd + 1); // +1 covers the '\n'
                if (s < e) {
                    float x1 = gutterWidth + (s - lineStart) * charWidth;
                    float x2 = gutterWidth + (e - lineStart) * charWidth;
                    fillPaint.setColor(scheme.selection);
                    canvas.drawRect(x1, top, x2, top + lineHeight, fillPaint);
                }
            }

            // Line number is drawn in the gutter pass below.

            // Text with spans (or plain while the highlighter catches up).
            LineSpans spans = highlighter.spansFor(line);
            float x = gutterWidth;
            if (spans == null || spans.size() == 0) {
                textPaint.setColor(scheme.foreground);
                canvas.drawText(content, x, baseline, textPaint);
            } else {
                int drawn = 0;
                for (int i = 0; i < spans.size(); i++) {
                    int s = Math.min(spans.start(i), content.length());
                    int e = Math.min(spans.end(i), content.length());
                    if (s >= e) {
                        continue;
                    }
                    if (drawn < s) { // gap between spans (shouldn't happen, be safe)
                        textPaint.setColor(scheme.foreground);
                        canvas.drawText(content, drawn, s, x + drawn * charWidth,
                                baseline, textPaint);
                    }
                    TokenType t = spans.type(i);
                    if (t != TokenType.WHITESPACE) {
                        textPaint.setColor(scheme.colorOf(t));
                        canvas.drawText(content, s, e, x + s * charWidth,
                                baseline, textPaint);
                    }
                    drawn = e;
                }
                if (drawn < content.length()) { // spans stale after an edit
                    textPaint.setColor(scheme.foreground);
                    canvas.drawText(content, drawn, content.length(),
                            x + drawn * charWidth, baseline, textPaint);
                }
            }

            // Caret.
            if (line == caretLine && caretVisible && isFocused()) {
                float cx = gutterWidth + (caret - lineStart) * charWidth;
                fillPaint.setColor(scheme.caret);
                canvas.drawRect(cx - 1, top, cx + 2, top + lineHeight, fillPaint);
            }
        }

        // Gutter pass: pinned to the left edge, drawn last so horizontally
        // scrolled text slides underneath it instead of over the numbers.
        fillPaint.setColor(scheme.gutterBackground);
        canvas.drawRect(getScrollX(), getScrollY(),
                getScrollX() + gutterWidth, getScrollY() + getHeight(), fillPaint);
        for (int line = firstLine; line <= lastLine; line++) {
            float baseline = line * lineHeight + baselineShift;
            gutterPaint.setColor(line == caretLine
                    ? scheme.gutterCurrentText : scheme.gutterText);
            String num = Integer.toString(line + 1);
            float numWidth = gutterPaint.measureText(num);
            canvas.drawText(num, getScrollX() + gutterWidth - numWidth - charWidth * 0.75f,
                    baseline, gutterPaint);
        }
    }

    private void updateGutterWidth() {
        int digits = Math.max(2, Integer.toString(document.lineCount()).length());
        gutterWidth = digits * gutterPaint.measureText("0") + charWidth * 1.5f;
    }

    // ------------------------------------------------------------------
    // Touch & scrolling
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !handled) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    private int offsetForPoint(float viewX, float viewY) {
        float docX = viewX + getScrollX() - gutterWidth;
        float docY = viewY + getScrollY();
        int line = Math.max(0, Math.min((int) (docY / lineHeight),
                document.lineCount() - 1));
        int col = Math.max(0, Math.round(docX / charWidth));
        return document.offsetAt(line, col);
    }

    private int maxScrollY() {
        return (int) Math.max(0, document.lineCount() * lineHeight - getHeight() / 2f);
    }

    private int maxScrollX() {
        // Cheap upper bound: longest visible line would be exact but costly;
        // allow generous horizontal room instead.
        return (int) (200 * charWidth);
    }

    private void ensureCaretVisible() {
        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        float top = line * lineHeight;
        float bottom = top + lineHeight;
        float x = gutterWidth + col * charWidth;

        int sx = getScrollX();
        int sy = getScrollY();
        if (top < sy) {
            sy = (int) top;
        } else if (bottom > sy + getHeight()) {
            sy = (int) (bottom - getHeight());
        }
        if (x < sx + gutterWidth) {
            sx = (int) Math.max(0, x - gutterWidth - charWidth * 4);
        } else if (x > sx + getWidth() - charWidth * 2) {
            sx = (int) (x - getWidth() + charWidth * 6);
        }
        if (sx != getScrollX() || sy != getScrollY()) {
            scroller.forceFinished(true);
            scrollTo(Math.max(0, sx), Math.max(0, sy));
        }
    }

    private void resetCaretBlink() {
        removeCallbacks(caretBlink);
        caretVisible = true;
        postDelayed(caretBlink, 500);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction,
                                  android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        removeCallbacks(caretBlink);
        if (gained) {
            resetCaretBlink();
        } else {
            dismissCompletions();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(caretBlink);
        completionPopup.dismiss();
        highlighter.shutdown();
        completionEngine.shutdown();
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDown(MotionEvent e) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            requestFocus();
            moveCaretTo(offsetForPoint(e.getX(), e.getY()), false);
            dismissCompletions();
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(CodeEditorView.this, 0);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            selectWordAt(offsetForPoint(e.getX(), e.getY()));
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            selectWordAt(offsetForPoint(e.getX(), e.getY()));
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                float dx, float dy) {
            int nx = Math.max(0, Math.min(getScrollX() + (int) dx, maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY() + (int) dy, maxScrollY()));
            scrollTo(nx, ny);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2,
                               float vx, float vy) {
            scroller.fling(getScrollX(), getScrollY(),
                    (int) -vx, (int) -vy,
                    0, maxScrollX(), 0, maxScrollY());
            postInvalidateOnAnimation();
            return true;
        }
    }
}

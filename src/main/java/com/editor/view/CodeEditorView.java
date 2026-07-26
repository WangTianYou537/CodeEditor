package com.editor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
 * Owns caret/selection state, IME plumbing, scrolling, pinch-zoom, undo
 * wiring and the completion popup.
 */
public class CodeEditorView extends View
        implements Highlighter.Callback, CompletionEngine.Callback {

    private static final float MIN_TEXT_SIZE_SP = 8f;
    private static final float MAX_TEXT_SIZE_SP = 48f;
    private static final float DEFAULT_TEXT_SIZE_SP = 15f;

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
    private final Rect visibleFrame = new Rect();
    private float density;
    private float textSizeSp = DEFAULT_TEXT_SIZE_SP;
    private float charWidth;
    private float lineHeight;
    private float baselineShift;
    private float gutterWidth;
    private float gutterPad;

    // -- scrolling / zoom ------------------------------------------------
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private boolean scaling;
    /** Anchor document position kept under the fingers during a pinch. */
    private float zoomFocusDocX;
    private float zoomFocusDocY;

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
        // Hardware layer: text + solid fills compose faster; pinch-zoom
        // just invalidates content, no layer thrash on every frame.
        setWillNotDraw(false);

        density = context.getResources().getDisplayMetrics().density;
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setSubpixelText(true);
        // SOLID style for fills — avoids accidental stroke state leaks.
        fillPaint.setStyle(Paint.Style.FILL);

        applyTextSize(textSizeSp, false);

        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new GestureListener());
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        // Quick-scale (double-tap-swipe) is confusing in an editor; pinch only.
        scaleDetector.setQuickScaleEnabled(false);

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
        if (completionPopup != null) {
            completionPopup.dismiss();
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

    /** Current font size in scaled pixels (sp). */
    public float getTextSizeSp() {
        return textSizeSp;
    }

    /** Sets font size in sp and reflows. Clamped to [{@value MIN_TEXT_SIZE_SP}, {@value MAX_TEXT_SIZE_SP}]. */
    public void setTextSizeSp(float sp) {
        applyTextSize(sp, true);
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
    // Font / zoom metrics
    // ------------------------------------------------------------------

    private void applyTextSize(float sp, boolean keepFocus) {
        float clamped = Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, sp));
        // Remember the document point under the viewport centre so a
        // programmatic size change (or end of pinch) doesn't jump.
        float focusDocX = 0, focusDocY = 0;
        float focusViewX = getWidth() / 2f;
        float focusViewY = getHeight() / 2f;
        if (keepFocus && charWidth > 0 && lineHeight > 0) {
            focusDocX = getScrollX() + focusViewX;
            focusDocY = getScrollY() + focusViewY;
        }

        textSizeSp = clamped;
        float px = textSizeSp * density;
        textPaint.setTextSize(px);
        gutterPaint.setTextSize(px * 0.85f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        // Quantize line height to whole pixels — stops baseline shimmer
        // while pinching and keeps caret/selection rects pixel-aligned.
        lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
        if (lineHeight < 1f) lineHeight = 1f;
        baselineShift = -fm.ascent;
        charWidth = textPaint.measureText("M");
        gutterPad = charWidth * 0.75f;
        updateGutterWidth();

        if (keepFocus && charWidth > 0) {
            int nx = Math.max(0, Math.round(focusDocX - focusViewX));
            int ny = Math.max(0, Math.round(focusDocY - focusViewY));
            nx = Math.min(nx, maxScrollX());
            ny = Math.min(ny, maxScrollY());
            scroller.forceFinished(true);
            scrollTo(nx, ny);
        }
        // Popup (if showing) needs re-anchoring at the new scale.
        if (completionPopup != null && completionPopup.isShowing()) {
            dismissCompletions();
        }
        invalidate();
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
        // Only pop completions for identifier-ish input, not spaces/symbols
        // that would just hide it again and steal a frame.
        if (shouldAutoCompleteAfter(text)) {
            requestCompletionsAtCaret();
        } else {
            dismissCompletions();
        }
    }

    public void deleteBackward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            dismissCompletions();
            return;
        }
        if (caret > 0) {
            document.delete(caret - 1, caret);
            caret--;
            afterEdit();
            // Backspace updates the prefix if a popup is already up; otherwise
            // don't spontaneously open one (avoids covering the IME mid-delete).
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    public void deleteForward() {
        if (deleteSelectionIfAny()) {
            afterEdit();
            dismissCompletions();
            return;
        }
        if (caret < document.length()) {
            document.delete(caret, caret + 1);
            afterEdit();
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    private static boolean shouldAutoCompleteAfter(String text) {
        if (text == null || text.isEmpty()) return false;
        // Single identifier character → complete. Multi-char commits from the
        // IME (e.g. CJK) also qualify if they end in an identifier part.
        char last = text.charAt(text.length() - 1);
        return Character.isJavaIdentifierPart(last) && !Character.isDigit(text.charAt(0));
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
            if (shouldAutoCompleteAfter(text)) {
                requestCompletionsAtCaret();
            } else {
                dismissCompletions();
            }
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
        // Composing updates are intermediate — don't pop the completion list
        // over the IME candidate bar on every keystroke of a CJK session.
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
        if (completionPopup != null && completionPopup.isShowing()) {
            requestCompletionsAtCaret();
        }
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
        if (completionPopup != null && completionPopup.isShowing()) {
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
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCaretTo(caret + 1, shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCaretVertically(-1, shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCaretVertically(1, shift);
                dismissCompletions();
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineStart(line), shift);
                dismissCompletions();
                return true;
            }
            case KeyEvent.KEYCODE_MOVE_END: {
                int line = document.lineOfOffset(caret);
                moveCaretTo(document.lineEnd(line), shift);
                dismissCompletions();
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
                    dismissCompletions();
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
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_PLUS:
                if (ctrl) {
                    setTextSizeSp(textSizeSp + 1f);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MINUS:
                if (ctrl) {
                    setTextSizeSp(textSizeSp - 1f);
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
        if (completionEngine != null) {
            completionEngine.requestCompletions(caret);
        }
    }

    private void dismissCompletions() {
        if (completionEngine != null) {
            completionEngine.cancel();
        } else if (completionPopup != null) {
            completionPopup.dismiss();
        }
    }

    @Override
    public void onCompletions(List<CompletionItem> items, int prefixStart, String prefix) {
        if (items == null || items.isEmpty()) {
            if (completionPopup != null) completionPopup.dismiss();
            return;
        }
        // Don't fight the user mid-gesture.
        if (scaling) {
            return;
        }
        this.completionPrefixStart = prefixStart;
        this.completionPrefixLength = prefix.length();

        // Caret rect in WINDOW coordinates, then clamp the popup to the
        // visible display frame (the region above the soft keyboard).
        int[] loc = new int[2];
        getLocationInWindow(loc);
        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        float caretDocX = gutterWidth + col * charWidth;
        float caretDocTop = line * lineHeight;
        int caretX = loc[0] + Math.round(caretDocX) - getScrollX();
        int caretTop = loc[1] + Math.round(caretDocTop) - getScrollY();
        int caretBottom = caretTop + Math.round(lineHeight);

        getWindowVisibleDisplayFrame(visibleFrame);
        completionPopup.show(items, caretX, caretTop, caretBottom, visibleFrame);
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
        // Only visible lines matter; with hardware acceleration a full
        // invalidate is cheap and the 4-arg form is deprecated post-API 28.
        if (lastLine >= firstVisible && firstLine <= lastVisible) {
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final int scrollX = getScrollX();
        final int scrollY = getScrollY();
        final int viewW = getWidth();
        final int viewH = getHeight();
        final float lh = lineHeight;
        final float cw = charWidth;
        final float gw = gutterWidth;

        canvas.drawColor(scheme.background);

        if (document == null || lh <= 0f || cw <= 0f) {
            return;
        }

        final int lineCount = document.lineCount();
        int firstLine = Math.max(0, (int) (scrollY / lh));
        int lastLine = Math.min(lineCount - 1,
                (int) ((scrollY + viewH) / lh) + 1);
        if (firstLine > lastLine) {
            drawGutter(canvas, scrollX, scrollY, viewH, firstLine, lastLine, -1);
            return;
        }

        int caretLine = document.lineOfOffset(caret);
        int selStart = hasSelection() ? Math.min(caret, selectionAnchor) : -1;
        int selEnd = hasSelection() ? Math.max(caret, selectionAnchor) : -1;

        // Clip text to the content area so it slides under the gutter
        // instead of painting over line numbers during horizontal scroll.
        int save = canvas.save();
        canvas.clipRect(scrollX + gw, scrollY, scrollX + viewW, scrollY + viewH);

        for (int line = firstLine; line <= lastLine; line++) {
            float top = line * lh;
            float baseline = top + baselineShift;
            int lineStart = document.lineStart(line);
            String content = document.lineContent(line);
            int contentLen = content.length();

            // Current-line highlight.
            if (line == caretLine && selStart < 0) {
                fillPaint.setColor(scheme.currentLine);
                canvas.drawRect(scrollX + gw, top,
                        scrollX + viewW, top + lh, fillPaint);
            }

            // Selection background for this line's overlap.
            if (selStart >= 0) {
                int lineEnd = lineStart + contentLen;
                int s = Math.max(selStart, lineStart);
                int e = Math.min(selEnd, lineEnd + 1); // +1 covers the '\n'
                if (s < e) {
                    float x1 = gw + (s - lineStart) * cw;
                    float x2 = gw + (e - lineStart) * cw;
                    fillPaint.setColor(scheme.selection);
                    canvas.drawRect(x1, top, x2, top + lh, fillPaint);
                }
            }

            // Text with spans. Stale spans (right after an edit, before the
            // worker republishes) are still painted — columns are clipped to
            // contentLen so we never read past the new line end. This is what
            // stops the colour flash when holding backspace in a comment.
            LineSpans spans = highlighter.spansFor(line);
            if (spans == null || spans.size() == 0 || contentLen == 0) {
                if (contentLen > 0) {
                    textPaint.setColor(scheme.foreground);
                    canvas.drawText(content, gw, baseline, textPaint);
                }
            } else {
                drawSpannedLine(canvas, content, contentLen, spans, gw, baseline, cw);
            }

            // Caret.
            if (line == caretLine && caretVisible && isFocused()) {
                float cx = gw + (caret - lineStart) * cw;
                fillPaint.setColor(scheme.caret);
                // 2-device-px wide caret, pixel-aligned.
                float caretW = Math.max(2f, density);
                canvas.drawRect(cx, top, cx + caretW, top + lh, fillPaint);
            }
        }
        canvas.restoreToCount(save);

        drawGutter(canvas, scrollX, scrollY, viewH, firstLine, lastLine, caretLine);
    }

    /**
     * Paints one line from its span list. Spans whose columns fall past
     * {@code contentLen} (stale after a delete) are clipped; gaps are filled
     * with the default foreground so a partially-updated line never shows
     * holes.
     */
    private void drawSpannedLine(Canvas canvas, String content, int contentLen,
                                 LineSpans spans, float x0, float baseline,
                                 float cw) {
        int drawn = 0;
        int n = spans.size();
        for (int i = 0; i < n; i++) {
            int s = spans.start(i);
            int e = spans.end(i);
            if (s < 0) s = 0;
            if (e > contentLen) e = contentLen;
            if (s >= e) continue;
            if (s > contentLen) break;

            if (drawn < s) {
                textPaint.setColor(scheme.foreground);
                canvas.drawText(content, drawn, s, x0 + drawn * cw, baseline, textPaint);
            }
            TokenType t = spans.type(i);
            if (t != TokenType.WHITESPACE) {
                textPaint.setColor(scheme.colorOf(t));
                canvas.drawText(content, s, e, x0 + s * cw, baseline, textPaint);
            }
            drawn = e;
        }
        if (drawn < contentLen) {
            textPaint.setColor(scheme.foreground);
            canvas.drawText(content, drawn, contentLen,
                    x0 + drawn * cw, baseline, textPaint);
        }
    }

    private void drawGutter(Canvas canvas, int scrollX, int scrollY, int viewH,
                            int firstLine, int lastLine, int caretLine) {
        fillPaint.setColor(scheme.gutterBackground);
        canvas.drawRect(scrollX, scrollY,
                scrollX + gutterWidth, scrollY + viewH, fillPaint);

        if (firstLine > lastLine || lineHeight <= 0f) return;

        final float rightPad = gutterPad;
        for (int line = firstLine; line <= lastLine; line++) {
            float baseline = line * lineHeight + baselineShift;
            gutterPaint.setColor(line == caretLine
                    ? scheme.gutterCurrentText : scheme.gutterText);
            String num = Integer.toString(line + 1);
            float numWidth = gutterPaint.measureText(num);
            canvas.drawText(num,
                    scrollX + gutterWidth - numWidth - rightPad,
                    baseline, gutterPaint);
        }
    }

    private void updateGutterWidth() {
        if (document == null || gutterPaint.getTextSize() <= 0f) {
            gutterWidth = 0;
            return;
        }
        int digits = Math.max(2, Integer.toString(document.lineCount()).length());
        // measureText("0") * digits is stable under monospace; cache-friendly.
        gutterWidth = digits * gutterPaint.measureText("0") + charWidth * 1.5f;
    }

    // ------------------------------------------------------------------
    // Touch & scrolling
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Scale detector first so a two-finger pinch isn't also interpreted
        // as a scroll by the gesture detector.
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            scaling = true;
            scroller.forceFinished(true);
            dismissCompletions();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scaling = false;
        }
        // While pinching, swallow one-finger gestures entirely.
        if (scaling || event.getPointerCount() > 1) {
            return true;
        }

        boolean handled = gestureDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP && !handled) {
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
        if (lineHeight <= 0f) return 0;
        return (int) Math.max(0, document.lineCount() * lineHeight - getHeight() / 2f);
    }

    private int maxScrollX() {
        return (int) Math.max(0, 200 * charWidth);
    }

    private void ensureCaretVisible() {
        if (lineHeight <= 0f || charWidth <= 0f || getWidth() == 0) return;
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
        sx = Math.max(0, Math.min(sx, maxScrollX()));
        sy = Math.max(0, Math.min(sy, maxScrollY()));
        if (sx != getScrollX() || sy != getScrollY()) {
            scroller.forceFinished(true);
            scrollTo(sx, sy);
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // IME show/hide resizes us via windowInsets / adjustablePan alternatives;
        // re-clamp scroll and re-anchor the popup if it's up.
        int sx = Math.min(getScrollX(), maxScrollX());
        int sy = Math.min(getScrollY(), maxScrollY());
        if (sx != getScrollX() || sy != getScrollY()) {
            scrollTo(sx, sy);
        }
        if (completionPopup != null && completionPopup.isShowing()) {
            // Re-query with the new visible frame so the popup climbs above
            // a newly shown keyboard instead of sitting on top of it.
            requestCompletionsAtCaret();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(caretBlink);
        if (completionPopup != null) completionPopup.dismiss();
        if (highlighter != null) highlighter.shutdown();
        if (completionEngine != null) completionEngine.shutdown();
    }

    // ------------------------------------------------------------------
    // Gesture listeners
    // ------------------------------------------------------------------

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            scaling = true;
            scroller.forceFinished(true);
            dismissCompletions();
            // Document point currently under the pinch focus.
            zoomFocusDocX = getScrollX() + detector.getFocusX();
            zoomFocusDocY = getScrollY() + detector.getFocusY();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0f) {
                return false;
            }
            float newSp = textSizeSp * factor;
            // Hard clamp before applying so a fling-pinch doesn't overshoot
            // and then feel sticky against the stop.
            if (newSp < MIN_TEXT_SIZE_SP) newSp = MIN_TEXT_SIZE_SP;
            if (newSp > MAX_TEXT_SIZE_SP) newSp = MAX_TEXT_SIZE_SP;
            if (Math.abs(newSp - textSizeSp) < 0.01f) {
                return true;
            }

            // Recompute metrics, then keep the original document point locked
            // under the (possibly moving) focus fingers.
            textSizeSp = newSp;
            float px = textSizeSp * density;
            textPaint.setTextSize(px);
            gutterPaint.setTextSize(px * 0.85f);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            lineHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
            if (lineHeight < 1f) lineHeight = 1f;
            baselineShift = -fm.ascent;
            charWidth = textPaint.measureText("M");
            gutterPad = charWidth * 0.75f;
            updateGutterWidth();

            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();
            int nx = Math.round(zoomFocusDocX - focusX);
            int ny = Math.round(zoomFocusDocY - focusY);
            nx = Math.max(0, Math.min(nx, maxScrollX()));
            ny = Math.max(0, Math.min(ny, maxScrollY()));
            scrollTo(nx, ny);
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            scaling = false;
            // Snap scroll to legal range once more after the last factor.
            int nx = Math.max(0, Math.min(getScrollX(), maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY(), maxScrollY()));
            scrollTo(nx, ny);
            invalidate();
        }
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
            if (scaling) return false;
            int nx = Math.max(0, Math.min(getScrollX() + Math.round(dx), maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY() + Math.round(dy), maxScrollY()));
            scrollTo(nx, ny);
            // Keep a showing popup glued to the caret while the user pans.
            if (completionPopup != null && completionPopup.isShowing()) {
                dismissCompletions();
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2,
                               float vx, float vy) {
            if (scaling) return false;
            scroller.fling(getScrollX(), getScrollY(),
                    Math.round(-vx), Math.round(-vy),
                    0, maxScrollX(), 0, maxScrollY());
            postInvalidateOnAnimation();
            return true;
        }
    }
}

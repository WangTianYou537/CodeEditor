package cn.wty5.editor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import cn.wty5.editor.complete.CompletionEngine;
import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.core.Document;
import cn.wty5.editor.core.UndoManager;
import cn.wty5.editor.highlight.ColorScheme;
import cn.wty5.editor.highlight.Highlighter;
import cn.wty5.editor.highlight.LineSpans;
import cn.wty5.editor.lang.LanguageRegistry;
import cn.wty5.editor.lang.LanguageSpec;
import cn.wty5.editor.lang.Languages;
import cn.wty5.editor.lang.Lexer;
import cn.wty5.editor.lang.TokenType;
import cn.wty5.editor.plugin.PluginManager;

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

    /** Which selection handle is being dragged, if any. */
    private enum Handle { NONE, START, END }
    private Handle activeHandle = Handle.NONE;
    /** After a long-press / double-tap word select, MOVE extends the end. */
    private boolean fingerSelecting;
    /** Pixel slop from {@link ViewConfiguration} — pan vs tap. */
    private int touchSlop;
    private float downX, downY;
    /** True once onScroll has classified this gesture as a pan. */
    private boolean panning;
    /** Set by onDoubleTap / onLongPress so onSingleTapConfirmed is ignored. */
    private boolean doubleTapHandled;

    // -- composing region for IME (simplified) ---------------------------
    private int composingStart = -1;
    private int composingEnd = -1;

    // -- metrics ---------------------------------------------------------
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path handlePath = new Path();
    private final Rect visibleFrame = new Rect();
    private float density;
    private float textSizeSp = DEFAULT_TEXT_SIZE_SP;
    private float charWidth;
    private float lineHeight;
    private float baselineShift;
    private float gutterWidth;
    private float gutterPad;
    private float handleRadius;

    // -- scrolling / zoom ------------------------------------------------
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    /** True while a pinch is actively changing the scale factor. */
    private boolean scaling;
    /**
     * True from the moment a 2nd pointer lands (or a scale begins) until
     * every finger has lifted. Blocks caret moves / taps / scrolls that
     * would otherwise fire from the leftover single-finger UP that ends a
     * pinch — GestureDetector still remembers the original DOWN and would
     * synthesise an {@code onSingleTapUp}.
     */
    private boolean suppressSingleFingerGestures;
    /** Anchor document position kept under the fingers during a pinch. */
    private float zoomFocusDocX;
    private float zoomFocusDocY;

    /**
     * Cached visible band of this view that is NOT covered by the soft
     * keyboard (or other system windows), in view-local coordinates:
     * {@code [imeVisibleTop, imeVisibleBottom)}. Updated from
     * {@link #refreshImeVisibleBand()} on layout / global-layout.
     */
    private int imeVisibleTop;
    private int imeVisibleBottom;
    private final int[] locationInWindow = new int[2];
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener =
            this::onPossibleImeLayoutChange;

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
        setClickable(true);
        setLongClickable(true);
        // Hardware layer: text + solid fills compose faster; pinch-zoom
        // just invalidates content, no layer thrash on every frame.
        setWillNotDraw(false);

        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setSubpixelText(true);
        // SOLID style for fills — avoids accidental stroke state leaks.
        fillPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL);
        handleRadius = 10f * density;

        applyTextSize(textSizeSp, false);

        scroller = new OverScroller(context);
        GestureListener gestures = new GestureListener();
        gestureDetector = new GestureDetector(context, gestures);
        gestureDetector.setOnDoubleTapListener(gestures);
        gestureDetector.setIsLongpressEnabled(true);
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
        activeHandle = Handle.NONE;
        fingerSelecting = false;
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

    /**
     * Selects the word (identifier run) under {@code offset}. Falls back to a
     * single character when the touch is on whitespace/punctuation. Always
     * leaves {@link #hasSelection()} true so the handles appear.
     */
    private void selectWordAt(int offset) {
        if (document == null || document.length() == 0) {
            selectionAnchor = -1;
            caret = 0;
            invalidate();
            return;
        }
        offset = Math.max(0, Math.min(offset, document.length()));
        int probe = offset < document.length() ? offset
                : Math.max(0, offset - 1);
        char pc = document.charAt(probe);
        int s;
        int e;
        if (isWordChar(pc)) {
            s = probe;
            e = probe + 1;
            while (s > 0 && isWordChar(document.charAt(s - 1))) s--;
            while (e < document.length() && isWordChar(document.charAt(e))) e++;
        } else if (Character.isWhitespace(pc)) {
            s = probe;
            e = probe + 1;
            while (s > 0 && Character.isWhitespace(document.charAt(s - 1))) s--;
            while (e < document.length()
                    && Character.isWhitespace(document.charAt(e))) e++;
            // A pure-whitespace "word" is rarely useful — take one char.
            if (e - s > 1) {
                s = probe;
                e = probe + 1;
            }
        } else {
            s = probe;
            e = probe + 1;
        }
        if (s == e) {
            // Absolute last resort so hasSelection() is true and handles show.
            e = Math.min(document.length(), s + 1);
            if (s == e && s > 0) s--;
        }
        selectionAnchor = s;
        caret = e;
        activeHandle = Handle.NONE;
        ensureCaretVisible();
        resetCaretBlink();
        // Haptic may be disabled on some devices / emulators — never fatal.
        try {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {
        }
        invalidate();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$'
                || Character.isJavaIdentifierPart(c);
    }

    private int selectionStart() {
        if (!hasSelection()) return caret;
        return Math.min(caret, selectionAnchor);
    }

    private int selectionEnd() {
        if (!hasSelection()) return caret;
        return Math.max(caret, selectionAnchor);
    }

    /**
     * Hit-tests the start / end selection handles. Only active while there
     * is a real selection — a bare caret must NOT steal taps that should
     * become double-tap / long-press word selects.
     */
    private Handle hitTestHandle(float viewX, float viewY) {
        if (!hasSelection() || document == null
                || lineHeight <= 0f || charWidth <= 0f) {
            return Handle.NONE;
        }
        float hitR = handleRadius * 2.4f;
        float hitR2 = hitR * hitR;
        float[] start = handleViewPos(selectionStart(), true);
        float[] end = handleViewPos(selectionEnd(), false);
        float ds = dist2(viewX, viewY, start[0], start[1]);
        float de = dist2(viewX, viewY, end[0], end[1]);
        if (ds <= hitR2 || de <= hitR2) {
            return ds <= de ? Handle.START : Handle.END;
        }
        return Handle.NONE;
    }

    /** View-local (x, y) of the handle circle centre for a document offset. */
    private float[] handleViewPos(int offset, boolean startHandle) {
        int line = document.lineOfOffset(offset);
        int col = offset - document.lineStart(line);
        float x = gutterWidth + col * charWidth - getScrollX();
        float y = startHandle
                ? line * lineHeight - getScrollY() - handleRadius * 0.55f
                : (line + 1) * lineHeight - getScrollY() + handleRadius * 0.55f;
        return new float[]{x, y};
    }

    private static float dist2(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * Moves the dragged end of the selection to the document offset under
     * {@code (viewX, viewY)}. Keeps anchor as the fixed end; swaps roles
     * transparently when the finger crosses.
     */
    private void dragSelectionTo(float viewX, float viewY) {
        if (document == null) return;
        int offset = offsetForPoint(viewX, viewY);
        offset = Math.max(0, Math.min(offset, document.length()));

        if (activeHandle == Handle.START) {
            int end = Math.max(caret, selectionAnchor);
            if (offset >= end) {
                // Crossed the end — pin start at end-1 and flip to END drag.
                selectionAnchor = Math.max(0, end - 1);
                caret = offset;
                activeHandle = Handle.END;
            } else {
                selectionAnchor = offset;
                caret = end;
            }
        } else { // END or fingerSelecting without an explicit handle
            int start = selectionAnchor >= 0
                    ? Math.min(caret, selectionAnchor)
                    : caret;
            if (selectionAnchor < 0) {
                selectionAnchor = caret;
                start = caret;
            }
            if (offset <= start) {
                selectionAnchor = offset;
                caret = start;
                activeHandle = Handle.START;
            } else {
                selectionAnchor = start;
                caret = offset;
                activeHandle = Handle.END;
            }
        }
        caret = Math.max(0, Math.min(caret, document.length()));
        selectionAnchor = Math.max(0, Math.min(selectionAnchor, document.length()));
        ensureCaretVisible();
        resetCaretBlink();
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

            // Caret (hidden while a range is selected — handles show the ends).
            if (line == caretLine && caretVisible && isFocused() && selStart < 0) {
                float cx = gw + (caret - lineStart) * cw;
                fillPaint.setColor(scheme.caret);
                // 2-device-px wide caret, pixel-aligned.
                float caretW = Math.max(2f, density);
                canvas.drawRect(cx, top, cx + caretW, top + lh, fillPaint);
            }
        }
        canvas.restoreToCount(save);

        drawGutter(canvas, scrollX, scrollY, viewH, firstLine, lastLine, caretLine);

        // Selection / caret handles are drawn in view space on top of everything
        // (including the gutter) so they stay tappable.
        if (isFocused() && !scaling) {
            drawSelectionHandles(canvas);
        }
    }

    /** Teardrop handles at the selection ends — only while a range is selected. */
    private void drawSelectionHandles(Canvas canvas) {
        if (!hasSelection()) return;
        handlePaint.setColor(scheme.selectionHandle);
        drawHandleAt(canvas, selectionStart(), /*start*/ true);
        drawHandleAt(canvas, selectionEnd(), /*start*/ false);
    }

    private void drawHandleAt(Canvas canvas, int offset, boolean startHandle) {
        if (document == null || lineHeight <= 0f) return;
        int line = document.lineOfOffset(offset);
        int col = offset - document.lineStart(line);
        float x = gutterWidth + col * charWidth - getScrollX();
        float lineTop = line * lineHeight - getScrollY();
        float lineBottom = lineTop + lineHeight;
        float r = handleRadius;

        handlePath.reset();
        if (startHandle) {
            // Circle centred above the line, with a tip pointing down to the edge.
            float cy = lineTop - r * 0.55f;
            handlePath.addCircle(x, cy, r, Path.Direction.CW);
            handlePath.moveTo(x - r * 0.55f, cy + r * 0.55f);
            handlePath.lineTo(x, lineTop + density);
            handlePath.lineTo(x + r * 0.55f, cy + r * 0.55f);
            handlePath.close();
        } else {
            float cy = lineBottom + r * 0.55f;
            handlePath.addCircle(x, cy, r, Path.Direction.CW);
            handlePath.moveTo(x - r * 0.55f, cy - r * 0.55f);
            handlePath.lineTo(x, lineBottom - density);
            handlePath.lineTo(x + r * 0.55f, cy - r * 0.55f);
            handlePath.close();
        }
        canvas.drawPath(handlePath, handlePaint);
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
        // Pinch first; it needs the raw stream.
        scaleDetector.onTouchEvent(event);

        final int action = event.getActionMasked();
        final int pointerCount = event.getPointerCount();
        final float x = event.getX();
        final float y = event.getY();

        // Multi-touch: cancel any single-finger selection/tap work.
        if (action == MotionEvent.ACTION_POINTER_DOWN && pointerCount >= 2) {
            beginMultiTouchSession(event);
            activeHandle = Handle.NONE;
            fingerSelecting = false;
            panning = false;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
        if (suppressSingleFingerGestures || scaling
                || pointerCount > 1 || scaleDetector.isInProgress()) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                scaling = false;
                suppressSingleFingerGestures = false;
                dispatchGestureCancel(event);
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                panning = false;
                doubleTapHandled = false;
                scroller.forceFinished(true);
                // Keep parent scroll containers from stealing our long-press.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }

                Handle hit = hitTestHandle(x, y);
                if (hit != Handle.NONE) {
                    activeHandle = hit;
                    fingerSelecting = true;
                    dismissCompletions();
                    // Do not feed this DOWN to GestureDetector — it is a handle grab.
                    return true;
                }
                activeHandle = Handle.NONE;
                fingerSelecting = false;
                gestureDetector.onTouchEvent(event);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeHandle != Handle.NONE || fingerSelecting) {
                    // Extend / drag selection; block parent intercept.
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    dragSelectionTo(x, y);
                    return true;
                }
                float dx = x - downX;
                float dy = y - downY;
                // Once clearly panning, allow parent to take over if it wants
                // horizontal gestures outside the editor — but we still scroll.
                if (!panning && dx * dx + dy * dy > touchSlop * touchSlop) {
                    panning = true;
                }
                gestureDetector.onTouchEvent(event);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasHandle = activeHandle != Handle.NONE || fingerSelecting;
                if (wasHandle) {
                    activeHandle = Handle.NONE;
                    fingerSelecting = false;
                    dispatchGestureCancel(event);
                    invalidate();
                } else {
                    gestureDetector.onTouchEvent(event);
                }
                panning = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
            default:
                gestureDetector.onTouchEvent(event);
                return true;
        }
    }

    /** Marks the current touch sequence as multi-touch and cancels taps. */
    private void beginMultiTouchSession(MotionEvent event) {
        suppressSingleFingerGestures = true;
        scaling = true;
        scroller.forceFinished(true);
        dismissCompletions();
        dispatchGestureCancel(event);
    }

    /** Feeds ACTION_CANCEL into GestureDetector without mutating {@code event}. */
    private void dispatchGestureCancel(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestureDetector.onTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
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
        if (lineHeight <= 0f || document == null) return 0;
        // Allow scrolling far enough that the last line can sit above the IME,
        // not merely within the full (possibly keyboard-covered) view height.
        int band = imeVisibleBandHeight();
        return (int) Math.max(0, document.lineCount() * lineHeight - band / 2f);
    }

    private int maxScrollX() {
        return (int) Math.max(0, 200 * charWidth);
    }

    /**
     * Height of the portion of this view currently free of the soft keyboard.
     * Falls back to {@link #getHeight()} before the first layout pass.
     */
    private int imeVisibleBandHeight() {
        int h = getHeight();
        if (h <= 0) return 0;
        if (imeVisibleBottom > imeVisibleTop) {
            return Math.max(1, Math.min(h, imeVisibleBottom - imeVisibleTop));
        }
        return h;
    }

    /**
     * Recomputes {@link #imeVisibleTop}/{@link #imeVisibleBottom} from the
     * window's visible display frame (the rectangle above the IME).
     *
     * <p>Works for both host modes:
     * <ul>
     *   <li>{@code adjustResize} — our height already shrank; band ≈ full height</li>
     *   <li>{@code adjustPan} / overlay — height unchanged; band is clipped
     *       to {@code visibleFrame.bottom} so caret scroll accounts for the IME</li>
     * </ul>
     *
     * @return true if the band changed
     */
    private boolean refreshImeVisibleBand() {
        int h = getHeight();
        int w = getWidth();
        if (h <= 0 || w <= 0 || !isAttachedToWindow()) {
            int oldBottom = imeVisibleBottom;
            imeVisibleTop = 0;
            imeVisibleBottom = Math.max(0, h);
            return oldBottom != imeVisibleBottom;
        }

        getWindowVisibleDisplayFrame(visibleFrame);
        getLocationInWindow(locationInWindow);
        int viewTop = locationInWindow[1];
        int viewBottom = viewTop + h;

        // Intersect view bounds with the window region not covered by the IME.
        int uncoveredTop = Math.max(viewTop, visibleFrame.top);
        int uncoveredBottom = Math.min(viewBottom, visibleFrame.bottom);

        int top = Math.max(0, uncoveredTop - viewTop);
        int bottom = Math.max(top, Math.min(h, uncoveredBottom - viewTop));

        // If the IME reports a degenerate frame (some OEMs while animating),
        // keep the previous band rather than collapsing to zero.
        if (bottom - top < lineHeight && h > lineHeight) {
            // Visible frame might still be full-screen during IME animation;
            // only trust a shrink when it's clearly smaller than the view.
            if (visibleFrame.height() >= h - 1) {
                top = 0;
                bottom = h;
            }
        }

        if (top == imeVisibleTop && bottom == imeVisibleBottom) {
            return false;
        }
        imeVisibleTop = top;
        imeVisibleBottom = bottom;
        return true;
    }

    /**
     * Scrolls so the caret lies inside the IME-uncovered band of this view,
     * with a small margin so it is not flush against the keyboard edge.
     */
    private void ensureCaretVisible() {
        if (lineHeight <= 0f || charWidth <= 0f || getWidth() == 0 || document == null) {
            return;
        }
        refreshImeVisibleBand();

        int line = document.lineOfOffset(caret);
        int col = caret - document.lineStart(line);
        float caretDocTop = line * lineHeight;
        float caretDocBottom = caretDocTop + lineHeight;
        float x = gutterWidth + col * charWidth;

        int sx = getScrollX();
        int sy = getScrollY();

        // Margin inside the visible band (≈ half a line, at least 4dp) so the
        // caret does not sit directly on the IME boundary.
        float margin = Math.max(4f * density, lineHeight * 0.5f);
        float bandTop = imeVisibleTop + margin;
        float bandBottom = imeVisibleBottom - margin;
        if (bandBottom <= bandTop) {
            // Tiny visible strip — just centre the caret in whatever remains.
            bandTop = imeVisibleTop;
            bandBottom = Math.max(imeVisibleTop + 1, imeVisibleBottom);
        }

        // Caret edges in view-local coordinates.
        float caretViewTop = caretDocTop - sy;
        float caretViewBottom = caretDocBottom - sy;

        if (caretViewTop < bandTop) {
            sy = Math.round(caretDocTop - bandTop);
        } else if (caretViewBottom > bandBottom) {
            sy = Math.round(caretDocBottom - bandBottom);
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

    /**
     * Called from the global-layout listener when the window's visible frame
     * may have changed (IME show / hide / resize). Scrolls the caret out from
     * under a newly shown keyboard even if our own height did not change
     * (adjustPan / edge-to-edge hosts).
     */
    private void onPossibleImeLayoutChange() {
        if (!isFocused() || document == null) {
            refreshImeVisibleBand();
            return;
        }
        boolean bandChanged = refreshImeVisibleBand();
        if (bandChanged) {
            ensureCaretVisible();
            if (completionPopup != null && completionPopup.isShowing()) {
                requestCompletionsAtCaret();
            }
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction,
                                  android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        removeCallbacks(caretBlink);
        if (gained) {
            resetCaretBlink();
            // Keyboard often appears right after focus — defer one frame so the
            // visible display frame has settled, then reveal the caret.
            post(this::ensureCaretVisible);
        } else {
            dismissCompletions();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // adjustResize: our height shrinks when the IME opens. Recompute the
        // visible band and bring the caret back above the keyboard.
        refreshImeVisibleBand();
        int sx = Math.min(getScrollX(), maxScrollX());
        int sy = Math.min(getScrollY(), maxScrollY());
        if (sx != getScrollX() || sy != getScrollY()) {
            scrollTo(sx, sy);
        }
        if (isFocused()) {
            ensureCaretVisible();
        }
        if (completionPopup != null && completionPopup.isShowing()) {
            // Re-query with the new visible frame so the popup climbs above
            // a newly shown keyboard instead of sitting on top of it.
            requestCompletionsAtCaret();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(imeLayoutListener);
        refreshImeVisibleBand();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(imeLayoutListener);
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
            // May fire slightly after POINTER_DOWN; make sure the session flag
            // is set even if the platform coalesced the pointer event.
            suppressSingleFingerGestures = true;
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
            // Keep suppressSingleFingerGestures set — the final finger is still
            // down and its eventual UP must not become a caret-moving tap.
            scaling = false;
            // Snap scroll to legal range once more after the last factor.
            int nx = Math.max(0, Math.min(getScrollX(), maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY(), maxScrollY()));
            scrollTo(nx, ny);
            invalidate();
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener
            implements GestureDetector.OnDoubleTapListener {

        @Override
        public boolean onDown(MotionEvent e) {
            scroller.forceFinished(true);
            return true; // must be true for long-press / double-tap / scroll
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (suppressSingleFingerGestures || scaling) return;
            if (activeHandle != Handle.NONE) return;
            requestFocus();
            selectWordAt(offsetForPoint(e.getX(), e.getY()));
            dismissCompletions();
            // Keep the finger's remaining MOVE stream as selection-extend.
            fingerSelecting = true;
            activeHandle = Handle.END;
            doubleTapHandled = true;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (suppressSingleFingerGestures || scaling) return true;
            if (doubleTapHandled || fingerSelecting) return true;
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
        public boolean onSingleTapUp(MotionEvent e) {
            // Wait for onSingleTapConfirmed so the first half of a double-tap
            // does not move the caret.
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (suppressSingleFingerGestures || scaling) return true;
            requestFocus();
            selectWordAt(offsetForPoint(e.getX(), e.getY()));
            dismissCompletions();
            fingerSelecting = true;
            activeHandle = Handle.END;
            doubleTapHandled = true;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (fingerSelecting && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                dragSelectionTo(e.getX(), e.getY());
                return true;
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                float dx, float dy) {
            if (suppressSingleFingerGestures || scaling) return false;
            // Selection drag is handled in onTouchEvent when fingerSelecting.
            if (activeHandle != Handle.NONE || fingerSelecting) {
                dragSelectionTo(e2.getX(), e2.getY());
                return true;
            }
            panning = true;
            int nx = Math.max(0, Math.min(getScrollX() + Math.round(dx), maxScrollX()));
            int ny = Math.max(0, Math.min(getScrollY() + Math.round(dy), maxScrollY()));
            scrollTo(nx, ny);
            if (completionPopup != null && completionPopup.isShowing()) {
                dismissCompletions();
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2,
                               float vx, float vy) {
            if (suppressSingleFingerGestures || scaling) return false;
            if (activeHandle != Handle.NONE || fingerSelecting) return false;
            scroller.fling(getScrollX(), getScrollY(),
                    Math.round(-vx), Math.round(-vy),
                    0, maxScrollX(), 0, maxScrollY());
            postInvalidateOnAnimation();
            return true;
        }
    }
}

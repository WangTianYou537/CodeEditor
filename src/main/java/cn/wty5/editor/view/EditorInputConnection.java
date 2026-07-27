package cn.wty5.editor.view;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/**
 * IME bridge that mirrors the document into a real {@link Editable}, matching
 * how {@code TextView}/{@code EditableInputConnection} work.
 *
 * <p>English predictive IMEs (Gboard, Samsung, etc.) depend on:
 * <ul>
 *   <li>a genuine {@link Editable} returned from {@link #getEditable()}</li>
 *   <li>{@link Spanned#SPAN_COMPOSING} managed by {@link BaseInputConnection}</li>
 *   <li>{@link #getSurroundingText} reading that same buffer</li>
 *   <li>{@code updateSelection} after every batch of edits</li>
 * </ul>
 *
 * Mutations flow Editable → piece table via a {@link TextWatcher}; external
 * document edits flow back through {@link #syncFromDocument()}.
 */
class EditorInputConnection extends BaseInputConnection {

    protected final CodeEditorView editor;
    private final SpannableStringBuilder editable;
    private final TextWatcher watcher;

    private int batchDepth;
    /** True while we push document → editable (suppress reverse sync). */
    private boolean syncingFromDocument;
    /** True while we push editable → document (suppress reverse sync). */
    private boolean syncingToDocument;

    EditorInputConnection(CodeEditorView editor) {
        // fullEditor=true: BaseInputConnection keeps SPAN_COMPOSING on our
        // Editable and does NOT enter key-event fallback mode.
        super(editor, true);
        this.editor = editor;
        this.editable = new SpannableStringBuilder();
        this.watcher = new TextWatcher() {
            private int changeStart;
            private int charsBefore;
            private int charsAfter;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                changeStart = start;
                charsBefore = count;
                charsAfter = after;
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                changeStart = start;
                charsBefore = before;
                charsAfter = count;
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (syncingFromDocument || syncingToDocument) return;
                // Defer document write until the outermost batch ends so a
                // multi-step setComposingText lands as one undo entry.
                if (batchDepth == 0) {
                    flushEditableToDocument(changeStart, charsBefore, charsAfter);
                    editor.onImeBatchFinished();
                }
            }
        };
        editable.setSpan(watcher, 0, 0,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE | (100 << Spanned.SPAN_PRIORITY_SHIFT));
        syncFromDocument();
    }

    @Override
    public Editable getEditable() {
        return editable;
    }

    // ------------------------------------------------------------------
    // Batching — nest so nested begin/end from BaseIC still work
    // ------------------------------------------------------------------

    @Override
    public boolean beginBatchEdit() {
        if (syncingFromDocument || syncingToDocument) {
            // Span-only adjustments during document→editable sync must not
            // open an undo batch on the editor.
            batchDepth++;
            return true;
        }
        if (batchDepth++ == 0) {
            editor.beginImeBatch();
        }
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        if (batchDepth <= 0) return false;
        batchDepth--;
        if (syncingFromDocument || syncingToDocument) {
            return batchDepth > 0;
        }
        if (batchDepth == 0) {
            // Super may have mutated the Editable during the batch without
            // going through afterTextChanged for every step; always flush the
            // full mirror once at the end for correctness.
            flushFullEditableToDocument();
            editor.endImeBatch();
            editor.onImeBatchFinished();
        }
        return batchDepth > 0;
    }

    // ------------------------------------------------------------------
    // Reads that need the real document snapshot for ExtractedText tokens
    // ------------------------------------------------------------------

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        editor.onExtractedTextRequested(request, flags);
        ExtractedText et = new ExtractedText();
        final int MAX = 32 * 1024;
        int len = editable.length();
        int start = 0;
        int end = len;
        int selStart = Selection.getSelectionStart(editable);
        int selEnd = Selection.getSelectionEnd(editable);
        if (selStart < 0) selStart = 0;
        if (selEnd < 0) selEnd = selStart;
        if (selStart > selEnd) {
            int t = selStart; selStart = selEnd; selEnd = t;
        }
        if (len > MAX) {
            int mid = (selStart + selEnd) >>> 1;
            start = Math.max(0, mid - MAX / 2);
            end = Math.min(len, start + MAX);
            start = Math.max(0, end - MAX);
        }
        et.text = editable.subSequence(start, end);
        et.startOffset = start;
        et.partialStartOffset = -1;
        et.partialEndOffset = -1;
        et.selectionStart = Math.max(0, selStart - start);
        et.selectionEnd = Math.max(0, selEnd - start);
        et.flags = 0;
        return et;
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        return editor.requestCursorUpdatesFromIme(cursorUpdateMode);
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    public boolean setSelection(int start, int end) {
        boolean ok = super.setSelection(start, end);
        // Selection spans don't fire TextWatcher — push to the editor now.
        if (ok && !syncingFromDocument && !syncingToDocument) {
            pullSelectionFromEditable();
            editor.onImeBatchFinished();
        }
        return ok;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        boolean ok = super.setComposingRegion(start, end);
        // Span-only; endBatchEdit inside super already nested through us, but
        // if batch depth was already >0 the outer end will flush. When we are
        // the outer batch (depth returned to 0), endBatchEdit flushed. When
        // syncingFromDocument we suppressed writes. Pull composing now to be
        // safe for the no-outer-batch case.
        if (ok && !syncingFromDocument && !syncingToDocument && batchDepth == 0) {
            pullSelectionFromEditable();
            editor.onImeBatchFinished();
        }
        return ok;
    }

    @Override
    public boolean finishComposingText() {
        boolean ok = super.finishComposingText();
        if (ok && !syncingFromDocument && !syncingToDocument && batchDepth == 0) {
            pullSelectionFromEditable();
            editor.onImeBatchFinished();
        }
        return ok;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        // Route through the view so hardware-style DEL/Enter from soft
        // keyboards hit the same path as physical keys. Before that, make
        // sure any pending Editable mutations are visible on the document.
        if (batchDepth == 0) {
            flushFullEditableToDocument();
            pullSelectionFromEditable();
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return editor.onKeyDown(event.getKeyCode(), event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return editor.onKeyUp(event.getKeyCode(), event);
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        flushFullEditableToDocument();
        pullSelectionFromEditable();
        editor.insertNewlineWithIndent();
        syncFromDocument();
        return true;
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        return true;
    }

    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        return true;
    }

    @Override
    public void closeConnection() {
        super.closeConnection();
        while (batchDepth > 0) {
            endBatchEdit();
        }
    }

    // ------------------------------------------------------------------
    // Sync helpers
    // ------------------------------------------------------------------

    /**
     * Rebuild the Editable from the piece table and re-apply selection /
     * composing spans. Called after external (non-IME) document mutations.
     */
    void syncFromDocument() {
        if (syncingToDocument) return;
        syncingFromDocument = true;
        try {
            String text = editor.getText();
            if (text == null) text = "";
            boolean sameText = text.contentEquals(editable);
            if (!sameText) {
                editable.replace(0, editable.length(), text);
            }
            // Re-anchor the TextWatcher span over the full buffer (replace can
            // drop zero-length spans at the edges).
            editable.removeSpan(watcher);
            editable.setSpan(watcher, 0, editable.length(),
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE
                            | (100 << Spanned.SPAN_PRIORITY_SHIFT));
            applyEditorSelectionAndComposing();
        } finally {
            syncingFromDocument = false;
        }
    }

    /**
     * Lightweight sync used after caret/selection moves that do not change
     * document text (tap, arrow keys, word select). Without this, the IME
     * Editable keeps the old Selection and the next commit/delete lands at
     * the previous caret even though the drawn caret has moved.
     */
    void syncSelectionFromEditor() {
        if (syncingToDocument || syncingFromDocument) return;
        if (editable.length() != editor.getDocumentLength()) {
            // Length drift — fall back to a full rebuild.
            syncFromDocument();
            return;
        }
        syncingFromDocument = true;
        try {
            applyEditorSelectionAndComposing();
        } finally {
            syncingFromDocument = false;
        }
    }

    private void applyEditorSelectionAndComposing() {
        int selStart = editor.imeSelectionStart();
        int selEnd = editor.imeSelectionEnd();
        selStart = clamp(selStart, 0, editable.length());
        selEnd = clamp(selEnd, 0, editable.length());
        Selection.setSelection(editable, selStart, selEnd);

        int compStart = editor.imeComposingStart();
        int compEnd = editor.imeComposingEnd();
        BaseInputConnection.removeComposingSpans(editable);
        if (compStart >= 0 && compEnd > compStart
                && compEnd <= editable.length()) {
            // setComposingRegion applies the platform COMPOSING span.
            super.setComposingRegion(compStart, compEnd);
        }
    }

    private void flushEditableToDocument(int start, int before, int after) {
        if (syncingFromDocument || syncingToDocument) return;
        syncingToDocument = true;
        try {
            int s = clamp(start, 0, editor.getDocumentLength());
            int oldEnd = clamp(s + Math.max(0, before), s, editor.getDocumentLength());
            CharSequence inserted = editable.subSequence(
                    clamp(start, 0, editable.length()),
                    clamp(start + Math.max(0, after), 0, editable.length()));
            editor.replaceRangeFromEditable(s, oldEnd, inserted.toString());
            pullSelectionFromEditable();
        } finally {
            syncingToDocument = false;
        }
    }

    private void flushFullEditableToDocument() {
        if (syncingFromDocument || syncingToDocument) return;
        String doc = editor.getText();
        if (doc == null) doc = "";
        if (doc.contentEquals(editable)) {
            pullSelectionFromEditable();
            return;
        }
        // Diff ends for a minimal replace when possible.
        int docLen = doc.length();
        int edLen = editable.length();
        int prefix = 0;
        int maxPrefix = Math.min(docLen, edLen);
        while (prefix < maxPrefix && doc.charAt(prefix) == editable.charAt(prefix)) {
            prefix++;
        }
        int docSuffix = docLen;
        int edSuffix = edLen;
        while (docSuffix > prefix && edSuffix > prefix
                && doc.charAt(docSuffix - 1) == editable.charAt(edSuffix - 1)) {
            docSuffix--;
            edSuffix--;
        }
        syncingToDocument = true;
        try {
            String inserted = editable.subSequence(prefix, edSuffix).toString();
            editor.replaceRangeFromEditable(prefix, docSuffix, inserted);
            pullSelectionFromEditable();
        } finally {
            syncingToDocument = false;
        }
    }

    private void pullSelectionFromEditable() {
        int selStart = Selection.getSelectionStart(editable);
        int selEnd = Selection.getSelectionEnd(editable);
        if (selStart < 0) selStart = 0;
        if (selEnd < 0) selEnd = selStart;
        int compStart = BaseInputConnection.getComposingSpanStart(editable);
        int compEnd = BaseInputConnection.getComposingSpanEnd(editable);
        if (compStart > compEnd) {
            int t = compStart; compStart = compEnd; compEnd = t;
        }
        editor.applyImeStateFromEditable(selStart, selEnd, compStart, compEnd);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}

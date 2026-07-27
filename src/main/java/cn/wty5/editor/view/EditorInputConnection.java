package cn.wty5.editor.view;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/**
 * Bridges the soft keyboard (IME) to the editor.
 *
 * <p>Uses {@link BaseInputConnection} in <em>dummy</em> mode (no internal
 * Editable) and routes every content mutation through
 * {@link CodeEditorView}'s piece-table edit methods. The connection is
 * deliberately complete enough for English suggestion / gesture typing and
 * CJK composition:
 * <ul>
 *   <li>{@link #setComposingText} / {@link #setComposingRegion} /
 *       {@link #finishComposingText}</li>
 *   <li>{@link #getTextBeforeCursor} / {@link #getTextAfterCursor} /
 *       {@link #getSelectedText} / {@link #getExtractedText} /
 *       {@link #getCursorCapsMode}</li>
 *   <li>{@link #setSelection} so the IME can move the caret after a pick</li>
 *   <li>{@link #deleteSurroundingText} (+ code-point variant)</li>
 *   <li>batch-edit bracketing so multi-step commits stay as one undo step</li>
 * </ul>
 */
class EditorInputConnection extends BaseInputConnection {

    private final CodeEditorView editor;
    private int batchDepth;

    EditorInputConnection(CodeEditorView editor) {
        // fullEditor=false: we own the buffer; BaseInputConnection must not
        // invent an Editable that drifts out of sync with the piece table.
        super(editor, false);
        this.editor = editor;
    }

    // ------------------------------------------------------------------
    // Text reads (IME suggestion engines lean on these heavily)
    // ------------------------------------------------------------------

    @Override
    public CharSequence getTextBeforeCursor(int length, int flags) {
        return editor.textBeforeCursor(length);
    }

    @Override
    public CharSequence getTextAfterCursor(int length, int flags) {
        return editor.textAfterCursor(length);
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        return editor.selectedTextForIme();
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        return editor.cursorCapsMode(reqModes);
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        editor.onExtractedTextRequested(request, flags);
        return editor.extractedTextForIme(request);
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    @Override
    public boolean beginBatchEdit() {
        batchDepth++;
        if (batchDepth == 1) {
            editor.beginImeBatch();
        }
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        if (batchDepth > 0) {
            batchDepth--;
            if (batchDepth == 0) {
                editor.endImeBatch();
            }
        }
        return true;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        editor.commitTextFromIme(text == null ? "" : text.toString(),
                newCursorPosition);
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        editor.replaceComposingFromIme(text == null ? "" : text.toString(),
                newCursorPosition);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        editor.setComposingRegionFromIme(start, end);
        return true;
    }

    @Override
    public boolean finishComposingText() {
        editor.finishComposingFromIme();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        editor.deleteSurroundingFromIme(beforeLength, afterLength);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength,
                                                     int afterLength) {
        // Document is UTF-16; convert code-point counts to UTF-16 units.
        editor.deleteSurroundingCodePointsFromIme(beforeLength, afterLength);
        return true;
    }

    @Override
    public boolean setSelection(int start, int end) {
        editor.setSelectionFromIme(start, end);
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        // Some English IMEs send DEL as a key event while a word is still a
        // composing span. Routing that through View.onKeyDown would clear the
        // span and make the candidate strip disappear ("girl" → DEL → no
        // suggestions). Keep IME deletions on the composition-aware path.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DEL:
                    editor.deleteBackwardFromIme();
                    return true;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    editor.deleteForwardFromIme();
                    return true;
                default:
                    return editor.onKeyDown(event.getKeyCode(), event);
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return editor.onKeyUp(event.getKeyCode(), event);
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        // IME action key (some keyboards fire this instead of commitText("\n")).
        editor.insertNewlineWithIndent();
        return true;
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        return true;
    }

    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        // We always request no-fullscreen; acknowledge without changing layout.
        return true;
    }
}

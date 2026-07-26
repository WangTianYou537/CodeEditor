package com.editor.view;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

/**
 * Bridges the soft keyboard (IME) to the editor.
 *
 * Extends {@link BaseInputConnection} in "dummy" mode (editable=false) and
 * routes committed text / deletions straight into the editor's edit methods.
 * Composing-region handling is intentionally simplified: composing text is
 * committed immediately, which keeps the piece table authoritative and works
 * with plain latin keyboards; a production IME integration would track the
 * composing span.
 */
final class EditorInputConnection extends BaseInputConnection {

    private final CodeEditorView editor;

    EditorInputConnection(CodeEditorView editor) {
        super(editor, true);
        this.editor = editor;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        editor.commitTextFromIme(text.toString());
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        // Simplified: treat composing text as committed (see class doc).
        editor.replaceComposingFromIme(text.toString());
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
    public boolean sendKeyEvent(KeyEvent event) {
        // Hardware-style events (incl. soft keyboard DEL on many IMEs).
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return editor.onKeyDown(event.getKeyCode(), event);
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        editor.commitTextFromIme("\n");
        return true;
    }

    @Override
    public CharSequence getTextBeforeCursor(int length, int flags) {
        return editor.textBeforeCursor(length);
    }

    @Override
    public CharSequence getTextAfterCursor(int length, int flags) {
        return editor.textAfterCursor(length);
    }
}

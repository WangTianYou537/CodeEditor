package cn.wty5.editor.view;

import android.view.inputmethod.TextAttribute;

/**
 * API 34+ InputConnection extensions. Kept separate so Android 7–13 never
 * resolve {@link TextAttribute} while loading the base connection class.
 *
 * <p>With the Editable-backed connection, {@code replaceText} from
 * {@link android.view.inputmethod.BaseInputConnection} already mutates our
 * real buffer; we only need to make sure the piece table is flushed after.
 */
final class EditorInputConnectionApi34 extends EditorInputConnection {

    EditorInputConnectionApi34(CodeEditorView editor) {
        super(editor);
    }

    @Override
    public boolean replaceText(int start, int end, CharSequence text,
                               int newCursorPosition, TextAttribute textAttribute) {
        // BaseInputConnection.replaceText mutates getEditable() correctly when
        // fullEditor=true; then our TextWatcher / endBatchEdit flushes it.
        boolean ok = super.replaceText(start, end, text, newCursorPosition, textAttribute);
        // Ensure selection is published even if no batch was open.
        editor.onImeBatchFinished();
        return ok;
    }
}

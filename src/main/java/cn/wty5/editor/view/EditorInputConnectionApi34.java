package cn.wty5.editor.view;

import android.view.inputmethod.TextAttribute;

/**
 * API 34+ InputConnection extensions kept in a separate class so Android
 * 7–13 never have to resolve {@link TextAttribute} while loading the base
 * connection class.
 */
final class EditorInputConnectionApi34 extends EditorInputConnection {

    EditorInputConnectionApi34(CodeEditorView editor) {
        super(editor);
        this.editor = editor;
    }

    private final CodeEditorView editor;

    /**
     * Android 14+ predictive IMEs may use replaceText for correction/backspace.
     * BaseInputConnection would mutate its private dummy Editable instead of
     * the editor's piece table, so route it explicitly.
     */
    @Override
    public boolean replaceText(int start, int end, CharSequence text,
                               int newCursorPosition, TextAttribute textAttribute) {
        editor.replaceRangeFromIme(start, end,
                text == null ? "" : text.toString(), newCursorPosition);
        return true;
    }
}

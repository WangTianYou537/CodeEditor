package com.editor.complete;

/** One completion suggestion. */
public final class CompletionItem {

    public enum Kind { KEYWORD, TYPE, IDENTIFIER, SNIPPET }

    public final Kind kind;
    /** Text shown in the list. */
    public final String label;
    /** Text inserted; may contain '$0' marking the caret position. */
    public final String insertText;
    /** Secondary text (e.g. "keyword", "in file"). */
    public final String detail;

    public CompletionItem(Kind kind, String label, String insertText, String detail) {
        this.kind = kind;
        this.label = label;
        this.insertText = insertText;
        this.detail = detail;
    }
}

package cn.wty5.editor.highlight;

import cn.wty5.editor.lang.TokenType;

/** Maps token types to ARGB colors. Default palette is a dark theme. */
public class ColorScheme {

    public int background = 0xFF1E1E2E;
    public int foreground = 0xFFCDD6F4;
    public int caret = 0xFFF5E0DC;
    public int selection = 0x993E68D8; // ~60% opaque — clearly visible on dark bg
    /** Android-style teardrop used for selection drag handles. */
    public int selectionHandle = 0xFF89B4FA;
    public int currentLine = 0x14FFFFFF;
    public int gutterBackground = 0xFF181825;
    public int gutterText = 0xFF6C7086;
    public int gutterCurrentText = 0xFFCDD6F4;
    public int completionBackground = 0xFF313244;
    public int completionSelected = 0xFF45475A;
    public int completionText = 0xFFCDD6F4;
    public int completionDetail = 0xFF9399B2;

    /** LSP diagnostic underline / gutter tick colours. */
    public int diagnosticError = 0xFFF38BA8;
    public int diagnosticWarning = 0xFFF9E2AF;
    public int diagnosticInfo = 0xFF89B4FA;
    public int diagnosticHint = 0xFF6C7086;

    /** IME composing-region underline (English suggestions / CJK preedit). */
    public int composingUnderline = 0xFF89B4FA;

    /** Self-drawn selection floating toolbar. */
    public int toolbarBackground = 0xFF313244;
    public int toolbarText = 0xFFCDD6F4;
    public int toolbarDivider = 0xFF45475A;

    public int colorOf(TokenType type) {
        switch (type) {
            case KEYWORD:      return 0xFFCBA6F7;
            case TYPE:         return 0xFF89B4FA;
            case NUMBER:       return 0xFFFAB387;
            case STRING:       return 0xFFA6E3A1;
            case CHAR_LITERAL: return 0xFFA6E3A1;
            case COMMENT:      return 0xFF6C7086;
            case ANNOTATION:   return 0xFFF9E2AF;
            case OPERATOR:     return 0xFF94E2D5;
            case PUNCTUATION:  return 0xFF9399B2;
            case IDENTIFIER:
            case WHITESPACE:
            case PLAIN:
            default:           return foreground;
        }
    }
}

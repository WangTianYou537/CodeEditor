package cn.wty5.editor.highlight;

import cn.wty5.editor.lang.TokenType;

/** Maps token types to ARGB colors. Default palette is a dark theme. */
public class ColorScheme {

    public int background = 0xFF1E1E2E;
    public int foreground = 0xFFCDD6F4;
    public int caret = 0xFFF5E0DC;
    public int selection = 0x403E68D8;
    /** Filled circle / teardrop used for selection drag handles. */
    public int selectionHandle = 0xFF89B4FA;
    public int currentLine = 0x14FFFFFF;
    public int gutterBackground = 0xFF181825;
    public int gutterText = 0xFF6C7086;
    public int gutterCurrentText = 0xFFCDD6F4;
    public int completionBackground = 0xFF313244;
    public int completionSelected = 0xFF45475A;
    public int completionText = 0xFFCDD6F4;
    public int completionDetail = 0xFF9399B2;

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

package cn.wty5.editor.highlight;

import cn.wty5.editor.lang.TokenType;

/**
 * Highlight spans for one line, stored as parallel primitive arrays to avoid
 * per-token object allocation (a 10k-line file easily has 100k+ tokens).
 * Columns are line-relative so spans survive edits on other lines untouched.
 */
public final class LineSpans {

    private int[] starts = new int[8];
    private int[] ends = new int[8];
    private byte[] types = new byte[8];
    private int size;

    /** Lexer state at the END of this line (feeds the next line's lex). */
    public int outState;

    public void clear() {
        size = 0;
    }

    public void add(TokenType type, int start, int end) {
        if (size == starts.length) {
            grow();
        }
        starts[size] = start;
        ends[size] = end;
        types[size] = (byte) type.ordinal();
        size++;
    }

    private void grow() {
        int cap = starts.length * 2;
        starts = java.util.Arrays.copyOf(starts, cap);
        ends = java.util.Arrays.copyOf(ends, cap);
        types = java.util.Arrays.copyOf(types, cap);
    }

    public int size() {
        return size;
    }

    public int start(int i) {
        return starts[i];
    }

    public int end(int i) {
        return ends[i];
    }

    private static final TokenType[] TOKEN_TYPES = TokenType.values();

    public TokenType type(int i) {
        return TOKEN_TYPES[types[i]];
    }
}

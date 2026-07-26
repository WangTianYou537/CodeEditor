package cn.wty5.editor.lang;

/**
 * Line-at-a-time incremental lexer.
 *
 * Multi-line constructs (block comments, text blocks, raw strings) are
 * carried across lines via an integer state returned from
 * {@link #tokenizeLine}. State 0 is always "default / not inside anything".
 */
public interface Lexer {

    int STATE_DEFAULT = 0;

    /** Receives each token of the lexed line. */
    interface TokenSink {
        /**
         * @param type  token category
         * @param start start column within the line
         * @param end   exclusive end column within the line
         */
        void token(TokenType type, int start, int end);
    }

    /**
     * Tokenizes one line.
     *
     * @param line    the line's text (no trailing '\n')
     * @param inState lexer state at the start of the line
     * @param sink    receives the tokens in order, covering the whole line
     * @return lexer state at the end of the line
     */
    int tokenizeLine(CharSequence line, int inState, TokenSink sink);
}

package cn.wty5.editor.lang;

/**
 * Backward-compatible Java lexer.
 *
 * Delegates to a {@link GrammarLexer} built from the built-in Java
 * {@link LanguageSpec}. Existing call sites and tests keep working; new
 * code should prefer {@link Languages#lexerFor(String)} or
 * {@link LanguageRegistry#createLexer(String)}.
 */
public final class JavaLexer implements Lexer {

    public static final int STATE_DEFAULT = Lexer.STATE_DEFAULT;
    public static final int STATE_IN_BLOCK_COMMENT = GrammarLexer.STATE_IN_BLOCK_COMMENT;
    public static final int STATE_IN_TEXT_BLOCK = GrammarLexer.STATE_IN_TEXT_BLOCK;

    private final GrammarLexer delegate;

    public JavaLexer() {
        this.delegate = new GrammarLexer(Languages.javaFallback());
    }

    public JavaLexer(LanguageSpec spec) {
        this.delegate = new GrammarLexer(spec);
    }

    @Override
    public int tokenizeLine(CharSequence line, int inState, TokenSink sink) {
        return delegate.tokenizeLine(line, inState, sink);
    }
}

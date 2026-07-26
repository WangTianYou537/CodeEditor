package cn.wty5.editor.lang;

/**
 * Generic line-at-a-time lexer driven by a {@link LanguageSpec}.
 *
 * Replaces the hard-coded JavaLexer: keywords/types/comment markers/string
 * forms all come from the spec, so the same engine highlights Java, Go, and
 * any future grammar file. Carry states:
 * <ul>
 *   <li>{@link #STATE_DEFAULT} — normal code</li>
 *   <li>{@link #STATE_IN_BLOCK_COMMENT} — inside block comment</li>
 *   <li>{@link #STATE_IN_TEXT_BLOCK} — inside multi-line text block</li>
 *   <li>{@link #STATE_IN_RAW_STRING} — inside raw string (e.g. Go backtick)</li>
 * </ul>
 */
public final class GrammarLexer implements Lexer {

    public static final int STATE_IN_BLOCK_COMMENT = 1;
    public static final int STATE_IN_TEXT_BLOCK = 2;
    public static final int STATE_IN_RAW_STRING = 3;

    private final LanguageSpec spec;

    public GrammarLexer(LanguageSpec spec) {
        this.spec = spec;
    }

    public LanguageSpec getSpec() {
        return spec;
    }

    @Override
    public int tokenizeLine(CharSequence line, int inState, TokenSink sink) {
        final int n = line.length();
        int i = 0;

        // Resume a multi-line construct.
        if (inState == STATE_IN_BLOCK_COMMENT) {
            String close = spec.blockCommentClose;
            if (close == null) {
                if (n > 0) sink.token(TokenType.COMMENT, 0, n);
                return STATE_DEFAULT;
            }
            int c = indexOf(line, close, 0);
            if (c < 0) {
                if (n > 0) sink.token(TokenType.COMMENT, 0, n);
                return STATE_IN_BLOCK_COMMENT;
            }
            sink.token(TokenType.COMMENT, 0, c + close.length());
            i = c + close.length();
        } else if (inState == STATE_IN_TEXT_BLOCK) {
            String close = spec.textBlockClose;
            if (close == null) {
                if (n > 0) sink.token(TokenType.STRING, 0, n);
                return STATE_DEFAULT;
            }
            int c = indexOf(line, close, 0);
            if (c < 0) {
                if (n > 0) sink.token(TokenType.STRING, 0, n);
                return STATE_IN_TEXT_BLOCK;
            }
            sink.token(TokenType.STRING, 0, c + close.length());
            i = c + close.length();
        } else if (inState == STATE_IN_RAW_STRING) {
            String delim = spec.rawStringDelimiter;
            if (delim == null) {
                if (n > 0) sink.token(TokenType.STRING, 0, n);
                return STATE_DEFAULT;
            }
            int c = indexOf(line, delim, 0);
            if (c < 0) {
                if (n > 0) sink.token(TokenType.STRING, 0, n);
                return STATE_IN_RAW_STRING;
            }
            sink.token(TokenType.STRING, 0, c + delim.length());
            i = c + delim.length();
        }

        while (i < n) {
            char c = line.charAt(i);

            // -- whitespace ------------------------------------------------
            if (c == ' ' || c == '\t') {
                int s = i;
                while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
                sink.token(TokenType.WHITESPACE, s, i);
                continue;
            }

            // -- line comment ----------------------------------------------
            if (spec.lineComment != null && startsWith(line, i, spec.lineComment)) {
                sink.token(TokenType.COMMENT, i, n);
                return STATE_DEFAULT;
            }

            // -- block comment ---------------------------------------------
            if (spec.blockCommentOpen != null
                    && startsWith(line, i, spec.blockCommentOpen)) {
                int openLen = spec.blockCommentOpen.length();
                int close = indexOf(line, spec.blockCommentClose, i + openLen);
                if (close < 0) {
                    sink.token(TokenType.COMMENT, i, n);
                    return STATE_IN_BLOCK_COMMENT;
                }
                sink.token(TokenType.COMMENT, i, close + spec.blockCommentClose.length());
                i = close + spec.blockCommentClose.length();
                continue;
            }

            // -- text block ------------------------------------------------
            if (spec.textBlockOpen != null
                    && startsWith(line, i, spec.textBlockOpen)) {
                int openLen = spec.textBlockOpen.length();
                int close = indexOf(line, spec.textBlockClose, i + openLen);
                if (close < 0) {
                    sink.token(TokenType.STRING, i, n);
                    return STATE_IN_TEXT_BLOCK;
                }
                sink.token(TokenType.STRING, i, close + spec.textBlockClose.length());
                i = close + spec.textBlockClose.length();
                continue;
            }

            // -- raw string (e.g. Go `...`) --------------------------------
            if (spec.rawStringDelimiter != null
                    && startsWith(line, i, spec.rawStringDelimiter)) {
                int dLen = spec.rawStringDelimiter.length();
                int close = indexOf(line, spec.rawStringDelimiter, i + dLen);
                if (close < 0) {
                    sink.token(TokenType.STRING, i, n);
                    return STATE_IN_RAW_STRING;
                }
                sink.token(TokenType.STRING, i, close + dLen);
                i = close + dLen;
                continue;
            }

            // -- double-quoted string --------------------------------------
            if (spec.doubleQuotedStrings && c == '"') {
                int s = i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                    } else if (d == '"') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                sink.token(TokenType.STRING, s, i);
                continue;
            }

            // -- single-quoted char literal --------------------------------
            if (spec.singleQuotedChars && c == '\'') {
                int s = i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                    } else if (d == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                sink.token(TokenType.CHAR_LITERAL, s, i);
                continue;
            }

            // -- single-quoted string --------------------------------------
            if (spec.singleQuotedStrings && c == '\'') {
                int s = i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                    } else if (d == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                sink.token(TokenType.STRING, s, i);
                continue;
            }

            // -- number ----------------------------------------------------
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int s = i;
                i = scanNumber(line, i);
                sink.token(TokenType.NUMBER, s, i);
                continue;
            }

            // -- annotation / attribute (@ident) ---------------------------
            if (c == '@' && i + 1 < n && isIdentStart(line.charAt(i + 1))) {
                int s = i++;
                while (i < n && isIdentPart(line.charAt(i))) i++;
                sink.token(TokenType.ANNOTATION, s, i);
                continue;
            }

            // -- identifier / keyword / type -------------------------------
            if (isIdentStart(c)) {
                int s = i;
                while (i < n && isIdentPart(line.charAt(i))) i++;
                String word = line.subSequence(s, i).toString();
                TokenType t = spec.keywords.contains(word) ? TokenType.KEYWORD
                        : spec.types.contains(word) ? TokenType.TYPE
                        : TokenType.IDENTIFIER;
                sink.token(t, s, i);
                continue;
            }

            // -- operators -------------------------------------------------
            if (isOperatorChar(c)) {
                int s = i;
                while (i < n && isOperatorChar(line.charAt(i))) i++;
                sink.token(TokenType.OPERATOR, s, i);
                continue;
            }

            // -- punctuation -----------------------------------------------
            if (isPunctuation(c)) {
                sink.token(TokenType.PUNCTUATION, i, i + 1);
                i++;
                continue;
            }

            // -- fallback --------------------------------------------------
            sink.token(TokenType.PLAIN, i, i + 1);
            i++;
        }
        return STATE_DEFAULT;
    }

    // ------------------------------------------------------------------

    private int scanNumber(CharSequence line, int i) {
        final int n = line.length();
        if (line.charAt(i) == '0' && i + 1 < n) {
            char c2 = line.charAt(i + 1);
            if (spec.hexNumbers && (c2 == 'x' || c2 == 'X')) {
                i += 2;
                while (i < n && (isHexDigit(line.charAt(i))
                        || (spec.underscoreInNumbers && line.charAt(i) == '_'))) {
                    i++;
                }
                return maybeSuffix(line, i);
            }
            if (spec.binNumbers && (c2 == 'b' || c2 == 'B')) {
                i += 2;
                while (i < n && (line.charAt(i) == '0' || line.charAt(i) == '1'
                        || (spec.underscoreInNumbers && line.charAt(i) == '_'))) {
                    i++;
                }
                return maybeSuffix(line, i);
            }
        }
        while (i < n && (Character.isDigit(line.charAt(i))
                || (spec.underscoreInNumbers && line.charAt(i) == '_'))) {
            i++;
        }
        if (i < n && line.charAt(i) == '.') {
            i++;
            while (i < n && (Character.isDigit(line.charAt(i))
                    || (spec.underscoreInNumbers && line.charAt(i) == '_'))) {
                i++;
            }
        }
        if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < n && (line.charAt(i) == '+' || line.charAt(i) == '-')) i++;
            if (i < n && Character.isDigit(line.charAt(i))) {
                while (i < n && Character.isDigit(line.charAt(i))) i++;
            } else {
                i = save;
            }
        }
        return maybeSuffix(line, i);
    }

    private int maybeSuffix(CharSequence line, int i) {
        if (i < line.length() && spec.numberSuffixes != null
                && spec.numberSuffixes.indexOf(line.charAt(i)) >= 0) {
            return i + 1;
        }
        return i;
    }

    private boolean isOperatorChar(char c) {
        return spec.operatorChars != null && spec.operatorChars.indexOf(c) >= 0;
    }

    private boolean isPunctuation(char c) {
        return spec.punctuationChars != null && spec.punctuationChars.indexOf(c) >= 0;
    }

    private static boolean isIdentStart(char c) {
        return Character.isJavaIdentifierStart(c);
    }

    private static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean startsWith(CharSequence s, int at, String needle) {
        if (needle == null || at + needle.length() > s.length()) return false;
        for (int j = 0; j < needle.length(); j++) {
            if (s.charAt(at + j) != needle.charAt(j)) return false;
        }
        return true;
    }

    private static int indexOf(CharSequence s, String needle, int from) {
        if (needle == null || needle.isEmpty()) return -1;
        final int n = s.length() - needle.length();
        outer:
        for (int i = Math.max(from, 0); i <= n; i++) {
            for (int j = 0; j < needle.length(); j++) {
                if (s.charAt(i + j) != needle.charAt(j)) continue outer;
            }
            return i;
        }
        return -1;
    }
}

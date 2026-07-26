package com.editor.lang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A hand-rolled Java lexer designed for line-at-a-time incremental
 * highlighting.
 *
 * The only lexical construct in Java that spans lines is the block comment
 * (and, since Java 15, the text block — treated here like a block string).
 * So each line can be tokenized independently given one bit of inbound
 * state: {@link #STATE_DEFAULT}, {@link #STATE_IN_BLOCK_COMMENT} or
 * {@link #STATE_IN_TEXT_BLOCK}. The lexer reports the outbound state so the
 * highlighter can propagate it and stop re-lexing as soon as states match
 * the cached ones.
 */
public final class JavaLexer {

    public static final int STATE_DEFAULT = 0;
    public static final int STATE_IN_BLOCK_COMMENT = 1;
    public static final int STATE_IN_TEXT_BLOCK = 2;

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "break", "case", "catch", "class", "const",
            "continue", "default", "do", "else", "enum", "extends", "final",
            "finally", "for", "goto", "if", "implements", "import",
            "instanceof", "interface", "native", "new", "package", "private",
            "protected", "public", "return", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "volatile", "while", "record", "sealed", "permits",
            "yield", "var", "true", "false", "null"));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "boolean", "byte", "char", "double", "float", "int", "long",
            "short", "void",
            "String", "Object", "Integer", "Long", "Double", "Float",
            "Boolean", "Byte", "Short", "Character", "Void", "Number",
            "CharSequence", "StringBuilder", "List", "Map", "Set",
            "ArrayList", "HashMap", "HashSet", "Exception", "RuntimeException",
            "Thread", "Runnable", "Math", "System"));

    /** Callback receiving each token of the lexed line. */
    public interface TokenSink {
        /**
         * @param type   token category
         * @param start  start column within the line
         * @param end    exclusive end column within the line
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
    public int tokenizeLine(CharSequence line, int inState, TokenSink sink) {
        final int n = line.length();
        int i = 0;

        // Resume a multi-line construct.
        if (inState == STATE_IN_BLOCK_COMMENT) {
            int close = indexOf(line, "*/", 0);
            if (close < 0) {
                if (n > 0) {
                    sink.token(TokenType.COMMENT, 0, n);
                }
                return STATE_IN_BLOCK_COMMENT;
            }
            sink.token(TokenType.COMMENT, 0, close + 2);
            i = close + 2;
        } else if (inState == STATE_IN_TEXT_BLOCK) {
            int close = indexOf(line, "\"\"\"", 0);
            if (close < 0) {
                if (n > 0) {
                    sink.token(TokenType.STRING, 0, n);
                }
                return STATE_IN_TEXT_BLOCK;
            }
            sink.token(TokenType.STRING, 0, close + 3);
            i = close + 3;
        }

        while (i < n) {
            char c = line.charAt(i);

            // -- whitespace --------------------------------------------
            if (c == ' ' || c == '\t') {
                int s = i;
                while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                    i++;
                }
                sink.token(TokenType.WHITESPACE, s, i);
                continue;
            }

            // -- comments ----------------------------------------------
            if (c == '/' && i + 1 < n) {
                char c2 = line.charAt(i + 1);
                if (c2 == '/') {
                    sink.token(TokenType.COMMENT, i, n);
                    return STATE_DEFAULT;
                }
                if (c2 == '*') {
                    int close = indexOf(line, "*/", i + 2);
                    if (close < 0) {
                        sink.token(TokenType.COMMENT, i, n);
                        return STATE_IN_BLOCK_COMMENT;
                    }
                    sink.token(TokenType.COMMENT, i, close + 2);
                    i = close + 2;
                    continue;
                }
            }

            // -- string / text block -----------------------------------
            if (c == '"') {
                if (i + 2 < n && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                    int close = indexOf(line, "\"\"\"", i + 3);
                    if (close < 0) {
                        sink.token(TokenType.STRING, i, n);
                        return STATE_IN_TEXT_BLOCK;
                    }
                    sink.token(TokenType.STRING, i, close + 3);
                    i = close + 3;
                    continue;
                }
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

            // -- char literal ------------------------------------------
            if (c == '\'') {
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

            // -- number ------------------------------------------------
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int s = i;
                i = scanNumber(line, i);
                sink.token(TokenType.NUMBER, s, i);
                continue;
            }

            // -- annotation --------------------------------------------
            if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(line.charAt(i + 1))) {
                int s = i++;
                while (i < n && Character.isJavaIdentifierPart(line.charAt(i))) {
                    i++;
                }
                sink.token(TokenType.ANNOTATION, s, i);
                continue;
            }

            // -- identifier / keyword / type ---------------------------
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                while (i < n && Character.isJavaIdentifierPart(line.charAt(i))) {
                    i++;
                }
                String word = line.subSequence(s, i).toString();
                TokenType t = KEYWORDS.contains(word) ? TokenType.KEYWORD
                        : TYPES.contains(word) ? TokenType.TYPE
                        : TokenType.IDENTIFIER;
                sink.token(t, s, i);
                continue;
            }

            // -- operators & punctuation -------------------------------
            if (isOperatorChar(c)) {
                int s = i;
                while (i < n && isOperatorChar(line.charAt(i))) {
                    i++;
                }
                sink.token(TokenType.OPERATOR, s, i);
                continue;
            }
            if (c == '(' || c == ')' || c == '{' || c == '}' || c == '['
                    || c == ']' || c == ';' || c == ',' || c == '.') {
                sink.token(TokenType.PUNCTUATION, i, i + 1);
                i++;
                continue;
            }

            // -- fallback ----------------------------------------------
            sink.token(TokenType.PLAIN, i, i + 1);
            i++;
        }
        return STATE_DEFAULT;
    }

    /** Scans a numeric literal (dec/hex/bin/oct, underscores, suffixes, exponents). */
    private static int scanNumber(CharSequence line, int i) {
        final int n = line.length();
        if (line.charAt(i) == '0' && i + 1 < n) {
            char c2 = line.charAt(i + 1);
            if (c2 == 'x' || c2 == 'X') {
                i += 2;
                while (i < n && (isHexDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    i++;
                }
                return maybeSuffix(line, i);
            }
            if (c2 == 'b' || c2 == 'B') {
                i += 2;
                while (i < n && (line.charAt(i) == '0' || line.charAt(i) == '1'
                        || line.charAt(i) == '_')) {
                    i++;
                }
                return maybeSuffix(line, i);
            }
        }
        while (i < n && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '_')) {
            i++;
        }
        if (i < n && line.charAt(i) == '.') {
            i++;
            while (i < n && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                i++;
            }
        }
        if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < n && (line.charAt(i) == '+' || line.charAt(i) == '-')) {
                i++;
            }
            if (i < n && Character.isDigit(line.charAt(i))) {
                while (i < n && Character.isDigit(line.charAt(i))) {
                    i++;
                }
            } else {
                i = save; // not an exponent after all
            }
        }
        return maybeSuffix(line, i);
    }

    private static int maybeSuffix(CharSequence line, int i) {
        if (i < line.length()) {
            char c = line.charAt(i);
            if (c == 'l' || c == 'L' || c == 'f' || c == 'F' || c == 'd' || c == 'D') {
                return i + 1;
            }
        }
        return i;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isOperatorChar(char c) {
        switch (c) {
            case '+': case '-': case '*': case '/': case '%':
            case '=': case '<': case '>': case '!': case '&':
            case '|': case '^': case '~': case '?': case ':':
                return true;
            default:
                return false;
        }
    }

    private static int indexOf(CharSequence s, String needle, int from) {
        final int n = s.length() - needle.length();
        outer:
        for (int i = Math.max(from, 0); i <= n; i++) {
            for (int j = 0; j < needle.length(); j++) {
                if (s.charAt(i + j) != needle.charAt(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

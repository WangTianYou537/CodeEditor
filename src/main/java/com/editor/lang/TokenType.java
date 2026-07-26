package com.editor.lang;

/** Token categories the color scheme maps to paints. */
public enum TokenType {
    KEYWORD,
    TYPE,          // primitive types + well-known class names
    IDENTIFIER,
    NUMBER,
    STRING,
    CHAR_LITERAL,
    COMMENT,
    ANNOTATION,
    OPERATOR,
    PUNCTUATION,   // braces, brackets, separators
    WHITESPACE,
    PLAIN          // anything unclassified
}

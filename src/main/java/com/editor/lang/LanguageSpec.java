package com.editor.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Declarative description of a programming language's lexical surface
 * and completion sources. Loaded from a grammar JSON file or supplied by
 * a {@link com.editor.plugin.LanguagePlugin}.
 *
 * Fields are public final so the grammar lexer can read them without
 * getters; construction always goes through {@link Builder}.
 */
public final class LanguageSpec {

    public final String name;
    public final List<String> extensions;

    public final Set<String> keywords;
    public final Set<String> types;
    public final List<Snippet> snippets;

    public final String lineComment;           // e.g. "//"; null = none
    public final String blockCommentOpen;      // e.g. "/*"; null = none
    public final String blockCommentClose;     // e.g. "*/"
    public final String textBlockOpen;         // e.g. "\"\"\""; null = none
    public final String textBlockClose;
    public final String rawStringDelimiter;    // e.g. "`" for Go; null = none

    public final boolean doubleQuotedStrings;
    public final boolean singleQuotedChars;    // Java char literals
    public final boolean singleQuotedStrings;  // languages where ' is a string

    public final String operatorChars;
    public final String punctuationChars;

    public final boolean hexNumbers;
    public final boolean binNumbers;
    public final boolean underscoreInNumbers;
    public final String numberSuffixes;

    /** One completion snippet: trigger → insert text. */
    public static final class Snippet {
        public final String trigger;
        public final String insert;
        public final String detail;

        public Snippet(String trigger, String insert, String detail) {
            this.trigger = trigger;
            this.insert = insert;
            this.detail = detail == null ? "" : detail;
        }
    }

    private LanguageSpec(Builder b) {
        this.name = b.name;
        this.extensions = Collections.unmodifiableList(new ArrayList<>(b.extensions));
        this.keywords = Collections.unmodifiableSet(new HashSet<>(b.keywords));
        this.types = Collections.unmodifiableSet(new HashSet<>(b.types));
        this.snippets = Collections.unmodifiableList(new ArrayList<>(b.snippets));
        this.lineComment = b.lineComment;
        this.blockCommentOpen = b.blockCommentOpen;
        this.blockCommentClose = b.blockCommentClose;
        this.textBlockOpen = b.textBlockOpen;
        this.textBlockClose = b.textBlockClose;
        this.rawStringDelimiter = b.rawStringDelimiter;
        this.doubleQuotedStrings = b.doubleQuotedStrings;
        this.singleQuotedChars = b.singleQuotedChars;
        this.singleQuotedStrings = b.singleQuotedStrings;
        this.operatorChars = b.operatorChars;
        this.punctuationChars = b.punctuationChars;
        this.hexNumbers = b.hexNumbers;
        this.binNumbers = b.binNumbers;
        this.underscoreInNumbers = b.underscoreInNumbers;
        this.numberSuffixes = b.numberSuffixes;
    }

    public static final class Builder {
        private String name = "plain";
        private final List<String> extensions = new ArrayList<>();
        private final List<String> keywords = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final List<Snippet> snippets = new ArrayList<>();
        private String lineComment = "//";
        private String blockCommentOpen = "/*";
        private String blockCommentClose = "*/";
        private String textBlockOpen;
        private String textBlockClose;
        private String rawStringDelimiter;
        private boolean doubleQuotedStrings = true;
        private boolean singleQuotedChars = false;
        private boolean singleQuotedStrings = false;
        private String operatorChars = "+-*/%=<>!&|^~?:";
        private String punctuationChars = "(){}[];,.";
        private boolean hexNumbers = true;
        private boolean binNumbers = true;
        private boolean underscoreInNumbers = true;
        private String numberSuffixes = "lLfFdDuU";

        public Builder name(String v) { this.name = v; return this; }
        public Builder extension(String v) { this.extensions.add(v); return this; }
        public Builder extensions(Iterable<String> v) {
            for (String s : v) this.extensions.add(s);
            return this;
        }
        public Builder keywords(Iterable<String> v) {
            for (String s : v) this.keywords.add(s);
            return this;
        }
        public Builder types(Iterable<String> v) {
            for (String s : v) this.types.add(s);
            return this;
        }
        public Builder snippet(String trigger, String insert, String detail) {
            this.snippets.add(new Snippet(trigger, insert, detail));
            return this;
        }
        public Builder lineComment(String v) { this.lineComment = v; return this; }
        public Builder blockComment(String open, String close) {
            this.blockCommentOpen = open;
            this.blockCommentClose = close;
            return this;
        }
        public Builder noBlockComment() {
            this.blockCommentOpen = null;
            this.blockCommentClose = null;
            return this;
        }
        public Builder textBlock(String open, String close) {
            this.textBlockOpen = open;
            this.textBlockClose = close;
            return this;
        }
        public Builder rawStringDelimiter(String v) {
            this.rawStringDelimiter = v;
            return this;
        }
        public Builder doubleQuotedStrings(boolean v) {
            this.doubleQuotedStrings = v;
            return this;
        }
        public Builder singleQuotedChars(boolean v) {
            this.singleQuotedChars = v;
            return this;
        }
        public Builder singleQuotedStrings(boolean v) {
            this.singleQuotedStrings = v;
            return this;
        }
        public Builder operatorChars(String v) { this.operatorChars = v; return this; }
        public Builder punctuationChars(String v) {
            this.punctuationChars = v;
            return this;
        }
        public Builder hexNumbers(boolean v) { this.hexNumbers = v; return this; }
        public Builder binNumbers(boolean v) { this.binNumbers = v; return this; }
        public Builder underscoreInNumbers(boolean v) {
            this.underscoreInNumbers = v;
            return this;
        }
        public Builder numberSuffixes(String v) { this.numberSuffixes = v; return this; }

        public LanguageSpec build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("language name required");
            }
            return new LanguageSpec(this);
        }
    }
}

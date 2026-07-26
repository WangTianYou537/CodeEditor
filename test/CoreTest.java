import cn.wty5.editor.core.Document;
import cn.wty5.editor.core.PieceTable;
import cn.wty5.editor.core.UndoManager;
import cn.wty5.editor.lang.JavaLexer;
import cn.wty5.editor.lang.TokenType;
import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.complete.CompletionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Smoke + fuzz tests for the editor core (no Android dependencies). */
public class CoreTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void checkEq(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: " + what
                    + "\n  expected: " + str(expected) + "\n  actual:   " + str(actual));
        }
    }

    static String str(Object o) {
        return o.toString().replace("\n", "\\n");
    }

    public static void main(String[] args) {
        testPieceTableBasics();
        testPieceTableLines();
        testPieceTableFuzz();
        testUndoRedo();
        testUndoMerge();
        testLexer();
        testLexerIncrementalStates();
        testCompletion();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    static void testPieceTableBasics() {
        PieceTable pt = new PieceTable("hello world");
        checkEq("hello world", pt.toString(), "initial content");
        checkEq(11, pt.length(), "initial length");

        pt.insert(5, ",");
        checkEq("hello, world", pt.toString(), "middle insert");

        pt.insert(0, ">> ");
        checkEq(">> hello, world", pt.toString(), "front insert");

        pt.insert(pt.length(), "!");
        checkEq(">> hello, world!", pt.toString(), "end insert");

        pt.delete(0, 3);
        checkEq("hello, world!", pt.toString(), "front delete");

        pt.delete(5, 6);
        checkEq("hello world!", pt.toString(), "middle delete");

        pt.delete(pt.length() - 1, pt.length());
        checkEq("hello world", pt.toString(), "end delete");

        checkEq('w', pt.charAt(6), "charAt");
        checkEq("lo wo", pt.substring(3, 8), "substring across pieces");

        // Empty table
        PieceTable empty = new PieceTable();
        checkEq(0, empty.length(), "empty length");
        checkEq(1, empty.lineCount(), "empty lineCount");
        empty.insert(0, "abc");
        checkEq("abc", empty.toString(), "insert into empty");

        // Delete everything then re-insert
        PieceTable pt2 = new PieceTable("abcdef");
        pt2.delete(0, 6);
        checkEq(0, pt2.length(), "delete-all length");
        checkEq("", pt2.toString(), "delete-all content");
        pt2.insert(0, "xy");
        checkEq("xy", pt2.toString(), "insert after delete-all");
    }

    static void testPieceTableLines() {
        PieceTable pt = new PieceTable("line0\nline1\nline2");
        checkEq(3, pt.lineCount(), "lineCount");
        checkEq(0, pt.lineStart(0), "lineStart 0");
        checkEq(6, pt.lineStart(1), "lineStart 1");
        checkEq(12, pt.lineStart(2), "lineStart 2");
        checkEq(5, pt.lineEnd(0), "lineEnd 0");
        checkEq(17, pt.lineEnd(2), "lineEnd last");
        checkEq("line1", pt.lineContent(1), "lineContent");
        checkEq(0, pt.lineOfOffset(0), "lineOfOffset 0");
        checkEq(0, pt.lineOfOffset(5), "lineOfOffset at \\n");
        checkEq(1, pt.lineOfOffset(6), "lineOfOffset after \\n");
        checkEq(2, pt.lineOfOffset(17), "lineOfOffset at length");

        pt.insert(5, "\nnew");
        // "line0\nnew\nline1\nline2"
        checkEq(4, pt.lineCount(), "lineCount after newline insert");
        checkEq("new", pt.lineContent(1), "inserted line content");
        checkEq("line1", pt.lineContent(2), "shifted line content");

        pt.delete(5, 9); // remove "\nnew"
        checkEq(3, pt.lineCount(), "lineCount after delete");
        checkEq("line0", pt.lineContent(0), "line0 restored");
        checkEq("line1", pt.lineContent(1), "line1 restored");

        // Trailing newline: doc ends with '\n' → last line is empty
        PieceTable pt3 = new PieceTable("a\n");
        checkEq(2, pt3.lineCount(), "trailing newline lineCount");
        checkEq("", pt3.lineContent(1), "trailing empty line");
    }

    /** Randomized diff-test of PieceTable against StringBuilder. */
    static void testPieceTableFuzz() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 30; trial++) {
            String init = randomText(rnd, rnd.nextInt(200));
            PieceTable pt = new PieceTable(init);
            StringBuilder sb = new StringBuilder(init);
            for (int op = 0; op < 300; op++) {
                if (sb.length() == 0 || rnd.nextBoolean()) {
                    int at = rnd.nextInt(sb.length() + 1);
                    String text = randomText(rnd, 1 + rnd.nextInt(10));
                    pt.insert(at, text);
                    sb.insert(at, text);
                } else {
                    int s = rnd.nextInt(sb.length());
                    int e = Math.min(sb.length(), s + 1 + rnd.nextInt(10));
                    pt.delete(s, e);
                    sb.delete(s, e);
                }
                if (op % 50 == 0 && !pt.toString().equals(sb.toString())) {
                    check(false, "fuzz mismatch trial=" + trial + " op=" + op);
                    return;
                }
            }
            checkEq(sb.toString(), pt.toString(), "fuzz final content trial=" + trial);
            // Verify the line index agrees with a naive count.
            String s = sb.toString();
            int naiveLines = 1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') naiveLines++;
            }
            checkEq(naiveLines, pt.lineCount(), "fuzz lineCount trial=" + trial);
            for (int probe = 0; probe < 20 && s.length() > 0; probe++) {
                int off = rnd.nextInt(s.length() + 1);
                int naiveLine = 0;
                for (int i = 0; i < off; i++) {
                    if (s.charAt(i) == '\n') naiveLine++;
                }
                checkEq(naiveLine, pt.lineOfOffset(off),
                        "fuzz lineOfOffset trial=" + trial + " off=" + off);
            }
            for (int line = 0; line < naiveLines; line += Math.max(1, naiveLines / 7)) {
                int naiveStart = 0;
                int seen = 0;
                for (int i = 0; i < s.length() && seen < line; i++) {
                    if (s.charAt(i) == '\n') {
                        seen++;
                        naiveStart = i + 1;
                    }
                }
                checkEq(naiveStart, pt.lineStart(line),
                        "fuzz lineStart trial=" + trial + " line=" + line);
            }
        }
    }

    static String randomText(Random rnd, int len) {
        StringBuilder sb = new StringBuilder(len);
        String alphabet = "abcXYZ 019{}();\n\n";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    static void testUndoRedo() {
        Document doc = new Document("abc");
        UndoManager um = new UndoManager(doc);

        doc.insert(3, "def");
        checkEq("abcdef", doc.toString(), "insert applied");
        check(um.canUndo(), "canUndo after insert");

        int caret = um.undo();
        checkEq("abc", doc.toString(), "undo insert");
        checkEq(3, caret, "undo caret");
        check(um.canRedo(), "canRedo after undo");

        caret = um.redo();
        checkEq("abcdef", doc.toString(), "redo insert");
        checkEq(6, caret, "redo caret");

        um.sealCurrent();
        doc.delete(0, 3);
        checkEq("def", doc.toString(), "delete applied");
        um.undo();
        checkEq("abcdef", doc.toString(), "undo delete restores text");
        um.undo();
        checkEq("abc", doc.toString(), "second undo");
        check(!um.canUndo(), "undo stack exhausted");

        um.redo();
        um.redo();
        checkEq("def", doc.toString(), "redo chain");

        // New edit clears redo.
        um.undo();
        doc.insert(0, "X");
        check(!um.canRedo(), "redo cleared by new edit");
    }

    static void testUndoMerge() {
        Document doc = new Document();
        UndoManager um = new UndoManager(doc);

        // Simulated typing: consecutive 1-char inserts merge into one step.
        String word = "hello";
        int off = 0;
        for (char c : word.toCharArray()) {
            doc.insert(off++, String.valueOf(c));
        }
        checkEq("hello", doc.toString(), "typed word");
        um.undo();
        checkEq("", doc.toString(), "typing merged into one undo step");

        um.redo();
        checkEq("hello", doc.toString(), "redo merged step");

        // Batch: several edits collapse into one step.
        um.beginBatch();
        doc.insert(5, " wor");
        doc.insert(9, "ld");
        um.endBatch();
        checkEq("hello world", doc.toString(), "batch applied");
        um.undo();
        checkEq("hello", doc.toString(), "batch undone as one step");

        // Backspace run merges.
        um.sealCurrent();
        doc.delete(4, 5);
        doc.delete(3, 4);
        doc.delete(2, 3);
        checkEq("he", doc.toString(), "backspaces applied");
        um.undo();
        checkEq("hello", doc.toString(), "backspace run undone as one step");

        // Newline breaks the merge run.
        Document doc2 = new Document();
        UndoManager um2 = new UndoManager(doc2);
        doc2.insert(0, "a");
        doc2.insert(1, "\n");
        doc2.insert(2, "b");
        um2.undo();
        checkEq("a\n", doc2.toString(), "undo stops at newline boundary");
    }

    // ------------------------------------------------------------------

    static final class Tok {
        final TokenType type;
        final int start, end;
        Tok(TokenType t, int s, int e) { type = t; start = s; end = e; }
    }

    static List<Tok> lex(JavaLexer lexer, String line, int inState, int[] outState) {
        List<Tok> toks = new ArrayList<>();
        outState[0] = lexer.tokenizeLine(line, inState,
                (t, s, e) -> toks.add(new Tok(t, s, e)));
        return toks;
    }

    static Tok tokenAt(List<Tok> toks, int col) {
        for (Tok t : toks) {
            if (col >= t.start && col < t.end) return t;
        }
        return null;
    }

    static void testLexer() {
        JavaLexer lexer = new JavaLexer();
        int[] out = new int[1];

        String line = "public static int x = 0x1F + 42L; // count";
        List<Tok> toks = lex(lexer, line, JavaLexer.STATE_DEFAULT, out);
        checkEq(JavaLexer.STATE_DEFAULT, out[0], "simple line out-state");

        checkEq(TokenType.KEYWORD, tokenAt(toks, line.indexOf("public")).type, "public kw");
        checkEq(TokenType.KEYWORD, tokenAt(toks, line.indexOf("static")).type, "static kw");
        checkEq(TokenType.TYPE, tokenAt(toks, line.indexOf("int")).type, "int type");
        checkEq(TokenType.IDENTIFIER, tokenAt(toks, line.indexOf("x ")).type, "x ident");
        checkEq(TokenType.NUMBER, tokenAt(toks, line.indexOf("0x1F")).type, "hex number");
        checkEq(TokenType.NUMBER, tokenAt(toks, line.indexOf("42L")).type, "long number");
        checkEq(TokenType.COMMENT, tokenAt(toks, line.indexOf("//")).type, "line comment");

        // Full coverage: tokens tile the line with no gaps or overlaps.
        int pos = 0;
        for (Tok t : toks) {
            checkEq(pos, t.start, "token tiling at " + pos);
            pos = t.end;
        }
        checkEq(line.length(), pos, "tokens cover whole line");

        // Strings with escapes.
        String s2 = "String s = \"a\\\"b\" + 'c';";
        List<Tok> toks2 = lex(lexer, s2, JavaLexer.STATE_DEFAULT, out);
        Tok strTok = tokenAt(toks2, s2.indexOf('"'));
        checkEq(TokenType.STRING, strTok.type, "string token");
        checkEq(s2.indexOf('"') + 6, strTok.end, "escaped quote inside string");
        checkEq(TokenType.CHAR_LITERAL, tokenAt(toks2, s2.indexOf("'c'")).type, "char literal");

        // Annotation.
        List<Tok> toks3 = lex(lexer, "@Override", JavaLexer.STATE_DEFAULT, out);
        checkEq(TokenType.ANNOTATION, toks3.get(0).type, "annotation");

        // Numbers: float/exponent/binary.
        List<Tok> toks4 = lex(lexer, "1.5e-3f 0b1010 1_000_000", JavaLexer.STATE_DEFAULT, out);
        checkEq(TokenType.NUMBER, toks4.get(0).type, "float exp number");
        checkEq(7, toks4.get(0).end, "float exp extent");
        checkEq(TokenType.NUMBER, tokenAt(toks4, 8).type, "binary number");
        checkEq(TokenType.NUMBER, tokenAt(toks4, 15).type, "underscore number");
    }

    static void testLexerIncrementalStates() {
        JavaLexer lexer = new JavaLexer();
        int[] out = new int[1];

        // Open block comment.
        lex(lexer, "int a; /* start", JavaLexer.STATE_DEFAULT, out);
        checkEq(JavaLexer.STATE_IN_BLOCK_COMMENT, out[0], "open block comment state");

        // Inside continues.
        List<Tok> mid = lex(lexer, "still comment", JavaLexer.STATE_IN_BLOCK_COMMENT, out);
        checkEq(JavaLexer.STATE_IN_BLOCK_COMMENT, out[0], "still-inside state");
        checkEq(TokenType.COMMENT, mid.get(0).type, "inside is comment");

        // Close resumes code.
        List<Tok> close = lex(lexer, "end */ int b;", JavaLexer.STATE_IN_BLOCK_COMMENT, out);
        checkEq(JavaLexer.STATE_DEFAULT, out[0], "closed state");
        checkEq(TokenType.COMMENT, close.get(0).type, "closing chunk is comment");
        checkEq(TokenType.TYPE, tokenAt(close, 7).type, "code after close");

        // One-line block comment stays DEFAULT.
        lex(lexer, "a /* x */ b", JavaLexer.STATE_DEFAULT, out);
        checkEq(JavaLexer.STATE_DEFAULT, out[0], "inline block comment state");

        // Text block open/close.
        lex(lexer, "String t = \"\"\"", JavaLexer.STATE_DEFAULT, out);
        checkEq(JavaLexer.STATE_IN_TEXT_BLOCK, out[0], "text block open");
        List<Tok> tb = lex(lexer, "body \"\"\";", JavaLexer.STATE_IN_TEXT_BLOCK, out);
        checkEq(JavaLexer.STATE_DEFAULT, out[0], "text block close");
        checkEq(TokenType.STRING, tb.get(0).type, "text block body is string");
    }

    // ------------------------------------------------------------------

    static void testCompletion() {
        Document doc = new Document(
                "public class Foo {\n"
                + "    private int counter;\n"
                + "    void increment() { counter++; }\n"
                + "    void getThreadBinding() {}\n"
                + "}\n"
                + "cou");
        CompletionProvider provider = new CompletionProvider(doc, cn.wty5.editor.lang.Languages.java());
        int caret = doc.length(); // right after "cou"
        String snap = doc.toString();
        long ver = doc.version();

        List<CompletionItem> items = provider.complete("cou", caret, snap, ver);
        check(!items.isEmpty(), "completion returns items");
        boolean hasCounter = false;
        for (CompletionItem it : items) {
            if (it.label.equals("counter")) hasCounter = true;
            checkEq(false, it.label.equals("cou"), "doesn't suggest the typed prefix");
        }
        check(hasCounter, "suggests 'counter' from document");

        // Keyword completion.
        items = provider.complete("pub", caret, snap, ver);
        boolean hasPublic = false;
        for (CompletionItem it : items) {
            if (it.label.equals("public")) hasPublic = true;
        }
        check(hasPublic, "suggests 'public' keyword");

        // Snippet.
        items = provider.complete("sou", caret, snap, ver);
        boolean hasSout = false;
        for (CompletionItem it : items) {
            if (it.label.equals("sout")) hasSout = true;
        }
        check(hasSout, "suggests sout snippet");

        // Camel-hump match.
        items = provider.complete("gTB", caret, snap, ver);
        boolean hasCamel = false;
        for (CompletionItem it : items) {
            if (it.label.equals("getThreadBinding")) hasCamel = true;
        }
        check(hasCamel, "camel-hump gTB -> getThreadBinding");

        // Prefix ranking: exact prefix beats substring.
        items = provider.complete("incr", caret, snap, ver);
        check(!items.isEmpty() && items.get(0).label.equals("increment"),
                "prefix match ranked first");
    }
}

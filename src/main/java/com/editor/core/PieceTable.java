package com.editor.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece-table text buffer.
 *
 * Text is stored in two append-only buffers: the immutable {@code original}
 * buffer holding the initially loaded text, and the {@code add} buffer that
 * every insertion is appended to. The logical document is a list of
 * {@link Piece}s, each referencing a span of one buffer. Edits never move
 * existing characters — insert/delete only split pieces and splice the piece
 * list, which makes edits O(pieces) and keeps memory churn minimal.
 *
 * Each piece also caches the number of line breaks it covers plus a running
 * prefix (accumulated length / line breaks) so that offset↔line lookups are
 * O(log pieces) via binary search over the prefix sums. Prefixes are
 * revalidated lazily from the first dirty piece.
 */
public final class PieceTable implements CharSequence {

    /** Which backing buffer a piece points into. */
    static final int BUF_ORIGINAL = 0;
    static final int BUF_ADD = 1;

    /** One contiguous span of a backing buffer. */
    static final class Piece {
        final int buffer;      // BUF_ORIGINAL or BUF_ADD
        final int start;       // offset into the backing buffer
        final int length;      // number of chars
        final int lineBreaks;  // count of '\n' inside this span

        // Cached prefix sums: totals up to but NOT including this piece.
        int prefixLength;
        int prefixLineBreaks;

        Piece(int buffer, int start, int length, int lineBreaks) {
            this.buffer = buffer;
            this.start = start;
            this.length = length;
            this.lineBreaks = lineBreaks;
        }
    }

    private final String original;
    private final StringBuilder add = new StringBuilder();
    private final List<Piece> pieces = new ArrayList<>();

    private int totalLength;
    private int totalLineBreaks;

    /** Index of the first piece whose prefix cache may be stale. */
    private int dirtyFrom = 0;

    /** Cache of the piece found by the last offset lookup (locality of edits). */
    private int lastPieceIndex = -1;

    public PieceTable() {
        this("");
    }

    public PieceTable(String initial) {
        this.original = initial == null ? "" : initial;
        this.totalLength = this.original.length();
        this.totalLineBreaks = countLineBreaks(this.original, 0, this.original.length());
        if (totalLength > 0) {
            pieces.add(new Piece(BUF_ORIGINAL, 0, totalLength, totalLineBreaks));
        }
    }

    // ------------------------------------------------------------------
    // CharSequence
    // ------------------------------------------------------------------

    @Override
    public int length() {
        return totalLength;
    }

    @Override
    public char charAt(int index) {
        checkIndex(index, totalLength - 1);
        int pi = findPiece(index);
        Piece p = pieces.get(pi);
        int local = index - p.prefixLength;
        return bufferOf(p).charAt(p.start + local);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    @Override
    public String toString() {
        return substring(0, totalLength);
    }

    /** Extracts [start, end) as a String in O(end - start + log pieces). */
    public String substring(int start, int end) {
        if (start < 0 || end > totalLength || start > end) {
            throw new IndexOutOfBoundsException(
                    "range [" + start + ", " + end + ") of length " + totalLength);
        }
        if (start == end) {
            return "";
        }
        StringBuilder out = new StringBuilder(end - start);
        int pi = findPiece(start);
        int remaining = end - start;
        int localStart = start - pieces.get(pi).prefixLength;
        while (remaining > 0) {
            Piece p = pieces.get(pi);
            int take = Math.min(remaining, p.length - localStart);
            CharSequence buf = bufferOf(p);
            out.append(buf, p.start + localStart, p.start + localStart + take);
            remaining -= take;
            localStart = 0;
            pi++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /** Inserts {@code text} before offset {@code offset}. */
    public void insert(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        checkIndex(offset, totalLength);

        int addStart = add.length();
        add.append(text);
        int breaks = countLineBreaks(text, 0, text.length());
        Piece inserted = new Piece(BUF_ADD, addStart, text.length(), breaks);

        if (pieces.isEmpty()) {
            pieces.add(inserted);
        } else if (offset == totalLength) {
            // Append at end: try to extend the last piece if the new text
            // directly continues it in the add buffer (typical typing pattern).
            Piece last = pieces.get(pieces.size() - 1);
            if (canMerge(last, inserted)) {
                pieces.set(pieces.size() - 1, merge(last, inserted));
                markDirty(pieces.size() - 1);
            } else {
                pieces.add(inserted);
                markDirty(pieces.size() - 1);
            }
        } else {
            int pi = findPiece(offset);
            Piece p = pieces.get(pi);
            int local = offset - p.prefixLength;
            if (local == 0) {
                // Boundary insert: try merging with the previous piece.
                if (pi > 0 && canMerge(pieces.get(pi - 1), inserted)) {
                    pieces.set(pi - 1, merge(pieces.get(pi - 1), inserted));
                    markDirty(pi - 1);
                } else {
                    pieces.add(pi, inserted);
                    markDirty(pi);
                }
            } else {
                // Split p into left | inserted | right.
                Piece left = slice(p, 0, local);
                Piece right = slice(p, local, p.length);
                pieces.set(pi, left);
                if (canMerge(left, inserted)) {
                    pieces.set(pi, merge(left, inserted));
                    pieces.add(pi + 1, right);
                } else {
                    pieces.add(pi + 1, inserted);
                    pieces.add(pi + 2, right);
                }
                markDirty(pi);
            }
        }
        totalLength += text.length();
        totalLineBreaks += breaks;
    }

    /** Deletes the range [start, end). */
    public void delete(int start, int end) {
        if (start == end) {
            return;
        }
        if (start < 0 || end > totalLength || start > end) {
            throw new IndexOutOfBoundsException(
                    "range [" + start + ", " + end + ") of length " + totalLength);
        }

        int removedBreaks = 0;
        int pi = findPiece(start);
        int localStart = start - pieces.get(pi).prefixLength;
        int remaining = end - start;

        // Piece assembled from the untouched head of the first affected piece.
        Piece head = localStart > 0 ? slice(pieces.get(pi), 0, localStart) : null;

        int removeFrom = pi;
        Piece tail = null;
        while (remaining > 0) {
            Piece p = pieces.get(pi);
            int available = p.length - localStart;
            if (remaining < available) {
                // Deletion ends inside this piece; keep its tail.
                Piece removed = slice(p, localStart, localStart + remaining);
                removedBreaks += removed.lineBreaks;
                tail = slice(p, localStart + remaining, p.length);
                remaining = 0;
            } else {
                Piece removed = slice(p, localStart, p.length);
                removedBreaks += removed.lineBreaks;
                remaining -= available;
            }
            localStart = 0;
            pi++;
        }

        // Splice: replace pieces[removeFrom .. pi) with {head?, tail?}.
        List<Piece> replacement = new ArrayList<>(2);
        if (head != null) {
            replacement.add(head);
        }
        if (tail != null) {
            replacement.add(tail);
        }
        pieces.subList(removeFrom, pi).clear();
        pieces.addAll(removeFrom, replacement);
        markDirty(Math.max(0, removeFrom - 1));

        totalLength -= (end - start);
        totalLineBreaks -= removedBreaks;
        lastPieceIndex = -1;
    }

    /** Convenience: delete + insert as one logical replace. */
    public void replace(int start, int end, String text) {
        delete(start, end);
        insert(start, text == null ? "" : text);
    }

    // ------------------------------------------------------------------
    // Line queries
    // ------------------------------------------------------------------

    /** Number of lines; an empty document has 1 line. */
    public int lineCount() {
        return totalLineBreaks + 1;
    }

    /**
     * Offset of the first character of {@code line} (0-based).
     * Line L starts right after the L-th '\n' (line 0 starts at offset 0).
     */
    public int lineStart(int line) {
        if (line < 0 || line >= lineCount()) {
            throw new IndexOutOfBoundsException("line " + line + " of " + lineCount());
        }
        if (line == 0) {
            return 0;
        }
        validatePrefixes();
        // Binary search: first piece whose prefixLineBreaks + own breaks >= line.
        int lo = 0, hi = pieces.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Piece p = pieces.get(mid);
            if (p.prefixLineBreaks + p.lineBreaks >= line) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        Piece p = pieces.get(found);
        int need = line - p.prefixLineBreaks; // which '\n' inside p (1-based)
        CharSequence buf = bufferOf(p);
        int seen = 0;
        for (int i = 0; i < p.length; i++) {
            if (buf.charAt(p.start + i) == '\n' && ++seen == need) {
                return p.prefixLength + i + 1;
            }
        }
        throw new IllegalStateException("line-break cache out of sync");
    }

    /** Exclusive end offset of {@code line}'s content (before its '\n', if any). */
    public int lineEnd(int line) {
        if (line == lineCount() - 1) {
            return totalLength;
        }
        return lineStart(line + 1) - 1;
    }

    /** 0-based line containing {@code offset}. Accepts offset == length(). */
    public int lineOfOffset(int offset) {
        checkIndex(offset, totalLength);
        if (offset == 0) {
            return 0;
        }
        validatePrefixes();
        if (pieces.isEmpty()) {
            return 0;
        }
        int pi = findPiece(Math.min(offset, totalLength - 1));
        Piece p = pieces.get(pi);
        int line = p.prefixLineBreaks;
        CharSequence buf = bufferOf(p);
        int localEnd = Math.min(offset - p.prefixLength, p.length);
        for (int i = 0; i < localEnd; i++) {
            if (buf.charAt(p.start + i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Content of one line, without its trailing '\n'. */
    public String lineContent(int line) {
        return substring(lineStart(line), lineEnd(line));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private CharSequence bufferOf(Piece p) {
        return p.buffer == BUF_ORIGINAL ? original : add;
    }

    /** Sub-span [from, to) of a piece, recounting its line breaks. */
    private Piece slice(Piece p, int from, int to) {
        CharSequence buf = bufferOf(p);
        int breaks = countLineBreaks(buf, p.start + from, p.start + to);
        return new Piece(p.buffer, p.start + from, to - from, breaks);
    }

    /** Two pieces merge iff they are adjacent spans of the add buffer. */
    private static boolean canMerge(Piece a, Piece b) {
        return a.buffer == BUF_ADD && b.buffer == BUF_ADD
                && a.start + a.length == b.start;
    }

    private static Piece merge(Piece a, Piece b) {
        return new Piece(a.buffer, a.start, a.length + b.length,
                a.lineBreaks + b.lineBreaks);
    }

    private static int countLineBreaks(CharSequence s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private void markDirty(int index) {
        dirtyFrom = Math.min(dirtyFrom, index);
        lastPieceIndex = -1;
    }

    /** Recomputes prefix sums from the first dirty piece onward. */
    private void validatePrefixes() {
        if (dirtyFrom >= pieces.size()) {
            dirtyFrom = pieces.size();
            return;
        }
        int len;
        int breaks;
        if (dirtyFrom == 0) {
            len = 0;
            breaks = 0;
        } else {
            Piece prev = pieces.get(dirtyFrom - 1);
            len = prev.prefixLength + prev.length;
            breaks = prev.prefixLineBreaks + prev.lineBreaks;
        }
        for (int i = dirtyFrom; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            p.prefixLength = len;
            p.prefixLineBreaks = breaks;
            len += p.length;
            breaks += p.lineBreaks;
        }
        dirtyFrom = pieces.size();
    }

    /**
     * Index of the piece containing {@code offset}. For offset == totalLength
     * returns the last piece. Requires a non-empty piece list.
     */
    private int findPiece(int offset) {
        validatePrefixes();
        // Fast path: repeated lookups near the same spot (typing).
        if (lastPieceIndex >= 0 && lastPieceIndex < pieces.size()) {
            Piece c = pieces.get(lastPieceIndex);
            if (offset >= c.prefixLength && offset < c.prefixLength + c.length) {
                return lastPieceIndex;
            }
        }
        int lo = 0, hi = pieces.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            Piece p = pieces.get(mid);
            if (offset < p.prefixLength) {
                hi = mid - 1;
            } else if (offset >= p.prefixLength + p.length) {
                lo = mid + 1;
            } else {
                lo = hi = mid;
            }
        }
        lastPieceIndex = lo;
        return lo;
    }

    private void checkIndex(int index, int max) {
        if (index < 0 || index > max) {
            throw new IndexOutOfBoundsException("index " + index + ", max " + max);
        }
    }

    // Visible for tests / debugging.
    int pieceCount() {
        return pieces.size();
    }
}

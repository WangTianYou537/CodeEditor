package com.editor.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The document model the editor works against.
 *
 * Wraps a {@link PieceTable} and adds:
 * <ul>
 *   <li>change notification ({@link ContentListener}) with the exact edit
 *       delta, which the highlighter/undo/view all subscribe to;</li>
 *   <li>a monotonically increasing {@link #version()} so async consumers
 *       (highlighter, completion) can detect stale results;</li>
 *   <li>line/column helpers on top of the piece table's line index.</li>
 * </ul>
 *
 * All mutation must happen on a single thread (the UI thread in the editor);
 * listeners are invoked synchronously on that thread.
 */
public final class Document implements CharSequence {

    /** Receives edit deltas. {@code text} is the inserted/removed content. */
    public interface ContentListener {
        void onInsert(Document doc, int offset, String text);

        void onDelete(Document doc, int offset, String text);
    }

    private PieceTable table;
    private final List<ContentListener> listeners = new CopyOnWriteArrayList<>();
    private long version;

    public Document() {
        this("");
    }

    public Document(String text) {
        this.table = new PieceTable(text);
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    public void insert(int offset, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        table.insert(offset, text);
        version++;
        for (ContentListener l : listeners) {
            l.onInsert(this, offset, text);
        }
    }

    public void delete(int start, int end) {
        if (start == end) {
            return;
        }
        String removed = table.substring(start, end);
        table.delete(start, end);
        version++;
        for (ContentListener l : listeners) {
            l.onDelete(this, start, removed);
        }
    }

    /** Delete + insert reported as two deltas (keeps listeners simple). */
    public void replace(int start, int end, String text) {
        delete(start, end);
        insert(start, text);
    }

    /** Replaces the whole content; a fresh piece table avoids garbage pieces. */
    public void setText(String text) {
        int oldLen = table.length();
        if (oldLen > 0) {
            delete(0, oldLen);
        }
        table = new PieceTable(text == null ? "" : text);
        version++;
        if (table.length() > 0) {
            for (ContentListener l : listeners) {
                l.onInsert(this, 0, table.toString());
            }
        }
    }

    /** Increases on every edit; async workers compare against it. */
    public long version() {
        return version;
    }

    // ------------------------------------------------------------------
    // CharSequence + queries
    // ------------------------------------------------------------------

    @Override
    public int length() {
        return table.length();
    }

    @Override
    public char charAt(int index) {
        return table.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return table.substring(start, end);
    }

    public String substring(int start, int end) {
        return table.substring(start, end);
    }

    @Override
    public String toString() {
        return table.toString();
    }

    public int lineCount() {
        return table.lineCount();
    }

    public int lineStart(int line) {
        return table.lineStart(line);
    }

    public int lineEnd(int line) {
        return table.lineEnd(line);
    }

    public String lineContent(int line) {
        return table.lineContent(line);
    }

    public int lineOfOffset(int offset) {
        return table.lineOfOffset(offset);
    }

    /** Column (0-based chars from line start) of {@code offset}. */
    public int columnOfOffset(int offset) {
        return offset - lineStart(lineOfOffset(offset));
    }

    /** Offset for (line, column), clamping column to the line's length. */
    public int offsetAt(int line, int column) {
        int start = lineStart(line);
        int max = lineEnd(line) - start;
        return start + Math.min(Math.max(column, 0), max);
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addContentListener(ContentListener l) {
        listeners.add(l);
    }

    public void removeContentListener(ContentListener l) {
        listeners.remove(l);
    }
}

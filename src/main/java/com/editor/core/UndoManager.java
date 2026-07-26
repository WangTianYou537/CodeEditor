package com.editor.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo/redo built on the {@link Document}'s edit deltas.
 *
 * Listens to the document, records each delta, and groups consecutive
 * single-character typing (and consecutive backspacing) into one undo step.
 * Applying an undo/redo replays inverse deltas with recording suspended.
 */
public final class UndoManager implements Document.ContentListener {

    private static final int MAX_STEPS = 500;
    /** Deltas closer together than this merge into one step (ms). */
    private static final long MERGE_WINDOW_MS = 800;

    private static final int KIND_INSERT = 0;
    private static final int KIND_DELETE = 1;

    private static final class Edit {
        final int kind;
        final int offset;
        final String text;

        Edit(int kind, int offset, String text) {
            this.kind = kind;
            this.offset = offset;
            this.text = text;
        }
    }

    /** One undoable step: a batch of edits applied in order. */
    private static final class Step {
        final List<Edit> edits = new ArrayList<>(4);
        long lastEditAt;
        boolean sealed;
    }

    private final Document document;
    private final Deque<Step> undoStack = new ArrayDeque<>();
    private final Deque<Step> redoStack = new ArrayDeque<>();
    private boolean replaying;
    private int batchDepth;

    public UndoManager(Document document) {
        this.document = document;
        document.addContentListener(this);
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    @Override
    public void onInsert(Document doc, int offset, String text) {
        record(new Edit(KIND_INSERT, offset, text));
    }

    @Override
    public void onDelete(Document doc, int offset, String text) {
        record(new Edit(KIND_DELETE, offset, text));
    }

    private void record(Edit edit) {
        if (replaying) {
            return;
        }
        redoStack.clear();
        long now = System.currentTimeMillis();
        Step step = undoStack.peekFirst();
        if (step == null || step.sealed || batchDepth == 0 && !mergeable(step, edit, now)) {
            step = new Step();
            undoStack.addFirst(step);
            trim();
        }
        step.edits.add(edit);
        step.lastEditAt = now;
    }

    /**
     * Typing coalescing: single printable char appended right after the
     * previous insert's end, or single-char delete right before the previous
     * delete's offset (backspace run), inside the time window.
     */
    private boolean mergeable(Step step, Edit edit, long now) {
        if (step.edits.isEmpty() || now - step.lastEditAt > MERGE_WINDOW_MS) {
            return false;
        }
        Edit last = step.edits.get(step.edits.size() - 1);
        if (edit.text.length() != 1 || last.kind != edit.kind) {
            return false;
        }
        char c = edit.text.charAt(0);
        if (c == '\n' || last.text.indexOf('\n') >= 0) {
            return false; // a newline both starts and ends a merge run
        }
        if (edit.kind == KIND_INSERT) {
            return edit.offset == last.offset + last.text.length();
        }
        // Backspace run: each delete lands right before the previous one.
        return edit.offset + edit.text.length() == last.offset
                || edit.offset == last.offset; // forward-delete run
    }

    private void trim() {
        while (undoStack.size() > MAX_STEPS) {
            undoStack.removeLast();
        }
    }

    // ------------------------------------------------------------------
    // Grouping control
    // ------------------------------------------------------------------

    /** Everything between begin/end batch collapses into one undo step. */
    public void beginBatch() {
        if (batchDepth++ == 0) {
            sealCurrent();
            undoStack.addFirst(new Step());
            trim();
        }
    }

    public void endBatch() {
        if (batchDepth > 0 && --batchDepth == 0) {
            Step top = undoStack.peekFirst();
            if (top != null) {
                if (top.edits.isEmpty()) {
                    undoStack.removeFirst(); // batch made no changes
                } else {
                    top.sealed = true;
                }
            }
        }
    }

    /** Prevents further merging into the current step (e.g. on cursor move). */
    public void sealCurrent() {
        Step top = undoStack.peekFirst();
        if (top != null) {
            top.sealed = true;
        }
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Undoes one step.
     *
     * @return caret offset to restore, or -1 if nothing was undone
     */
    public int undo() {
        Step step = undoStack.pollFirst();
        if (step == null) {
            return -1;
        }
        int caret = -1;
        replaying = true;
        try {
            for (int i = step.edits.size() - 1; i >= 0; i--) {
                Edit e = step.edits.get(i);
                if (e.kind == KIND_INSERT) {
                    document.delete(e.offset, e.offset + e.text.length());
                    caret = e.offset;
                } else {
                    document.insert(e.offset, e.text);
                    caret = e.offset + e.text.length();
                }
            }
        } finally {
            replaying = false;
        }
        step.sealed = true;
        redoStack.addFirst(step);
        return caret;
    }

    /**
     * Redoes one step.
     *
     * @return caret offset to restore, or -1 if nothing was redone
     */
    public int redo() {
        Step step = redoStack.pollFirst();
        if (step == null) {
            return -1;
        }
        int caret = -1;
        replaying = true;
        try {
            for (Edit e : step.edits) {
                if (e.kind == KIND_INSERT) {
                    document.insert(e.offset, e.text);
                    caret = e.offset + e.text.length();
                } else {
                    document.delete(e.offset, e.offset + e.text.length());
                    caret = e.offset;
                }
            }
        } finally {
            replaying = false;
        }
        undoStack.addFirst(step);
        return caret;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        batchDepth = 0;
    }
}

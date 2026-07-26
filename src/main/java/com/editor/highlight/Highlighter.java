package com.editor.highlight;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.editor.core.Document;
import com.editor.lang.Lexer;
import com.editor.lang.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental syntax highlighter.
 *
 * Keeps one {@link LineSpans} per line. On an edit it re-lexes only the
 * touched lines on a background thread, then keeps going while the line's
 * outbound lexer state differs from the cached one (e.g. typing "/*" cascades
 * down; typing a space stops after one line). Results are published to the
 * UI thread; stale results (document changed meanwhile) are dropped by
 * version check and the edit is re-processed.
 *
 * The {@link Lexer} is pluggable — swap it (via {@link #setLexer}) when the
 * user changes language; that invalidates the whole span cache.
 *
 * Threading: all public methods must be called from the UI thread.
 */
public final class Highlighter implements Document.ContentListener {

    /** Notified on the UI thread when a range of lines got fresh spans. */
    public interface Callback {
        void onHighlightUpdated(int firstLine, int lastLine);
    }

    private final Document document;
    private volatile Lexer lexer;
    private final Callback callback;

    private final List<LineSpans> lines = new ArrayList<>();

    private final HandlerThread workerThread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Pending dirty region on the UI thread, merged across rapid edits. */
    private int dirtyFirst = -1;
    private int dirtyLast = -1;
    private boolean scheduled;

    /**
     * Max lines snapshotted per lexing round. The cascade (a state change
     * rippling past the edit, e.g. typing "/*") continues across rounds by
     * re-marking the next chunk dirty, so cost per frame stays bounded.
     */
    private static final int CHUNK_LINES = 256;

    public Highlighter(Document document, Lexer lexer, Callback callback) {
        this.document = document;
        this.lexer = lexer;
        this.callback = callback;
        this.workerThread = new HandlerThread("editor-highlight");
        this.workerThread.start();
        this.worker = new Handler(workerThread.getLooper());
        document.addContentListener(this);
        invalidateAll();
    }

    /** Swaps the lexer (e.g. on language change) and re-highlights everything. */
    public void setLexer(Lexer lexer) {
        this.lexer = lexer;
        invalidateAll();
    }

    public Lexer getLexer() {
        return lexer;
    }

    public void shutdown() {
        document.removeContentListener(this);
        workerThread.quitSafely();
    }

    /** Spans for a line, or null while it hasn't been lexed yet. */
    public LineSpans spansFor(int line) {
        return line < lines.size() ? lines.get(line) : null;
    }

    public void invalidateAll() {
        lines.clear();
        for (int i = 0, n = document.lineCount(); i < n; i++) {
            lines.add(null);
        }
        if (document.lineCount() > 0) {
            markDirty(0, document.lineCount() - 1);
        }
    }

    // ------------------------------------------------------------------
    // Document deltas → line-cache maintenance
    // ------------------------------------------------------------------

    @Override
    public void onInsert(Document doc, int offset, String text) {
        int line = doc.lineOfOffset(offset);
        int newLines = countNewlines(text);
        for (int i = 0; i < newLines; i++) {
            lines.add(Math.min(line + 1, lines.size()), null);
        }
        if (line < lines.size()) {
            lines.set(line, null);
        }
        markDirty(line, line + newLines);
    }

    @Override
    public void onDelete(Document doc, int offset, String text) {
        int line = doc.lineOfOffset(offset); // line AFTER the delete
        int removed = countNewlines(text);
        for (int i = 0; i < removed && line + 1 < lines.size(); i++) {
            lines.remove(line + 1);
        }
        if (line < lines.size()) {
            lines.set(line, null);
        }
        markDirty(line, line);
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Async lexing
    // ------------------------------------------------------------------

    private void markDirty(int first, int last) {
        dirtyFirst = dirtyFirst < 0 ? first : Math.min(dirtyFirst, first);
        dirtyLast = Math.max(dirtyLast, last);
        if (!scheduled) {
            scheduled = true;
            // Post (not run inline) so a burst of edits lexes once.
            main.post(this::dispatch);
        }
    }

    private void dispatch() {
        scheduled = false;
        if (dirtyFirst < 0) {
            return;
        }
        final int first = Math.max(0, dirtyFirst);
        // Bound the chunk; anything beyond re-queues via the cascade check.
        final int last = Math.min(Math.min(dirtyLast, first + CHUNK_LINES - 1),
                document.lineCount() - 1);
        if (last < first) {
            // The dirty region fell off the end (lines were deleted).
            dirtyFirst = dirtyLast = -1;
            return;
        }
        if (dirtyLast > last) {
            dirtyFirst = last + 1; // leftover dirty region stays queued
            scheduled = true;
            main.post(this::dispatch);
        } else {
            dirtyFirst = dirtyLast = -1;
        }

        // Snapshot inputs on the UI thread; the worker only touches copies.
        final long version = document.version();
        final Lexer currentLexer = this.lexer;
        final LineSpans prev = first > 0 && first - 1 < lines.size()
                ? lines.get(first - 1) : null;
        final int inState = prev != null ? prev.outState : Lexer.STATE_DEFAULT;
        final List<String> text = new ArrayList<>(last - first + 1);
        for (int i = first; i <= last; i++) {
            text.add(document.lineContent(i));
        }

        worker.post(() -> lexRegion(version, first, inState, text, currentLexer));
    }

    /** Runs on the worker thread; only local data + immutable snapshots. */
    private void lexRegion(long version, int firstLine, int inState,
                           List<String> text, Lexer lex) {
        List<LineSpans> fresh = new ArrayList<>(text.size());
        int state = inState;
        for (String line : text) {
            state = lexOne(lex, line, state, fresh);
        }
        final int to = firstLine + fresh.size() - 1;
        main.post(() -> publish(version, firstLine, to, fresh));
    }

    private int lexOne(Lexer lex, String line, int inState, List<LineSpans> out) {
        LineSpans spans = new LineSpans();
        int outState = lex.tokenizeLine(line, inState,
                (TokenType type, int s, int e) -> spans.add(type, s, e));
        spans.outState = outState;
        out.add(spans);
        return outState;
    }

    /** UI thread: install results unless the document moved on. */
    private void publish(long version, int first, int last, List<LineSpans> fresh) {
        if (version != document.version()) {
            // Stale: an edit landed while we lexed. Its own onInsert/onDelete
            // already queued a re-lex, so just drop this batch.
            return;
        }
        while (lines.size() < document.lineCount()) {
            lines.add(null);
        }
        int installedLast = Math.min(last, lines.size() - 1);
        for (int i = first; i <= installedLast; i++) {
            LineSpans old = lines.get(i);
            lines.set(i, fresh.get(i - first));
            // Cascade: if the last line's out-state changed, the next line
            // was lexed with a now-wrong in-state — re-lex onward. Also
            // continue into never-lexed (null) territory.
            if (i == installedLast && installedLast + 1 < document.lineCount()) {
                LineSpans next = installedLast + 1 < lines.size()
                        ? lines.get(installedLast + 1) : null;
                boolean stateChanged = old == null
                        || old.outState != fresh.get(i - first).outState;
                if (next == null || stateChanged) {
                    // Widen to a chunk so long cascades (e.g. "/*" near the
                    // top) advance CHUNK_LINES per round, not one line.
                    markDirty(installedLast + 1,
                            Math.min(installedLast + CHUNK_LINES,
                                    document.lineCount() - 1));
                }
            }
        }
        callback.onHighlightUpdated(first, installedLast);
    }
}

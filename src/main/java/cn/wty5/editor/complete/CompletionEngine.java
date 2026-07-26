package cn.wty5.editor.complete;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import cn.wty5.editor.core.Document;
import cn.wty5.editor.lang.LanguageSpec;

import java.util.List;

/**
 * Async completion orchestration.
 *
 * The view calls {@link #requestCompletions} on every caret/content change;
 * the engine extracts the identifier prefix before the caret, debounces,
 * computes suggestions on a worker thread, and delivers them on the UI
 * thread — dropping results that are stale by document version or caret
 * position. Keywords / snippets come from the active {@link LanguageSpec}.
 */
public final class CompletionEngine {

    /** UI-thread callback with fresh suggestions (empty list = hide popup). */
    public interface Callback {
        void onCompletions(List<CompletionItem> items, int prefixStart, String prefix);
    }

    private static final long DEBOUNCE_MS = 120;

    private final Document document;
    private final CompletionProvider provider;
    private final Callback callback;

    private final HandlerThread workerThread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private long requestSeq;

    public CompletionEngine(Document document, LanguageSpec language, Callback callback) {
        this.document = document;
        this.provider = new CompletionProvider(document, language);
        this.callback = callback;
        this.workerThread = new HandlerThread("editor-completion");
        this.workerThread.start();
        this.worker = new Handler(workerThread.getLooper());
    }

    public void setLanguage(LanguageSpec language) {
        provider.setLanguage(language);
    }

    public void shutdown() {
        workerThread.quitSafely();
    }

    /** Call on the UI thread whenever the caret moves or text changes. */
    public void requestCompletions(int caretOffset) {
        final long seq = ++requestSeq;
        final int prefixStart = findPrefixStart(caretOffset);
        if (prefixStart == caretOffset) {
            callback.onCompletions(java.util.Collections.emptyList(), caretOffset, "");
            return;
        }
        final String prefix = document.substring(prefixStart, caretOffset);
        final long version = document.version();
        // The piece table is single-threaded: snapshot the text here (UI
        // thread) when the provider's word index is stale; the worker only
        // ever touches immutable strings.
        final String snapshot =
                provider.isIndexCurrent(version) ? null : document.toString();

        worker.removeCallbacksAndMessages(null); // debounce: drop queued work
        worker.postDelayed(() -> {
            List<CompletionItem> items =
                    provider.complete(prefix, caretOffset, snapshot, version);
            main.post(() -> {
                // Deliver only if nothing changed while we computed.
                if (seq == requestSeq && version == document.version()) {
                    callback.onCompletions(items, prefixStart, prefix);
                }
            });
        }, DEBOUNCE_MS);
    }

    /** Cancels any in-flight request and hides the popup. */
    public void cancel() {
        requestSeq++;
        worker.removeCallbacksAndMessages(null);
        callback.onCompletions(java.util.Collections.emptyList(), 0, "");
    }

    /** Walks back from the caret over identifier chars. */
    private int findPrefixStart(int caretOffset) {
        int i = caretOffset;
        while (i > 0 && Character.isJavaIdentifierPart(document.charAt(i - 1))) {
            i--;
        }
        // A prefix can't start mid-number ("123a" → no completion).
        if (i < caretOffset && Character.isDigit(document.charAt(i))) {
            return caretOffset;
        }
        return i;
    }
}

package cn.wty5.editor.complete;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import cn.wty5.editor.core.Document;
import cn.wty5.editor.lang.LanguageSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Async completion orchestration.
 *
 * The view calls {@link #requestCompletions} on every caret/content change;
 * the engine extracts the identifier prefix before the caret, debounces,
 * computes suggestions on a worker thread, and delivers them on the UI
 * thread — dropping results that are stale by document version or caret
 * position. Keywords / snippets come from the active {@link LanguageSpec}.
 *
 * <p>An optional {@link ExternalSource} (e.g. an LSP client) can supply
 * additional items; local grammar results still appear immediately and are
 * merged when the external reply arrives.
 */
public final class CompletionEngine {

    /** UI-thread callback with fresh suggestions (empty list = hide popup). */
    public interface Callback {
        void onCompletions(List<CompletionItem> items, int prefixStart, String prefix);
    }

    /**
     * Optional remote / LSP completion source. Invoked on the UI thread after
     * the local request is queued; implementations must not block. Reply later
     * via {@link #acceptExternal(int, List)}.
     */
    public interface ExternalSource {
        /**
         * @return a request id (&gt;0) that will be passed back to
         *         {@link #acceptExternal}, or &lt;=0 to skip
         */
        int request(int caretOffset, int prefixStart, String prefix);
    }

    private static final long DEBOUNCE_MS = 120;

    private final Document document;
    private final CompletionProvider provider;
    private final Callback callback;

    private final HandlerThread workerThread;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private long requestSeq;
    private volatile ExternalSource externalSource;
    /** Last local items kept so a late LSP reply can merge with them. */
    private List<CompletionItem> lastLocalItems = Collections.emptyList();
    private int lastPrefixStart;
    private String lastPrefix = "";
    private int lastExternalRequestId = -1;

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

    public void setExternalSource(ExternalSource source) {
        this.externalSource = source;
    }

    public void shutdown() {
        workerThread.quitSafely();
    }

    /** Call on the UI thread whenever the caret moves or text changes. */
    public void requestCompletions(int caretOffset) {
        final long seq = ++requestSeq;
        final int prefixStart = findPrefixStart(caretOffset);
        if (prefixStart == caretOffset) {
            lastLocalItems = Collections.emptyList();
            lastExternalRequestId = -1;
            callback.onCompletions(Collections.emptyList(), caretOffset, "");
            return;
        }
        final String prefix = document.substring(prefixStart, caretOffset);
        final long version = document.version();
        // The piece table is single-threaded: snapshot the text here (UI
        // thread) when the provider's word index is stale; the worker only
        // ever touches immutable strings.
        final String snapshot =
                provider.isIndexCurrent(version) ? null : document.toString();

        // Kick the external (LSP) source immediately; reply merges later.
        ExternalSource ext = externalSource;
        if (ext != null) {
            try {
                lastExternalRequestId = ext.request(caretOffset, prefixStart, prefix);
            } catch (Exception e) {
                lastExternalRequestId = -1;
            }
        } else {
            lastExternalRequestId = -1;
        }

        worker.removeCallbacksAndMessages(null); // debounce: drop queued work
        worker.postDelayed(() -> {
            List<CompletionItem> items =
                    provider.complete(prefix, caretOffset, snapshot, version);
            main.post(() -> {
                // Deliver only if nothing changed while we computed.
                if (seq == requestSeq && version == document.version()) {
                    lastLocalItems = items;
                    lastPrefixStart = prefixStart;
                    lastPrefix = prefix;
                    callback.onCompletions(items, prefixStart, prefix);
                }
            });
        }, DEBOUNCE_MS);
    }

    /**
     * UI thread: merge an external (LSP) completion reply with the last local
     * result. Stale ids (superseded by a newer request) are ignored.
     */
    public void acceptExternal(int requestId, List<CompletionItem> external) {
        if (requestId <= 0 || requestId != lastExternalRequestId) return;
        if (external == null || external.isEmpty()) return;
        List<CompletionItem> merged = new ArrayList<>(
                lastLocalItems.size() + external.size());
        HashSet<String> seen = new HashSet<>();
        // External (usually higher quality) first.
        for (CompletionItem it : external) {
            if (it != null && it.label != null && seen.add(it.label)) {
                merged.add(it);
            }
        }
        for (CompletionItem it : lastLocalItems) {
            if (it != null && it.label != null && seen.add(it.label)) {
                merged.add(it);
            }
        }
        if (merged.size() > 40) {
            merged = new ArrayList<>(merged.subList(0, 40));
        }
        callback.onCompletions(merged, lastPrefixStart, lastPrefix);
    }

    /** Cancels any in-flight request and hides the popup. */
    public void cancel() {
        requestSeq++;
        lastExternalRequestId = -1;
        worker.removeCallbacksAndMessages(null);
        callback.onCompletions(Collections.emptyList(), 0, "");
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

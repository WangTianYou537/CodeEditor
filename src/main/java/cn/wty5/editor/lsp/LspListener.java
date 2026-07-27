package cn.wty5.editor.lsp;

import cn.wty5.editor.complete.CompletionItem;

import java.util.List;

/**
 * UI-thread callbacks from {@link LspClient}. Implementations must be fast —
 * heavy work belongs off the main thread before the client posts here.
 */
public interface LspListener {

    /** Server finished {@code initialize} / {@code initialized} handshake. */
    default void onLspReady() {}

    /** Connection dropped or handshake failed. {@code message} is human-readable. */
    default void onLspClosed(String message) {}

    /**
     * Fresh diagnostics for {@code uri}. Replaces any previous set for that
     * document; an empty list clears underlines.
     */
    default void onDiagnostics(String uri, List<Diagnostic> diagnostics) {}

    /**
     * Completion response for a prior {@link LspClient#requestCompletion} call.
     * {@code requestId} matches the value returned by that call so stale
     * replies can be dropped. {@code items} is never null.
     */
    default void onLspCompletions(int requestId, List<CompletionItem> items) {}
}

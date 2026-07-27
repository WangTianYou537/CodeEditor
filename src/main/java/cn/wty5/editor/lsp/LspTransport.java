package cn.wty5.editor.lsp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bidirectional byte channel used by {@link LspClient}. Implementations wrap
 * stdio pipes, TCP sockets, WebSocket sessions or HTTP long-poll pairs; the
 * client only sees streams (or a {@link MessageTransport} for message-oriented
 * backends).
 */
public interface LspTransport extends Closeable {

    /** Bytes from the language server (client reads). */
    InputStream getInputStream();

    /** Bytes to the language server (client writes). */
    OutputStream getOutputStream();

    /** Human-readable label for logs / onLspClosed messages. */
    default String describe() {
        return getClass().getSimpleName();
    }

    /**
     * Optional message-oriented transport (WebSocket / HTTP). When non-null
     * the client skips Content-Length framing and uses
     * {@link MessageTransport#send(String)} / the inbound queue instead.
     */
    default MessageTransport asMessages() {
        return null;
    }

    /**
     * Frame-oriented channel: each {@code send} is one JSON-RPC message, each
     * {@link #receive()} returns one. Used by WebSocket and HTTP transports
     * where the carrier already provides message boundaries.
     */
    interface MessageTransport {
        void send(String json) throws IOException;

        /**
         * Block until the next server→client JSON-RPC message arrives, or
         * return {@code null} on clean EOF.
         */
        String receive() throws IOException;

        void close() throws IOException;
    }
}

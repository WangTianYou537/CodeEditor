package cn.wty5.editor.lsp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds an {@link LspTransport} from an {@link LspConfig}. Pure JDK — no
 * external WebSocket/HTTP libraries; implements a minimal RFC 6455 client and
 * an HTTP POST + optional SSE reader sufficient for LSP-over-HTTP gateways
 * (e.g. vscode-ws-jsonrpc proxies, lsp-ws-proxy, custom bridges).
 */
public final class LspConnector {

    private LspConnector() {}

    public static LspTransport connect(LspConfig config) throws IOException {
        if (config == null || !config.isConfigured()) {
            throw new IOException("LSP config missing or disabled");
        }
        switch (config.transport) {
            case STDIO:
                return connectStdio(config);
            case TCP:
            case SOCKET:
                return connectTcp(config);
            case WEBSOCKET:
                return connectWebSocket(config);
            case HTTP:
                return connectHttp(config);
            default:
                throw new IOException("unsupported transport: " + config.transport);
        }
    }

    // ------------------------------------------------------------------
    // stdio
    // ------------------------------------------------------------------

    private static LspTransport connectStdio(LspConfig config) throws IOException {
        if (config.command.isEmpty()) {
            throw new IOException("stdio LSP requires a command");
        }
        ProcessBuilder pb = new ProcessBuilder(config.command);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (config.cwd != null && !config.cwd.isEmpty()) {
            File dir = new File(config.cwd);
            if (dir.isDirectory()) pb.directory(dir);
        }
        if (config.env != null) {
            pb.environment().putAll(config.env);
        }
        Process process = pb.start();
        return new ProcessTransport(process);
    }

    static final class ProcessTransport implements LspTransport {
        private final Process process;
        private final InputStream in;
        private final OutputStream out;

        ProcessTransport(Process process) {
            this.process = process;
            this.in = new BufferedInputStream(process.getInputStream());
            this.out = new BufferedOutputStream(process.getOutputStream());
        }

        @Override public InputStream getInputStream() { return in; }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public String describe() {
            return "stdio:" + process.toString();
        }

        @Override
        public void close() {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
            try { process.destroy(); } catch (Exception ignored) {}
            try { process.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // TCP / raw socket
    // ------------------------------------------------------------------

    private static LspTransport connectTcp(LspConfig config) throws IOException {
        String host = config.host == null ? "127.0.0.1" : config.host;
        if (config.port <= 0) throw new IOException("tcp LSP requires a port");
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, config.port), config.connectTimeoutMs);
        socket.setTcpNoDelay(true);
        return new SocketTransport(socket, host, config.port);
    }

    static final class SocketTransport implements LspTransport {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String label;

        SocketTransport(Socket socket, String host, int port) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());
            this.label = "tcp://" + host + ":" + port;
        }

        @Override public InputStream getInputStream() { return in; }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public String describe() { return label; }

        @Override
        public void close() {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // WebSocket (RFC 6455, text frames, no extensions)
    // ------------------------------------------------------------------

    private static LspTransport connectWebSocket(LspConfig config) throws IOException {
        if (config.url == null || config.url.isEmpty()) {
            throw new IOException("websocket LSP requires url");
        }
        URI uri = URI.create(config.url);
        String scheme = uri.getScheme() == null ? "ws" : uri.getScheme().toLowerCase();
        boolean secure = "wss".equals(scheme);
        if (!"ws".equals(scheme) && !secure) {
            throw new IOException("websocket url must be ws:// or wss://: " + config.url);
        }
        if (secure) {
            // Keep the surface honest: pure-JDK wss needs SSL plumbing we don't
            // want to half-implement. Callers can terminate TLS externally.
            throw new IOException("wss:// is not supported yet; use ws:// or an stunnel");
        }
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), config.connectTimeoutMs);
        socket.setTcpNoDelay(true);
        InputStream rawIn = socket.getInputStream();
        OutputStream rawOut = socket.getOutputStream();

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String handshake = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (port == 80 ? "" : ":" + port) + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        rawOut.write(handshake.getBytes(StandardCharsets.US_ASCII));
        rawOut.flush();

        String status = readHttpLine(rawIn);
        if (status == null || !status.contains("101")) {
            socket.close();
            throw new IOException("websocket handshake failed: " + status);
        }
        // Drain headers.
        while (true) {
            String line = readHttpLine(rawIn);
            if (line == null || line.isEmpty()) break;
        }

        WebSocketSession session = new WebSocketSession(socket, rawIn, rawOut, config.url);
        return session;
    }

    static final class WebSocketSession implements LspTransport, LspTransport.MessageTransport {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String label;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        // Stream adapters so LspClient can still speak Content-Length if it
        // wants to; primarily we expose asMessages().
        private final MessagePipe pipe = new MessagePipe();

        WebSocketSession(Socket socket, InputStream in, OutputStream out, String label) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.label = label;
        }

        @Override public InputStream getInputStream() { return pipe.input; }
        @Override public OutputStream getOutputStream() { return pipe.output; }
        @Override public MessageTransport asMessages() { return this; }
        @Override public String describe() { return "ws:" + label; }

        @Override
        public void send(String json) throws IOException {
            if (closed.get()) throw new IOException("websocket closed");
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            writeFrame(payload, /*opcode text*/ 0x1);
        }

        @Override
        public String receive() throws IOException {
            while (!closed.get()) {
                Frame f = readFrame();
                if (f == null) return null;
                if (f.opcode == 0x8) { // close
                    closed.set(true);
                    return null;
                }
                if (f.opcode == 0x9) { // ping → pong
                    writeFrame(f.payload, 0xA);
                    continue;
                }
                if (f.opcode == 0xA) continue; // pong
                if (f.opcode == 0x1 || f.opcode == 0x2) {
                    return new String(f.payload, StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { writeFrame(new byte[0], 0x8); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
            pipe.close();
        }

        private void writeFrame(byte[] payload, int opcode) throws IOException {
            // Client frames MUST be masked (RFC 6455 §5.1).
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 14);
            buf.write(0x80 | (opcode & 0x0F)); // FIN + opcode
            int len = payload.length;
            if (len < 126) {
                buf.write(0x80 | len);
            } else if (len < 65536) {
                buf.write(0x80 | 126);
                buf.write((len >>> 8) & 0xFF);
                buf.write(len & 0xFF);
            } else {
                buf.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    buf.write((int) ((len >>> (i * 8)) & 0xFF));
                }
            }
            buf.write(mask);
            for (int i = 0; i < payload.length; i++) {
                buf.write(payload[i] ^ mask[i & 3]);
            }
            synchronized (out) {
                out.write(buf.toByteArray());
                out.flush();
            }
        }

        private Frame readFrame() throws IOException {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) return null;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                int b2 = in.read(), b3 = in.read();
                if (b2 < 0 || b3 < 0) return null;
                len = ((b2 & 0xFF) << 8) | (b3 & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b < 0) return null;
                    len = (len << 8) | (b & 0xFF);
                }
            }
            if (len > 16 * 1024 * 1024) {
                throw new IOException("websocket frame too large: " + len);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            if (len > 0) readFully(in, payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            return new Frame(opcode, payload);
        }

        private static final class Frame {
            final int opcode;
            final byte[] payload;
            Frame(int opcode, byte[] payload) {
                this.opcode = opcode;
                this.payload = payload;
            }
        }

        /**
         * Adapts message send/receive to InputStream/OutputStream by re-framing
         * with Content-Length so a stream-oriented client still works.
         */
        private final class MessagePipe {
            final BlockingQueue<byte[]> inbound = new ArrayBlockingQueue<>(64);
            final InputStream input = new InputStream() {
                private byte[] cur;
                private int pos;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : (one[0] & 0xFF);
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (cur == null || pos >= cur.length) {
                        try {
                            // Pump websocket → Content-Length buffer.
                            String msg = WebSocketSession.this.receive();
                            if (msg == null) return -1;
                            byte[] body = msg.getBytes(StandardCharsets.UTF_8);
                            byte[] header = ("Content-Length: " + body.length
                                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                            cur = new byte[header.length + body.length];
                            System.arraycopy(header, 0, cur, 0, header.length);
                            System.arraycopy(body, 0, cur, header.length, body.length);
                            pos = 0;
                        } catch (IOException e) {
                            return -1;
                        }
                    }
                    int n = Math.min(len, cur.length - pos);
                    System.arraycopy(cur, pos, b, off, n);
                    pos += n;
                    return n;
                }
            };
            final OutputStream output = new OutputStream() {
                private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                @Override public synchronized void write(int b) { buf.write(b); }
                @Override public synchronized void write(byte[] b, int off, int len) {
                    buf.write(b, off, len);
                }
                @Override public synchronized void flush() throws IOException {
                    // Parse out Content-Length framed messages and forward each
                    // as a websocket text frame.
                    byte[] data = buf.toByteArray();
                    buf.reset();
                    int i = 0;
                    while (i < data.length) {
                        // Find header end.
                        int headerEnd = indexOf(data, i, new byte[]{'\r','\n','\r','\n'});
                        if (headerEnd < 0) {
                            // Incomplete — stash back.
                            buf.write(data, i, data.length - i);
                            return;
                        }
                        String header = new String(data, i, headerEnd - i,
                                StandardCharsets.US_ASCII);
                        int contentLength = -1;
                        for (String line : header.split("\r\n")) {
                            int c = line.indexOf(':');
                            if (c > 0 && line.substring(0, c).trim()
                                    .equalsIgnoreCase("Content-Length")) {
                                contentLength = Integer.parseInt(line.substring(c + 1).trim());
                            }
                        }
                        int bodyStart = headerEnd + 4;
                        if (contentLength < 0 || bodyStart + contentLength > data.length) {
                            buf.write(data, i, data.length - i);
                            return;
                        }
                        String json = new String(data, bodyStart, contentLength,
                                StandardCharsets.UTF_8);
                        WebSocketSession.this.send(json);
                        i = bodyStart + contentLength;
                    }
                }
                @Override public void close() throws IOException { flush(); }
            };
            void close() { /* queues abandoned */ }
        }
    }

    // ------------------------------------------------------------------
    // HTTP + optional SSE
    // ------------------------------------------------------------------

    private static LspTransport connectHttp(LspConfig config) throws IOException {
        if (config.url == null || config.url.isEmpty()) {
            throw new IOException("http LSP requires url");
        }
        String postUrl = config.url;
        String sse = config.sseUrl;
        if (sse == null || sse.isEmpty()) {
            // Conventional companion endpoint.
            sse = postUrl.endsWith("/") ? postUrl + "sse" : postUrl + "/sse";
        }
        return new HttpTransport(postUrl, sse, config.connectTimeoutMs);
    }

    /**
     * LSP-over-HTTP: every client→server JSON-RPC message is an HTTP POST of
     * the raw JSON body (or Content-Length framed — we accept either on the
     * response). Server→client traffic arrives on an SSE stream where each
     * {@code data:} line is one JSON-RPC message. If the SSE endpoint is
     * unreachable we fall back to reading the POST response body only
     * (request/response style, no unsolicited diagnostics).
     */
    static final class HttpTransport implements LspTransport, LspTransport.MessageTransport {
        private final String postUrl;
        private final String sseUrl;
        private final int timeoutMs;
        private final BlockingQueue<String> inbound = new ArrayBlockingQueue<>(256);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread sseThread;
        private final MessageStreamAdapter adapter = new MessageStreamAdapter(this);

        HttpTransport(String postUrl, String sseUrl, int timeoutMs) {
            this.postUrl = postUrl;
            this.sseUrl = sseUrl;
            this.timeoutMs = timeoutMs;
            this.sseThread = new Thread(this::sseLoop, "lsp-http-sse");
            this.sseThread.setDaemon(true);
            this.sseThread.start();
        }

        @Override public InputStream getInputStream() { return adapter.input; }
        @Override public OutputStream getOutputStream() { return adapter.output; }
        @Override public MessageTransport asMessages() { return this; }
        @Override public String describe() { return "http:" + postUrl; }

        @Override
        public void send(String json) throws IOException {
            if (closed.get()) throw new IOException("http transport closed");
            HttpURLConnection conn = (HttpURLConnection) new URL(postUrl).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/vscode-jsonrpc; charset=utf-8");
            conn.setRequestProperty("Accept", "application/vscode-jsonrpc, application/json");
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            // Many bridges expect bare JSON; some want Content-Length framing.
            // Send bare JSON — bridges that need framing typically re-frame.
            conn.setRequestProperty("Content-Length", Integer.toString(body.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream resp = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (resp != null) {
                byte[] raw = readAll(resp);
                resp.close();
                if (raw.length > 0) {
                    String text = new String(raw, StandardCharsets.UTF_8).trim();
                    // Response may be bare JSON, Content-Length framed, or an
                    // array of messages. Push each JSON value we can find.
                    enqueueJsonPayloads(text);
                }
            }
            conn.disconnect();
        }

        @Override
        public String receive() throws IOException {
            try {
                while (!closed.get()) {
                    String m = inbound.poll(500, TimeUnit.MILLISECONDS);
                    if (m != null) return m;
                    if (closed.get()) return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return null;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            sseThread.interrupt();
            inbound.clear();
        }

        private void sseLoop() {
            while (!closed.get()) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(sseUrl).openConnection();
                    conn.setConnectTimeout(timeoutMs);
                    conn.setReadTimeout(0); // long poll
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "text/event-stream, application/json");
                    int code = conn.getResponseCode();
                    if (code >= 400) {
                        // SSE unavailable — sleep and retry; POSTs still work.
                        sleep(2000);
                        continue;
                    }
                    try (InputStream in = conn.getInputStream()) {
                        StringBuilder data = new StringBuilder();
                        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
                        int c;
                        while (!closed.get() && (c = in.read()) >= 0) {
                            if (c == '\n') {
                                String line = new String(lineBuf.toByteArray(),
                                        StandardCharsets.UTF_8);
                                lineBuf.reset();
                                if (line.endsWith("\r")) {
                                    line = line.substring(0, line.length() - 1);
                                }
                                if (line.startsWith("data:")) {
                                    String payload = line.substring(5);
                                    if (payload.startsWith(" ")) payload = payload.substring(1);
                                    if (data.length() > 0) data.append('\n');
                                    data.append(payload);
                                } else if (line.isEmpty()) {
                                    if (data.length() > 0) {
                                        inbound.offer(data.toString());
                                        data.setLength(0);
                                    }
                                }
                                // ignore event:/id:/retry: lines
                            } else {
                                lineBuf.write(c);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (closed.get()) return;
                    sleep(1500);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }

        private void enqueueJsonPayloads(String text) {
            String t = text.trim();
            if (t.isEmpty()) return;
            // Content-Length framed bundle?
            if (t.regionMatches(true, 0, "Content-Length:", 0, 15)
                    || t.regionMatches(true, 0, "content-length:", 0, 15)) {
                try {
                    int i = 0;
                    byte[] data = t.getBytes(StandardCharsets.UTF_8);
                    // Re-parse properly from original bytes is safer; fall through
                    // to bare JSON on failure.
                    while (i < t.length()) {
                        int sep = t.indexOf("\r\n\r\n", i);
                        if (sep < 0) break;
                        String header = t.substring(i, sep);
                        int contentLength = -1;
                        for (String line : header.split("\r\n")) {
                            int c = line.indexOf(':');
                            if (c > 0 && line.substring(0, c).trim()
                                    .equalsIgnoreCase("Content-Length")) {
                                contentLength = Integer.parseInt(line.substring(c + 1).trim());
                            }
                        }
                        int bodyStart = sep + 4;
                        if (contentLength < 0 || bodyStart + contentLength > t.length()) break;
                        inbound.offer(t.substring(bodyStart, bodyStart + contentLength));
                        i = bodyStart + contentLength;
                    }
                    return;
                } catch (Exception ignored) {
                }
            }
            inbound.offer(t);
        }

        private static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Shared stream adapter: OutputStream buffers Content-Length frames and
     * hands each JSON body to {@link LspTransport.MessageTransport#send};
     * InputStream re-frames {@link LspTransport.MessageTransport#receive}
     * results as Content-Length so {@link LspClient}'s stream reader works.
     */
    static final class MessageStreamAdapter {
        private final LspTransport.MessageTransport mt;
        final InputStream input;
        final OutputStream output;

        MessageStreamAdapter(LspTransport.MessageTransport mt) {
            this.mt = mt;
            this.input = new InputStream() {
                private byte[] cur;
                private int pos;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : (one[0] & 0xFF);
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (cur == null || pos >= cur.length) {
                        String msg = mt.receive();
                        if (msg == null) return -1;
                        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
                        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                        cur = new byte[header.length + body.length];
                        System.arraycopy(header, 0, cur, 0, header.length);
                        System.arraycopy(body, 0, cur, header.length, body.length);
                        pos = 0;
                    }
                    int n = Math.min(len, cur.length - pos);
                    System.arraycopy(cur, pos, b, off, n);
                    pos += n;
                    return n;
                }
            };
            this.output = new OutputStream() {
                private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                @Override public synchronized void write(int b) { buf.write(b); }
                @Override public synchronized void write(byte[] b, int off, int len) {
                    buf.write(b, off, len);
                }
                @Override public synchronized void flush() throws IOException {
                    byte[] data = buf.toByteArray();
                    buf.reset();
                    int i = 0;
                    while (i < data.length) {
                        int headerEnd = indexOf(data, i, new byte[]{'\r','\n','\r','\n'});
                        if (headerEnd < 0) {
                            buf.write(data, i, data.length - i);
                            return;
                        }
                        String header = new String(data, i, headerEnd - i,
                                StandardCharsets.US_ASCII);
                        int contentLength = -1;
                        for (String line : header.split("\r\n")) {
                            int c = line.indexOf(':');
                            if (c > 0 && line.substring(0, c).trim()
                                    .equalsIgnoreCase("Content-Length")) {
                                contentLength = Integer.parseInt(line.substring(c + 1).trim());
                            }
                        }
                        int bodyStart = headerEnd + 4;
                        if (contentLength < 0 || bodyStart + contentLength > data.length) {
                            buf.write(data, i, data.length - i);
                            return;
                        }
                        String json = new String(data, bodyStart, contentLength,
                                StandardCharsets.UTF_8);
                        mt.send(json);
                        i = bodyStart + contentLength;
                    }
                }
                @Override public void close() throws IOException { flush(); }
            };
        }
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (buf.size() == 0) return null;
                break;
            }
            if (c == '\n') break;
            if (prev == '\r') buf.write(prev);
            if (c != '\r') {
                buf.write(c);
                prev = -1;
            } else {
                prev = c;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII.name());
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, int from, byte[] pattern) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

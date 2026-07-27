package cn.wty5.editor.lsp;

import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.lang.MiniJson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Language Server Protocol (LSP 3.16-ish) client over stdio or any
 * pair of streams.
 *
 * <p>Supported surface:
 * <ul>
 *   <li>initialize / initialized handshake</li>
 *   <li>textDocument/didOpen, didChange (full sync), didClose</li>
 *   <li>textDocument/completion</li>
 *   <li>textDocument/publishDiagnostics (server → client)</li>
 *   <li>shutdown / exit</li>
 * </ul>
 *
 * <p>Pure Java — no Android dependency. The host (typically
 * {@link cn.wty5.editor.view.CodeEditorView}) posts listener callbacks onto
 * the UI thread itself; this class invokes {@link LspListener} on a
 * background reader thread, so the listener must hop threads if needed.
 *
 * <p>Wire format is LSP's {@code Content-Length}-framed JSON-RPC 2.0.
 */
public final class LspClient {

    public interface UiScheduler {
        /** Run {@code r} on the thread the host wants listener calls on (UI). */
        void post(Runnable r);
    }

    private final List<LspListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private volatile OutputStream out;
    private volatile InputStream in;
    private volatile Process process;
    private volatile LspTransport transport;
    private volatile Thread readerThread;
    private volatile UiScheduler uiScheduler = Runnable::run;
    private volatile String rootUri = "file:///";
    private volatile Object initializationOptions;
    private volatile LspConfig activeConfig;
    private final ExecutorService writerPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "lsp-write");
            t.setDaemon(true);
            return t;
        }
    });

    /** Optional hop onto the UI thread before listener fan-out. Default = inline. */
    public void setUiScheduler(UiScheduler scheduler) {
        this.uiScheduler = scheduler == null ? Runnable::run : scheduler;
    }

    public void addListener(LspListener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(LspListener l) {
        listeners.remove(l);
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isReady() {
        return ready.get();
    }

    /**
     * Connect using a declarative {@link LspConfig} (stdio / tcp / http /
     * websocket). Placeholders in the config should already have been
     * resolved via {@link LspConfig#resolve(LspConfig.LspWorkspace)}.
     */
    public void start(LspConfig config) throws IOException {
        if (config == null || !config.isConfigured()) {
            throw new IOException("LSP config missing or disabled");
        }
        LspTransport t = LspConnector.connect(config);
        this.activeConfig = config;
        this.initializationOptions = config.initializationOptions;
        String root = config.rootUri;
        if (root == null || root.isEmpty()) root = "file:///";
        if (t instanceof LspConnector.ProcessTransport) {
            // Keep a handle so stop() can destroy the subprocess.
            // ProcessTransport closes the process itself on close().
        }
        start(t, root);
    }

    /**
     * Start a language server as a subprocess and speak LSP over its stdio.
     * {@code command[0]} is the executable; the rest are arguments.
     */
    public void startProcess(List<String> command, String rootUri) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command required");
        }
        LspConfig cfg = LspConfig.builder()
                .transport(LspConfig.Transport.STDIO)
                .command(command)
                .rootUri(rootUri)
                .build();
        start(cfg);
    }

    /** Speak LSP over an already-open {@link LspTransport}. */
    public void start(LspTransport transport, String rootUri) {
        if (transport == null) throw new IllegalArgumentException("transport required");
        stop();
        this.transport = transport;
        this.in = new BufferedInputStream(transport.getInputStream());
        this.out = new BufferedOutputStream(transport.getOutputStream());
        this.rootUri = rootUri == null || rootUri.isEmpty() ? "file:///" : rootUri;
        running.set(true);
        ready.set(false);
        readerThread = new Thread(this::readLoop, "lsp-read");
        readerThread.setDaemon(true);
        readerThread.start();
        sendInitialize();
    }

    /** Speak LSP over arbitrary streams (e.g. a TCP socket). */
    public void start(InputStream input, OutputStream output, String rootUri) {
        start(new StreamTransport(input, output), rootUri);
    }

    /** Optional {@code initialize.initializationOptions} payload. */
    public void setInitializationOptions(Object options) {
        this.initializationOptions = options;
    }

    public LspConfig getActiveConfig() {
        return activeConfig;
    }

    /** Thin wrapper so bare streams look like an {@link LspTransport}. */
    private static final class StreamTransport implements LspTransport {
        private final InputStream in;
        private final OutputStream out;
        StreamTransport(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }
        @Override public InputStream getInputStream() { return in; }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public void close() {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** Open (or re-open) a document on the server. */
    public void didOpen(String uri, String languageId, String text, int version) {
        if (!running.get()) return;
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        textDocument.put("languageId", languageId == null ? "plaintext" : languageId);
        textDocument.put("version", version);
        textDocument.put("text", text == null ? "" : text);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        notify("textDocument/didOpen", params);
    }

    /** Full-document change notification (TextDocumentSyncKind.Full). */
    public void didChange(String uri, String text, int version) {
        if (!running.get()) return;
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        textDocument.put("version", version);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("text", text == null ? "" : text);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("contentChanges", Collections.singletonList(change));
        notify("textDocument/didChange", params);
    }

    public void didClose(String uri) {
        if (!running.get()) return;
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        notify("textDocument/didClose", params);
    }

    /**
     * Request completions at a 0-based (line, character) position.
     * @return request id used to match {@link LspListener#onLspCompletions}
     */
    public int requestCompletion(String uri, int line, int character) {
        if (!running.get()) return -1;
        Map<String, Object> textDocument = new LinkedHashMap<>();
        textDocument.put("uri", uri);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("line", line);
        position.put("character", character);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDocument);
        params.put("position", position);
        // triggerKind = Invoked
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("triggerKind", 1);
        params.put("context", context);
        return request("textDocument/completion", params, Pending.Kind.COMPLETION);
    }

    /** Graceful shutdown; also destroys any subprocess we spawned. */
    public void stop() {
        if (!running.getAndSet(false)) {
            // Still make sure a leftover process is reaped.
            destroyProcess();
            return;
        }
        ready.set(false);
        try {
            // Best-effort; ignore failures on a half-closed pipe.
            request("shutdown", new LinkedHashMap<String, Object>(), Pending.Kind.SHUTDOWN);
            notify("exit", null);
        } catch (Exception ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        LspTransport t = transport;
        transport = null;
        if (t != null) {
            try { t.close(); } catch (Exception ignored) {}
        }
        if (readerThread != null) {
            try { readerThread.interrupt(); } catch (Exception ignored) {}
        }
        destroyProcess();
        activeConfig = null;
        pending.clear();
        deliver(l -> l.onLspClosed("stopped"));
    }

    public void shutdownPool() {
        writerPool.shutdownNow();
    }

    // ------------------------------------------------------------------
    // JSON-RPC
    // ------------------------------------------------------------------

    private void sendInitialize() {
        Map<String, Object> caps = new LinkedHashMap<>();
        Map<String, Object> textDoc = new LinkedHashMap<>();
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("openClose", Boolean.TRUE);
        sync.put("change", 1); // Full
        textDoc.put("synchronization", sync);
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("dynamicRegistration", Boolean.FALSE);
        Map<String, Object> completionItem = new LinkedHashMap<>();
        completionItem.put("snippetSupport", Boolean.TRUE);
        completion.put("completionItem", completionItem);
        textDoc.put("completion", completion);
        Map<String, Object> publishDiag = new LinkedHashMap<>();
        publishDiag.put("relatedInformation", Boolean.FALSE);
        textDoc.put("publishDiagnostics", publishDiag);
        caps.put("textDocument", textDoc);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", null);
        params.put("rootUri", rootUri);
        params.put("capabilities", caps);
        params.put("clientInfo", mapOf("name", "CodeEditor", "version", "1.0"));
        Object initOpts = initializationOptions;
        if (initOpts != null) {
            params.put("initializationOptions", initOpts);
        }
        request("initialize", params, Pending.Kind.INITIALIZE);
    }

    private int request(String method, Map<String, Object> params, Pending.Kind kind) {
        int id = nextId.getAndIncrement();
        pending.put(id, new Pending(kind));
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        writeMessage(msg);
        return id;
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        writeMessage(msg);
    }

    private void writeMessage(Map<String, Object> msg) {
        final String body = MiniJson.stringify(msg);
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writerPool.execute(() -> {
            if (!running.get() && !"exit".equals(msg.get("method"))
                    && !"shutdown".equals(msg.get("method"))) {
                return;
            }
            synchronized (writeLock) {
                OutputStream o = out;
                if (o == null) return;
                try {
                    String header = "Content-Length: " + bytes.length + "\r\n\r\n";
                    o.write(header.getBytes(StandardCharsets.US_ASCII));
                    o.write(bytes);
                    o.flush();
                } catch (IOException e) {
                    running.set(false);
                    deliver(l -> l.onLspClosed("write failed: " + e.getMessage()));
                }
            }
        });
    }

    private void readLoop() {
        try {
            while (running.get()) {
                String body = readFrame(in);
                if (body == null) break;
                handleMessage(body);
            }
        } catch (IOException e) {
            if (running.get()) {
                deliver(l -> l.onLspClosed("read failed: " + e.getMessage()));
            }
        } finally {
            running.set(false);
            ready.set(false);
        }
    }

    private static String readFrame(InputStream in) throws IOException {
        int contentLength = -1;
        // Headers end at a blank line. LSP permits either \r\n or \n.
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(64);
        int state = 0; // 0=normal, 1=saw \r, 2=saw \n (maybe end), 3=saw \n\r, done on \n\n or \r\n\r\n
        // Simpler: read line by line.
        while (true) {
            String line = readLine(in);
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }
        if (contentLength < 0) {
            throw new IOException("LSP frame missing Content-Length");
        }
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) throw new IOException("EOF in LSP body");
            off += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (buf.size() == 0) return null;
                break;
            }
            if (c == '\n') {
                // drop trailing \r
                break;
            }
            if (prev == '\r') {
                buf.write(prev);
            }
            if (c != '\r') {
                buf.write(c);
                prev = -1;
            } else {
                prev = c;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII.name());
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String body) {
        final Map<String, Object> msg;
        try {
            msg = MiniJson.parseObject(body);
        } catch (RuntimeException e) {
            return;
        }
        Object idObj = msg.get("id");
        String method = msg.containsKey("method") ? String.valueOf(msg.get("method")) : null;
        Object result = msg.get("result");
        Object error = msg.get("error");
        Object params = msg.get("params");

        if (method != null && idObj == null) {
            // Notification from server.
            if ("textDocument/publishDiagnostics".equals(method) && params instanceof Map) {
                handlePublishDiagnostics((Map<String, Object>) params);
            }
            return;
        }

        if (idObj != null && method == null) {
            // Response to our request.
            int id = toInt(idObj, -1);
            Pending p = pending.remove(id);
            if (p == null) return;
            if (error != null) {
                if (p.kind == Pending.Kind.INITIALIZE) {
                    deliver(l -> l.onLspClosed("initialize error: " + error));
                }
                return;
            }
            switch (p.kind) {
                case INITIALIZE:
                    ready.set(true);
                    notify("initialized", new LinkedHashMap<String, Object>());
                    deliver(LspListener::onLspReady);
                    break;
                case COMPLETION:
                    List<CompletionItem> items = parseCompletionResult(result);
                    deliver(l -> l.onLspCompletions(id, items));
                    break;
                case SHUTDOWN:
                default:
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePublishDiagnostics(Map<String, Object> params) {
        String uri = params.get("uri") == null ? "" : String.valueOf(params.get("uri"));
        Object raw = params.get("diagnostics");
        List<Diagnostic> list = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> d = (Map<String, Object>) o;
                Map<String, Object> range = d.get("range") instanceof Map
                        ? (Map<String, Object>) d.get("range") : null;
                int sl = 0, sc = 0, el = 0, ec = 0;
                if (range != null) {
                    Map<String, Object> start = range.get("start") instanceof Map
                            ? (Map<String, Object>) range.get("start") : null;
                    Map<String, Object> end = range.get("end") instanceof Map
                            ? (Map<String, Object>) range.get("end") : null;
                    if (start != null) {
                        sl = toInt(start.get("line"), 0);
                        sc = toInt(start.get("character"), 0);
                    }
                    if (end != null) {
                        el = toInt(end.get("line"), sl);
                        ec = toInt(end.get("character"), sc);
                    }
                }
                int severity = toInt(d.get("severity"), Diagnostic.SEVERITY_ERROR);
                String message = d.get("message") == null ? "" : String.valueOf(d.get("message"));
                String source = d.get("source") == null ? "" : String.valueOf(d.get("source"));
                String code = d.get("code") == null ? "" : String.valueOf(d.get("code"));
                list.add(new Diagnostic(sl, sc, el, ec, severity, message, source, code));
            }
        }
        final List<Diagnostic> frozen = Collections.unmodifiableList(list);
        deliver(l -> l.onDiagnostics(uri, frozen));
    }

    @SuppressWarnings("unchecked")
    private static List<CompletionItem> parseCompletionResult(Object result) {
        if (result == null) return Collections.emptyList();
        List<Object> rawItems;
        if (result instanceof List) {
            rawItems = (List<Object>) result;
        } else if (result instanceof Map
                && ((Map<?, ?>) result).get("items") instanceof List) {
            rawItems = (List<Object>) ((Map<?, ?>) result).get("items");
        } else {
            return Collections.emptyList();
        }
        List<CompletionItem> out = new ArrayList<>(Math.min(rawItems.size(), 50));
        for (Object o : rawItems) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String label = m.get("label") == null ? "" : String.valueOf(m.get("label"));
            if (label.isEmpty()) continue;
            String insert;
            Object insertText = m.get("insertText");
            Object textEdit = m.get("textEdit");
            if (insertText != null) {
                insert = String.valueOf(insertText);
            } else if (textEdit instanceof Map
                    && ((Map<?, ?>) textEdit).get("newText") != null) {
                insert = String.valueOf(((Map<?, ?>) textEdit).get("newText"));
            } else {
                insert = label;
            }
            // LSP snippet (insertTextFormat = 2) uses $0 / ${0} — keep as-is;
            // the editor already understands a single $0 caret mark.
            insert = normalizeSnippet(insert);
            String detail = m.get("detail") == null ? "" : String.valueOf(m.get("detail"));
            if (detail.isEmpty() && m.get("kind") != null) {
                detail = kindLabel(toInt(m.get("kind"), 1));
            }
            CompletionItem.Kind kind = mapKind(toInt(m.get("kind"), 1));
            out.add(new CompletionItem(kind, label, insert, detail));
            if (out.size() >= 50) break;
        }
        return out;
    }

    /** Collapse ${0:foo} / $0 forms the editor already handles to a bare $0. */
    private static String normalizeSnippet(String insert) {
        if (insert == null) return "";
        // Convert ${0} / ${0:placeholder} → $0; leave other tabstops as text.
        String s = insert.replace("${0}", "$0");
        // ${0:xxx} → xxx$0  (placeholder kept, caret after)
        int idx;
        while ((idx = s.indexOf("${0:")) >= 0) {
            int end = s.indexOf('}', idx);
            if (end < 0) break;
            String inner = s.substring(idx + 4, end);
            s = s.substring(0, idx) + inner + "$0" + s.substring(end + 1);
        }
        return s;
    }

    private static CompletionItem.Kind mapKind(int lspKind) {
        // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionItemKind
        switch (lspKind) {
            case 2:  // Method
            case 3:  // Function
            case 4:  // Constructor
                return CompletionItem.Kind.METHOD;
            case 5:  // Field
            case 6:  // Variable
            case 10: // Property
                return CompletionItem.Kind.VARIABLE;
            case 7:  // Class
            case 8:  // Interface
            case 9:  // Module
            case 22: // Struct
            case 25: // TypeParameter
                return CompletionItem.Kind.TYPE;
            case 14: // Keyword
                return CompletionItem.Kind.KEYWORD;
            case 15: // Snippet
                return CompletionItem.Kind.SNIPPET;
            default:
                return CompletionItem.Kind.IDENTIFIER;
        }
    }

    private static String kindLabel(int lspKind) {
        switch (lspKind) {
            case 2: return "method";
            case 3: return "function";
            case 4: return "constructor";
            case 5: return "field";
            case 6: return "variable";
            case 7: return "class";
            case 8: return "interface";
            case 9: return "module";
            case 10: return "property";
            case 14: return "keyword";
            case 15: return "snippet";
            default: return "lsp";
        }
    }

    private void deliver(ListenerCall call) {
        UiScheduler sched = uiScheduler;
        sched.post(() -> {
            for (LspListener l : listeners) {
                try {
                    call.invoke(l);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private interface ListenerCall {
        void invoke(LspListener l);
    }

    private void destroyProcess() {
        Process p = process;
        process = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) {}
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static final class Pending {
        enum Kind { INITIALIZE, COMPLETION, SHUTDOWN }
        final Kind kind;
        Pending(Kind kind) { this.kind = kind; }
    }
}

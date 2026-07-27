import cn.wty5.editor.complete.CompletionItem;
import cn.wty5.editor.lang.MiniJson;
import cn.wty5.editor.lsp.Diagnostic;
import cn.wty5.editor.lsp.LspClient;
import cn.wty5.editor.lsp.LspListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JDK coverage for MiniJson.stringify and the LSP client framing /
 * handshake / diagnostics / completion path. Speaks to an in-process fake
 * language server over piped streams — no Android, no real subprocess.
 */
public final class LspTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testMiniJsonRoundTrip();
        testInitializeHandshake();
        testPublishDiagnostics();
        testCompletionResponse();
        if (failed > 0) {
            System.out.println("FAILED " + failed + " / " + (passed + failed));
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void testMiniJsonRoundTrip() {
        String src = "{\"a\":1,\"b\":[true,null,\"x\\\"y\"],\"c\":{\"d\":-2.5}}";
        Object parsed = MiniJson.parse(src);
        String out = MiniJson.stringify(parsed);
        Object reparsed = MiniJson.parse(out);
        check("stringify round-trip preserves structure",
                MiniJson.stringify(parsed).equals(MiniJson.stringify(reparsed)));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        check("parse number", ((Number) map.get("a")).intValue() == 1);
    }

    private static void testInitializeHandshake() throws Exception {
        FakeServer server = FakeServer.start();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> closed = new AtomicReference<>();
        LspClient client = new LspClient();
        client.setUiScheduler(Runnable::run);
        client.addListener(new LspListener() {
            @Override public void onLspReady() { ready.countDown(); }
            @Override public void onLspClosed(String message) { closed.set(message); }
        });
        client.start(server.clientIn, server.clientOut, "file:///tmp/proj");
        check("initialize completes", ready.await(2, TimeUnit.SECONDS));
        check("client reports ready", client.isReady());
        // Fake server should have seen initialize + initialized.
        check("server got initialize",
                server.awaitMethod("initialize", 2, TimeUnit.SECONDS));
        check("server got initialized",
                server.awaitMethod("initialized", 2, TimeUnit.SECONDS));
        client.stop();
        server.close();
    }

    private static void testPublishDiagnostics() throws Exception {
        FakeServer server = FakeServer.start();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch diagLatch = new CountDownLatch(1);
        AtomicReference<List<Diagnostic>> diags = new AtomicReference<>();
        LspClient client = new LspClient();
        client.setUiScheduler(Runnable::run);
        client.addListener(new LspListener() {
            @Override public void onLspReady() { ready.countDown(); }
            @Override public void onDiagnostics(String uri, List<Diagnostic> list) {
                diags.set(list);
                diagLatch.countDown();
            }
        });
        client.start(server.clientIn, server.clientOut, "file:///tmp/proj");
        check("ready before diag", ready.await(2, TimeUnit.SECONDS));

        client.didOpen("file:///tmp/proj/A.java", "java", "class A {\n", 1);
        server.awaitMethod("textDocument/didOpen", 2, TimeUnit.SECONDS);

        // Server publishes one error on line 0.
        server.sendNotification("textDocument/publishDiagnostics",
                "{"
              + "\"uri\":\"file:///tmp/proj/A.java\","
              + "\"diagnostics\":[{"
              +   "\"range\":{\"start\":{\"line\":0,\"character\":0},"
              +              "\"end\":{\"line\":0,\"character\":5}},"
              +   "\"severity\":1,"
              +   "\"message\":\"missing brace\","
              +   "\"source\":\"fake\""
              + "}]"
              + "}");
        check("diagnostics delivered", diagLatch.await(2, TimeUnit.SECONDS));
        List<Diagnostic> list = diags.get();
        check("one diagnostic", list != null && list.size() == 1);
        check("diag severity error",
                list != null && list.get(0).severity == Diagnostic.SEVERITY_ERROR);
        check("diag message",
                list != null && "missing brace".equals(list.get(0).message));
        client.stop();
        server.close();
    }

    private static void testCompletionResponse() throws Exception {
        FakeServer server = FakeServer.start();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch compLatch = new CountDownLatch(1);
        AtomicReference<List<CompletionItem>> items = new AtomicReference<>();
        LspClient client = new LspClient();
        client.setUiScheduler(Runnable::run);
        client.addListener(new LspListener() {
            @Override public void onLspReady() { ready.countDown(); }
            @Override public void onLspCompletions(int id, List<CompletionItem> list) {
                items.set(list);
                compLatch.countDown();
            }
        });
        client.start(server.clientIn, server.clientOut, "file:///tmp/proj");
        check("ready before completion", ready.await(2, TimeUnit.SECONDS));

        int reqId = client.requestCompletion("file:///tmp/proj/A.java", 0, 3);
        check("request id positive", reqId > 0);
        // Fake server answers any request whose method is textDocument/completion
        // with a fixed item list (see FakeServer).
        check("completions delivered", compLatch.await(2, TimeUnit.SECONDS));
        List<CompletionItem> list = items.get();
        check("got items", list != null && !list.isEmpty());
        check("label present",
                list != null && list.stream().anyMatch(i -> "println".equals(i.label)));
        client.stop();
        server.close();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    // ------------------------------------------------------------------
    // In-process fake language server
    // ------------------------------------------------------------------

    /**
     * Speaks LSP over a pair of piped streams. Handles initialize, echoes
     * initialized, and answers textDocument/completion with a canned list.
     * Other requests get a null result. Notifications are recorded by method.
     */
    private static final class FakeServer {
        final InputStream clientIn;   // client reads server output from here
        final OutputStream clientOut; // client writes requests into here
        private final PipedOutputStream serverOut;
        private final PipedInputStream serverIn;
        private final List<String> methods =
                Collections.synchronizedList(new ArrayList<>());
        private final Thread thread;
        private volatile boolean running = true;

        static FakeServer start() throws IOException {
            return new FakeServer();
        }

        private FakeServer() throws IOException {
            // clientOut → serverIn ; serverOut → clientIn
            PipedInputStream clientInPipe = new PipedInputStream(64 * 1024);
            PipedOutputStream serverOutPipe = new PipedOutputStream(clientInPipe);
            PipedInputStream serverInPipe = new PipedInputStream(64 * 1024);
            PipedOutputStream clientOutPipe = new PipedOutputStream(serverInPipe);
            this.clientIn = clientInPipe;
            this.clientOut = clientOutPipe;
            this.serverOut = serverOutPipe;
            this.serverIn = serverInPipe;
            this.thread = new Thread(this::loop, "fake-lsp");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void close() {
            running = false;
            try { serverOut.close(); } catch (IOException ignored) {}
            try { serverIn.close(); } catch (IOException ignored) {}
            try { clientIn.close(); } catch (IOException ignored) {}
            try { clientOut.close(); } catch (IOException ignored) {}
            try { thread.interrupt(); } catch (Exception ignored) {}
        }

        boolean awaitMethod(String method, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                synchronized (methods) {
                    if (methods.contains(method)) return true;
                    methods.wait(50);
                }
            }
            return methods.contains(method);
        }

        void sendNotification(String method, String paramsJson) throws IOException {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method
                    + "\",\"params\":" + paramsJson + "}";
            writeFrame(body);
        }

        private void loop() {
            try {
                while (running) {
                    String body = readFrame(serverIn);
                    if (body == null) break;
                    Map<String, Object> msg = MiniJson.parseObject(body);
                    String method = msg.get("method") == null
                            ? null : String.valueOf(msg.get("method"));
                    Object id = msg.get("id");
                    if (method != null) {
                        synchronized (methods) {
                            methods.add(method);
                            methods.notifyAll();
                        }
                    }
                    if (id != null && method != null) {
                        // Request — send a response.
                        String result;
                        if ("initialize".equals(method)) {
                            result = "{\"capabilities\":{"
                                    + "\"textDocumentSync\":1,"
                                    + "\"completionProvider\":{\"triggerCharacters\":[\".\"]}"
                                    + "}}";
                        } else if ("textDocument/completion".equals(method)) {
                            result = "[{\"label\":\"println\",\"kind\":3,"
                                    + "\"insertText\":\"println($0)\","
                                    + "\"detail\":\"PrintStream\"},"
                                    + "{\"label\":\"print\",\"kind\":3,"
                                    + "\"insertText\":\"print($0)\"}]";
                        } else if ("shutdown".equals(method)) {
                            result = "null";
                        } else {
                            result = "null";
                        }
                        String resp = "{\"jsonrpc\":\"2.0\",\"id\":"
                                + MiniJson.stringify(id)
                                + ",\"result\":" + result + "}";
                        writeFrame(resp);
                    }
                }
            } catch (IOException e) {
                // pipe closed — normal on shutdown
            }
        }

        private void writeFrame(String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String header = "Content-Length: " + bytes.length + "\r\n\r\n";
            synchronized (serverOut) {
                serverOut.write(header.getBytes(StandardCharsets.US_ASCII));
                serverOut.write(bytes);
                serverOut.flush();
            }
        }

        private static String readFrame(InputStream in) throws IOException {
            int contentLength = -1;
            while (true) {
                String line = readLine(in);
                if (line == null) return null;
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0
                        && line.substring(0, colon).trim()
                                .equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength < 0) throw new IOException("no Content-Length");
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) throw new IOException("EOF");
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
    }
}

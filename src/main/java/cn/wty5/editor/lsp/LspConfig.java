package cn.wty5.editor.lsp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative language-server connection settings, typically embedded in a
 * grammar JSON file under the {@code "lsp"} key or supplied by a
 * {@link cn.wty5.editor.plugin.LanguagePlugin}.
 *
 * <p>Supported transports:
 * <ul>
 *   <li>{@link Transport#STDIO} — spawn {@code command} and speak LSP on pipes</li>
 *   <li>{@link Transport#TCP} / {@link Transport#SOCKET} — raw TCP,
 *       Content-Length framed</li>
 *   <li>{@link Transport#WEBSOCKET} — JSON-RPC text frames over WebSocket</li>
 *   <li>{@link Transport#HTTP} — JSON-RPC over HTTP POST + optional SSE
 *       notification stream</li>
 * </ul>
 *
 * <p>String fields may contain placeholders expanded against a
 * {@link LspWorkspace} at connect time:
 * {@code ${workspaceFolder}}, {@code ${workspaceFolderUri}},
 * {@code ${workspaceFolderBasename}}, {@code ${file}}, {@code ${fileUri}},
 * {@code ${fileBasename}}, {@code ${languageId}}.
 */
public final class LspConfig {

    public enum Transport {
        STDIO, TCP, SOCKET, HTTP, WEBSOCKET;

        public static Transport parse(String raw) {
            if (raw == null || raw.isEmpty()) return STDIO;
            String s = raw.trim().toLowerCase(Locale.US);
            switch (s) {
                case "stdio":
                case "std":
                case "process":
                    return STDIO;
                case "tcp":
                    return TCP;
                case "socket":
                case "net":
                    return SOCKET;
                case "http":
                case "https":
                    return HTTP;
                case "ws":
                case "wss":
                case "websocket":
                case "web-socket":
                    return WEBSOCKET;
                default:
                    throw new IllegalArgumentException("unknown LSP transport: " + raw);
            }
        }
    }

    public final boolean enabled;
    public final Transport transport;
    /** Executable + args for {@link Transport#STDIO}. */
    public final List<String> command;
    /** Extra environment variables for stdio processes (null = inherit only). */
    public final Map<String, String> env;
    /** Working directory template for stdio processes. */
    public final String cwd;
    /**
     * Endpoint URL template for HTTP / WebSocket
     * (e.g. {@code http://127.0.0.1:2087/} or {@code ws://127.0.0.1:2087/lsp}).
     */
    public final String url;
    /** Host template for TCP/SOCKET. */
    public final String host;
    public final int port;
    /**
     * Optional SSE/event URL for {@link Transport#HTTP} server→client push.
     * Defaults to {@code url} with {@code /sse} appended when null.
     */
    public final String sseUrl;
    /** LSP {@code languageId} override; null → language name from the grammar. */
    public final String languageId;
    /** Root URI template passed to {@code initialize}; null → workspace folder. */
    public final String rootUri;
    /** Opaque JSON object forwarded as {@code initializationOptions}. */
    public final Object initializationOptions;
    /** Connect timeout in milliseconds (TCP / HTTP / WS). */
    public final int connectTimeoutMs;

    private LspConfig(Builder b) {
        this.enabled = b.enabled;
        this.transport = b.transport == null ? Transport.STDIO : b.transport;
        this.command = b.command == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(b.command));
        this.env = b.env == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.env));
        this.cwd = b.cwd;
        this.url = b.url;
        this.host = b.host == null ? "127.0.0.1" : b.host;
        this.port = b.port;
        this.sseUrl = b.sseUrl;
        this.languageId = b.languageId;
        this.rootUri = b.rootUri;
        this.initializationOptions = b.initializationOptions;
        this.connectTimeoutMs = b.connectTimeoutMs > 0 ? b.connectTimeoutMs : 10_000;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isConfigured() {
        if (!enabled) return false;
        switch (transport) {
            case STDIO:
                return !command.isEmpty();
            case TCP:
            case SOCKET:
                return port > 0 && host != null && !host.isEmpty();
            case HTTP:
            case WEBSOCKET:
                return url != null && !url.isEmpty();
            default:
                return false;
        }
    }

    /**
     * Resolve placeholders against a workspace. Returns a new config whose
     * string fields are concrete.
     */
    public LspConfig resolve(LspWorkspace ws) {
        if (ws == null) ws = LspWorkspace.EMPTY;
        Builder b = new Builder();
        b.enabled = enabled;
        b.transport = transport;
        b.port = port;
        b.connectTimeoutMs = connectTimeoutMs;
        b.initializationOptions = initializationOptions;
        if (command != null) {
            List<String> resolved = new ArrayList<>(command.size());
            for (String c : command) resolved.add(ws.expand(c));
            b.command = resolved;
        }
        if (env != null) {
            Map<String, String> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : env.entrySet()) {
                resolved.put(e.getKey(), ws.expand(e.getValue()));
            }
            b.env = resolved;
        }
        b.cwd = ws.expand(cwd);
        b.url = ws.expand(url);
        b.host = ws.expand(host);
        b.sseUrl = ws.expand(sseUrl);
        b.languageId = ws.expand(languageId);
        b.rootUri = ws.expand(rootUri);
        return b.build();
    }

    /**
     * Parse the {@code "lsp"} object of a grammar JSON (or a free-standing
     * map). Returns null when the value is missing / JSON-null / explicitly
     * disabled with a bare {@code false}.
     */
    @SuppressWarnings("unchecked")
    public static LspConfig fromJson(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean) {
            return ((Boolean) raw) ? builder().enabled(true).build() : null;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("lsp must be an object");
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        Builder b = builder();

        if (m.containsKey("enabled")) {
            b.enabled(asBool(m.get("enabled"), true));
        } else {
            b.enabled(true);
        }

        // "type" and "transport" are aliases.
        String transport = firstString(m, "transport", "type");
        if (transport != null) b.transport(Transport.parse(transport));

        Object cmd = m.containsKey("command") ? m.get("command") : m.get("cmd");
        if (cmd instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) cmd) {
                if (o != null) list.add(o.toString());
            }
            b.command(list);
        } else if (cmd instanceof String) {
            // Single string — naive whitespace split keeps simple configs short.
            String s = ((String) cmd).trim();
            if (!s.isEmpty()) {
                List<String> list = new ArrayList<>();
                for (String part : s.split("\\s+")) list.add(part);
                b.command(list);
            }
        }

        Object args = m.get("args");
        if (args instanceof List && b.command != null) {
            List<String> withArgs = new ArrayList<>(b.command);
            for (Object o : (List<?>) args) {
                if (o != null) withArgs.add(o.toString());
            }
            b.command(withArgs);
        }

        Object envObj = m.get("env");
        if (envObj instanceof Map) {
            Map<String, String> env = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) envObj).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    env.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            b.env(env);
        }

        b.cwd(firstString(m, "cwd", "workingDirectory", "workdir"));
        b.url(firstString(m, "url", "endpoint", "uri"));
        b.host(firstString(m, "host", "hostname", "address"));
        if (m.containsKey("port")) b.port(asInt(m.get("port"), 0));
        b.sseUrl(firstString(m, "sseUrl", "sse", "eventsUrl"));
        b.languageId(firstString(m, "languageId", "language"));
        b.rootUri(firstString(m, "rootUri", "root", "workspaceUri"));
        if (m.containsKey("initializationOptions")) {
            b.initializationOptions(m.get("initializationOptions"));
        } else if (m.containsKey("initOptions")) {
            b.initializationOptions(m.get("initOptions"));
        }
        if (m.containsKey("connectTimeoutMs")) {
            b.connectTimeoutMs(asInt(m.get("connectTimeoutMs"), 10_000));
        } else if (m.containsKey("timeout")) {
            b.connectTimeoutMs(asInt(m.get("timeout"), 10_000));
        }

        // Infer transport from URL scheme when the user only set "url".
        if (transport == null && b.url != null) {
            String u = b.url.toLowerCase(Locale.US);
            if (u.startsWith("ws://") || u.startsWith("wss://")) {
                b.transport(Transport.WEBSOCKET);
            } else if (u.startsWith("http://") || u.startsWith("https://")) {
                b.transport(Transport.HTTP);
            } else if (u.startsWith("tcp://") || u.startsWith("socket://")) {
                b.transport(Transport.TCP);
                // tcp://host:port[/...]
                parseTcpUrl(b.url, b);
            }
        }
        // host:port shorthand in "url" with tcp transport.
        if ((b.transport == Transport.TCP || b.transport == Transport.SOCKET)
                && b.port <= 0 && b.url != null) {
            parseTcpUrl(b.url, b);
        }

        return b.build();
    }

    private static void parseTcpUrl(String raw, Builder b) {
        String s = raw;
        if (s.startsWith("tcp://")) s = s.substring(6);
        else if (s.startsWith("socket://")) s = s.substring(9);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            b.host(s.substring(0, colon));
            try {
                b.port(Integer.parseInt(s.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
            }
        } else if (!s.isEmpty() && b.host == null) {
            b.host(s);
        }
    }

    private static String firstString(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = v.toString();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return def;
        return Boolean.parseBoolean(o.toString());
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return def;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return "LspConfig{transport=" + transport
                + ", command=" + command
                + ", url=" + url
                + ", host=" + host + ':' + port
                + ", enabled=" + enabled + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LspConfig)) return false;
        LspConfig c = (LspConfig) o;
        return enabled == c.enabled
                && port == c.port
                && connectTimeoutMs == c.connectTimeoutMs
                && transport == c.transport
                && Objects.equals(command, c.command)
                && Objects.equals(env, c.env)
                && Objects.equals(cwd, c.cwd)
                && Objects.equals(url, c.url)
                && Objects.equals(host, c.host)
                && Objects.equals(sseUrl, c.sseUrl)
                && Objects.equals(languageId, c.languageId)
                && Objects.equals(rootUri, c.rootUri);
        // initializationOptions intentionally excluded from equality — opaque.
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, transport, command, env, cwd, url, host,
                port, sseUrl, languageId, rootUri, connectTimeoutMs);
    }

    public static final class Builder {
        private boolean enabled = true;
        private Transport transport = Transport.STDIO;
        private List<String> command;
        private Map<String, String> env;
        private String cwd;
        private String url;
        private String host = "127.0.0.1";
        private int port;
        private String sseUrl;
        private String languageId;
        private String rootUri;
        private Object initializationOptions;
        private int connectTimeoutMs = 10_000;

        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder transport(Transport v) { this.transport = v; return this; }
        public Builder command(List<String> v) { this.command = v; return this; }
        public Builder command(String... v) {
            this.command = new ArrayList<>();
            if (v != null) Collections.addAll(this.command, v);
            return this;
        }
        public Builder env(Map<String, String> v) { this.env = v; return this; }
        public Builder cwd(String v) { this.cwd = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder sseUrl(String v) { this.sseUrl = v; return this; }
        public Builder languageId(String v) { this.languageId = v; return this; }
        public Builder rootUri(String v) { this.rootUri = v; return this; }
        public Builder initializationOptions(Object v) {
            this.initializationOptions = v;
            return this;
        }
        public Builder connectTimeoutMs(int v) {
            this.connectTimeoutMs = v;
            return this;
        }

        public LspConfig build() {
            return new LspConfig(this);
        }
    }

    /**
     * Runtime paths used to expand {@code ${…}} placeholders in an
     * {@link LspConfig}. All fields are optional.
     */
    public static final class LspWorkspace {
        public static final LspWorkspace EMPTY = new LspWorkspace(null, null, null);

        public final String workspaceFolder; // absolute filesystem path
        public final String file;            // absolute filesystem path of current doc
        public final String languageId;

        public LspWorkspace(String workspaceFolder, String file, String languageId) {
            this.workspaceFolder = workspaceFolder;
            this.file = file;
            this.languageId = languageId;
        }

        public String expand(String template) {
            if (template == null) return null;
            String s = template;
            s = replace(s, "${workspaceFolder}", workspaceFolder == null ? "" : workspaceFolder);
            s = replace(s, "${workspaceFolderUri}", toFileUri(workspaceFolder));
            s = replace(s, "${workspaceFolderBasename}", basename(workspaceFolder));
            s = replace(s, "${file}", file == null ? "" : file);
            s = replace(s, "${fileUri}", toFileUri(file));
            s = replace(s, "${fileBasename}", basename(file));
            s = replace(s, "${languageId}", languageId == null ? "" : languageId);
            return s;
        }

        private static String replace(String s, String key, String value) {
            if (s.indexOf(key) < 0) return s;
            return s.replace(key, value == null ? "" : value);
        }

        public static String toFileUri(String path) {
            if (path == null || path.isEmpty()) return "";
            if (path.startsWith("file:")) return path;
            String p = path.replace('\\', '/');
            if (!p.startsWith("/")) p = "/" + p;
            // Escape spaces lightly; full RFC 3986 encoding is overkill here.
            p = p.replace(" ", "%20");
            return "file://" + p;
        }

        private static String basename(String path) {
            if (path == null || path.isEmpty()) return "";
            String p = path.replace('\\', '/');
            int i = p.lastIndexOf('/');
            return i >= 0 ? p.substring(i + 1) : p;
        }

        public static String pathFromUri(String uri) {
            if (uri == null) return null;
            if (!uri.startsWith("file:")) return uri;
            String p = uri.substring(5); // strip "file:"
            while (p.startsWith("//")) {
                // file:///tmp → /tmp ; file://localhost/tmp → /localhost/tmp
                p = p.substring(1);
            }
            if (p.startsWith("localhost/")) p = p.substring(9);
            return p.replace("%20", " ");
        }
    }
}

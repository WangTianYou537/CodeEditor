package cn.wty5.editor.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/serializer covering the subset used by grammar files
 * and the LSP client: objects, arrays, strings (with standard escapes),
 * numbers, booleans, null.
 *
 * Values are {@link Map}/{@link List}/String/Number/Boolean/null.
 * No external dependency — keeps the core runnable on a plain JDK.
 */
public final class MiniJson {

    private MiniJson() {}

    public static Object parse(String source) {
        return new Parser(source).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String source) {
        Object v = parse(source);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** Serialise a JSON-compatible value tree to a compact string. */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Number) {
            // Avoid trailing .0 for integers stored as Double.
            Number n = (Number) value;
            if (value instanceof Double || value instanceof Float) {
                double d = n.doubleValue();
                if (Double.isFinite(d) && d == Math.rint(d)
                        && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                    sb.append((long) d);
                } else {
                    sb.append(d);
                }
            } else {
                sb.append(n.toString());
            }
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) value) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, arr[i]);
            }
            sb.append(']');
        } else {
            // Fallback: string form (keeps callers from crashing on enums etc.).
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw err("unexpected '" + c + "'");
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': case '\\': case '/': sb.append(e); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("bad \\u");
                            int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                            sb.append((char) cp);
                            i += 4;
                            break;
                        default:
                            throw err("unknown escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private Number parseNumber() {
            int start = i;
            if (peek('-')) i++;
            while (i < s.length() && isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (peek('.')) {
                isFloat = true;
                i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isFloat = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                if (isFloat) return Double.parseDouble(num);
                long v = Long.parseLong(num);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    return (int) v;
                }
                return v;
            } catch (NumberFormatException e) {
                throw err("bad number " + num);
            }
        }

        private Boolean parseBool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("expected true/false");
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("expected null");
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw err("expected '" + c + "'");
            }
            i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(
                    "JSON parse error at " + i + ": " + msg);
        }
    }
}

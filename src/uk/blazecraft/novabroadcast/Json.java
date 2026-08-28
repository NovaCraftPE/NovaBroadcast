package uk.blazecraft.novabroadcast;

import java.util.*;

final class Json {
    static String string(String json, String key) {
        Object root = parse(json);
        if (root instanceof Map<?,?> map) {
            Object v = map.get(key);
            return v == null ? "" : String.valueOf(v);
        }
        return "";
    }

    static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    @SuppressWarnings("unchecked")
    static String nestedString(String json, String... path) {
        Object v = parse(json);
        for (String key : path) {
            if (v instanceof Map<?,?> map) {
                v = map.get(key);
            } else if (v instanceof List<?> list) {
                int i = Integer.parseInt(key);
                v = i >= 0 && i < list.size() ? list.get(i) : null;
            } else {
                return "";
            }
            if (v == null) return "";
        }
        return String.valueOf(v);
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Deterministic compact JSON used for extracted MPSD custom-property files. */
    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return quote(s);
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short ||
                value instanceof Integer || value instanceof Long) return String.valueOf(value);
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite JSON number");
            return String.valueOf(f);
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite JSON number");
            return String.valueOf(d);
        }
        if (value instanceof Number n) return String.valueOf(n);
        if (value instanceof Map<?,?> map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?,?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object key is not a string");
                }
                if (!first) b.append(',');
                first = false;
                b.append(quote(key)).append(':').append(stringify(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object item : values) {
                if (!first) b.append(',');
                first = false;
                b.append(stringify(item));
            }
            return b.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s == null ? "" : s; }

        Object parseValue() {
            ws();
            if (i >= s.length()) return null;
            return switch (s.charAt(i)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> numberOrBare();
            };
        }

        private Map<String,Object> object() {
            i++;
            Map<String,Object> m = new LinkedHashMap<>();
            ws();
            if (peek('}')) { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = string();
                ws();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                ws();
                if (peek('}')) { i++; break; }
                expect(',');
            }
            return m;
        }

        private List<Object> array() {
            i++;
            List<Object> a = new ArrayList<>();
            ws();
            if (peek(']')) { i++; return a; }
            while (i < s.length()) {
                a.add(parseValue());
                ws();
                if (peek(']')) { i++; break; }
                expect(',');
            }
            return a;
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"','\\','/' -> b.append(e);
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            String hex = s.substring(i, Math.min(i + 4, s.length()));
                            i += 4;
                            b.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private Object literal(String text, Object value) {
            if (!s.startsWith(text, i)) throw new IllegalArgumentException("Invalid JSON");
            i += text.length();
            return value;
        }

        private Object numberOrBare() {
            int start = i;
            while (i < s.length() && ",]} \r\n\t".indexOf(s.charAt(i)) < 0) i++;
            String x = s.substring(start, i);
            try {
                if (x.contains(".") || x.contains("e") || x.contains("E")) return Double.parseDouble(x);
                return Long.parseLong(x);
            } catch (NumberFormatException e) {
                return x;
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            ws();
            if (!peek(c)) throw new IllegalArgumentException("Invalid JSON near position " + i);
            i++;
        }
    }

    private Json() {}
}

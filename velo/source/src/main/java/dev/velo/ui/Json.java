package dev.velo.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough JSON for the browser bridge: one flat object of strings, numbers, booleans and nulls. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses {"key": value, ...}; numbers and booleans come back as their text, null as absent. */
    static Map<String, String> parseObject(String text) {
        Json p = new Json(text);
        Map<String, String> out = p.object();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing characters at " + p.i);
        }
        return out;
    }

    static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private Map<String, String> object() {
        Map<String, String> out = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            String value = value();
            if (value != null) {
                out.put(key, value);
            }
            ws();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }
    }

    private String value() {
        char c = peek();
        if (c == '"') {
            return string();
        }
        int start = i;
        while (i < s.length() && "{}[],: \t\r\n".indexOf(s.charAt(i)) < 0) {
            i++;
        }
        String word = s.substring(start, i);
        if (word.equals("null")) {
            return null;
        }
        if (word.equals("true") || word.equals("false") || word.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            return word;
        }
        throw new IllegalArgumentException("unsupported value at " + start);
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new IllegalArgumentException("bad escape");
                    }
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
        }
    }
}

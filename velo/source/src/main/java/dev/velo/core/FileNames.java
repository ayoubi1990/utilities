package dev.velo.core;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Works out a safe local file name from what the server sends. */
final class FileNames {
    private static final int MAX_LEN = 200;
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
            "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() {}

    /** Reads the name from a Content-Disposition value, preferring RFC 5987 {@code filename*}. */
    static String fromContentDisposition(String value) {
        String plain = null;
        for (String part : value.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = part.substring(eq + 1).trim();
            if (key.equals("filename*")) {
                // charset'language'percent-encoded-value
                String[] pieces = val.split("'", 3);
                String encoded = unquote(pieces.length == 3 ? pieces[2] : val);
                String name = percentDecode(encoded);
                if (!name.isEmpty()) {
                    return name;
                }
            } else if (key.equals("filename")) {
                plain = unquote(val);
            }
        }
        return plain == null || plain.isEmpty() ? null : plain;
    }

    /** Uses the last non-empty path segment of the URL. */
    static String fromUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                return percentDecode(parts[i]);
            }
        }
        return null;
    }

    /** Makes a server-supplied name safe on Windows, macOS and Linux. */
    static String sanitize(String name) {
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        StringBuilder sb = new StringBuilder();
        base.codePoints().forEach(c -> {
            boolean bad = "<>:\"|?*".indexOf(c) >= 0 || Character.isISOControl(c);
            sb.appendCodePoint(bad ? '_' : c);
        });
        String out = sb.toString().strip().replaceAll("[. ]+$", "");
        if (out.isEmpty()) {
            out = "download";
        }
        String stem = out.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (RESERVED.contains(stem)) {
            out = "_" + out;
        }
        return truncate(out);
    }

    private static String truncate(String name) {
        if (name.length() <= MAX_LEN) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        int stemEnd = MAX_LEN - ext.length();
        if (Character.isLowSurrogate(name.charAt(stemEnd))) {
            stemEnd--;
        }
        return name.substring(0, stemEnd) + ext;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** Decodes %XX escapes as UTF-8; unlike URLDecoder, leaves '+' alone. */
    static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi << 4 | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(bytes[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}

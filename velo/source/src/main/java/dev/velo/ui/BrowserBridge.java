package dev.velo.ui;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A tiny HTTP endpoint on 127.0.0.1 that the velo browser extension calls to
 * hand over a link. Web pages cannot use it: requests carrying a web origin
 * are refused, and every hand-off still has to be confirmed in the UI.
 *
 * <pre>
 * GET  /ping       -> {"app":"velo","version":"0.1"}
 * POST /download   {"url", "referrer", "cookie", "userAgent", "title", "fileName"}
 * </pre>
 */
final class BrowserBridge implements AutoCloseable {
    static final int DEFAULT_PORT = 47321;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** @param mode "page" when the link is a video page for yt-dlp; anything else means a file */
    record Request(String url, String referrer, String cookie, String userAgent, String title, String fileName,
            String mode) {
        boolean isPage() {
            return "page".equals(mode);
        }
    }

    private final ServerSocket server;
    private final Consumer<Request> onDownload;
    private volatile Runnable onActivate = () -> { };

    private BrowserBridge(ServerSocket server, Consumer<Request> onDownload) {
        this.server = server;
        this.onDownload = onDownload;
        Thread.ofPlatform().daemon().name("velo-bridge").start(this::acceptLoop);
    }

    /** Listens on loopback only; throws if the port is taken (e.g. another velo is running). */
    static BrowserBridge start(int port, Consumer<Request> onDownload) throws IOException {
        return new BrowserBridge(new ServerSocket(port, 20, InetAddress.getLoopbackAddress()), onDownload);
    }

    /** What to do when a second launch of velo asks this one to come to the front. */
    void onActivate(Runnable action) {
        onActivate = action;
    }

    /**
     * Used by a second launch: asks the running velo to show its window and take
     * the given links. Returns false if no velo answered.
     */
    static boolean handOver(int port, java.util.List<String> urls) {
        try {
            for (String url : urls) {
                if (localPost(port, "/download", "{\"url\":" + Json.quote(url) + "}") != 200) {
                    return false;
                }
            }
            return localPost(port, "/activate", "{}") == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static int localPost(int port, String path, String json) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(3000);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            String head = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            String status = new String(s.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
            return status.startsWith("HTTP/1.1 ") ? Integer.parseInt(status.substring(9, 12)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread.ofPlatform().daemon().name("velo-bridge-conn").start(() -> handle(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                respond(out, 400, null, "{\"error\":\"bad request\"}");
                return;
            }
            String method = requestLine[0];
            String path = requestLine[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }

            // Guard against DNS rebinding: only answer requests addressed to loopback.
            String host = headers.getOrDefault("host", "");
            if (!(host.startsWith("127.0.0.1:") || host.startsWith("localhost:"))) {
                respond(out, 403, null, "{\"error\":\"forbidden host\"}");
                return;
            }
            String origin = headers.get("origin");
            if (!originAllowed(origin)) {
                respond(out, 403, null, "{\"error\":\"only the velo browser extension may call this\"}");
                return;
            }
            String allowOrigin = origin;

            switch (method + " " + path) {
                case "OPTIONS /ping", "OPTIONS /download" -> respond(out, 204, allowOrigin, null);
                case "GET /ping" -> respond(out, 200, allowOrigin, "{\"app\":\"velo\",\"version\":\"0.1\"}");
                case "POST /activate" -> {
                    // Only another velo process on this machine (no Origin) may raise the window.
                    if (origin != null) {
                        respond(out, 403, allowOrigin, "{\"error\":\"forbidden\"}");
                        return;
                    }
                    onActivate.run();
                    respond(out, 200, null, "{\"ok\":true}");
                }
                case "POST /download" -> {
                    int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    if (length <= 0 || length > MAX_BODY_BYTES) {
                        respond(out, 413, allowOrigin, "{\"error\":\"bad body size\"}");
                        return;
                    }
                    String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
                    Request r = parse(body);
                    if (r == null) {
                        respond(out, 400, allowOrigin, "{\"error\":\"expected an http(s) url\"}");
                        return;
                    }
                    onDownload.accept(r);
                    respond(out, 200, allowOrigin, "{\"ok\":true}");
                }
                default -> respond(out, 404, allowOrigin, "{\"error\":\"not found\"}");
            }
        } catch (IOException | RuntimeException e) {
            // A broken or malformed request; nothing to report to anyone.
        }
    }

    /**
     * Extensions identify themselves with an extension origin. A missing origin means a
     * non-browser client on this machine. Any web origin (including "null") is refused.
     */
    static boolean originAllowed(String origin) {
        if (origin == null) {
            return true;
        }
        String o = origin.toLowerCase(Locale.ROOT);
        return o.startsWith("chrome-extension://") || o.startsWith("moz-extension://")
                || o.startsWith("extension://");
    }

    static Request parse(String body) {
        Map<String, String> m;
        try {
            m = Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String url = m.get("url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")) || url.length() > 8192) {
            return null;
        }
        return new Request(url, m.get("referrer"), m.get("cookie"), m.get("userAgent"), m.get("title"),
                m.get("fileName"), m.get("mode"));
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return buf.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static void respond(OutputStream out, int status, String allowOrigin, String json) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 413 -> "Payload Too Large";
            default -> "Error";
        };
        byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (allowOrigin != null) {
            h.append("Access-Control-Allow-Origin: ").append(allowOrigin).append("\r\n");
            h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            h.append("Access-Control-Allow-Headers: Content-Type\r\n");
            h.append("Vary: Origin\r\n");
        }
        if (json != null) {
            h.append("Content-Type: application/json\r\n");
        }
        h.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}

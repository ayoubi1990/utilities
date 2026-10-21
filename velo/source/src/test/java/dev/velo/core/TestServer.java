package dev.velo.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny blocking HTTP/1.1 server with byte-range support and knobs for
 * failure scenarios. Paths starting with /redirect/ answer 302 to the rest.
 */
public final class TestServer implements AutoCloseable {
    private static final int CHUNK = 16 * 1024;

    public final byte[] payload;
    volatile boolean ranges = true;
    volatile String disposition;
    /** Pause after every 16 KiB to simulate a slow server. */
    public volatile long sleepMillisPerChunk;
    /** This many large responses are cut off after 64 KiB. */
    final AtomicInteger dropsLeft = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();

    private final ServerSocket server;

    public TestServer(int size) throws IOException {
        payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i * 31 % 251);
        }
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("test-server").start(this::acceptLoop);
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread.ofPlatform().daemon().start(() -> handle(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = r.readLine();
            if (requestLine == null) {
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (String line; (line = r.readLine()) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            requests.incrementAndGet();
            String path = requestLine.split(" ")[1];
            OutputStream os = socket.getOutputStream();
            if (path.startsWith("/redirect/")) {
                write(os, "HTTP/1.1 302 Found\r\nLocation: /" + path.substring(10)
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }

            long from = 0;
            long to = payload.length - 1;
            String status = "200 OK";
            StringBuilder head = new StringBuilder();
            String range = headers.get("range");
            if (ranges && range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                from = Long.parseLong(parts[0]);
                if (!parts[1].isEmpty()) {
                    to = Math.min(to, Long.parseLong(parts[1]));
                }
                status = "206 Partial Content";
                head.append("Content-Range: bytes ").append(from).append('-').append(to).append('/')
                        .append(payload.length).append("\r\n");
            }
            long length = to - from + 1;
            head.insert(0, "HTTP/1.1 " + status + "\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            head.append("ETag: \"v1\"\r\n");
            head.append("Accept-Ranges: ").append(ranges ? "bytes" : "none").append("\r\n");
            if (disposition != null) {
                head.append("Content-Disposition: ").append(disposition).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            write(os, head.toString());

            boolean drop = length > 128 * 1024 && dropsLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
            long sent = 0;
            for (long pos = from; pos <= to; ) {
                int n = (int) Math.min(CHUNK, to - pos + 1);
                os.write(payload, (int) pos, n);
                pos += n;
                sent += n;
                if (drop && sent >= 64 * 1024) {
                    os.flush();
                    return; // close mid-body to simulate a dropped connection
                }
                long sleep = sleepMillisPerChunk;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
            os.flush();
        } catch (IOException | InterruptedException e) {
            // The client went away.
        }
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}

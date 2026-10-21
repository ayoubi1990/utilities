package dev.velo.ui;

import static dev.velo.core.TestMain.check;
import static dev.velo.core.TestMain.eq;
import static dev.velo.core.TestMain.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class UiTests {
    private UiTests() {}

    public static void run() {
        test("json flat objects", () -> {
            Map<String, String> m = Json.parseObject(
                    "{\"url\":\"https://x/a b\\u00e9\",\"n\":12,\"ok\":true,\"none\":null,\"q\":\"say \\\"hi\\\"\"}");
            eq(m.get("url"), "https://x/a bé");
            eq(m.get("n"), "12");
            eq(m.get("ok"), "true");
            check(!m.containsKey("none"), "null is absent");
            eq(m.get("q"), "say \"hi\"");
            eq(Json.quote("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
            try {
                Json.parseObject("{\"a\":{\"nested\":1}}");
                throw new AssertionError("nested objects are rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        });
        test("bridge origin policy", () -> {
            check(BrowserBridge.originAllowed(null), "local clients allowed");
            check(BrowserBridge.originAllowed("chrome-extension://abcdef"), "chrome extension allowed");
            check(BrowserBridge.originAllowed("moz-extension://abcdef"), "firefox extension allowed");
            check(!BrowserBridge.originAllowed("https://evil.example"), "web pages refused");
            check(!BrowserBridge.originAllowed("null"), "opaque origins refused");
        });
        test("bridge accepts extensions and refuses web pages", () -> {
            List<BrowserBridge.Request> got = new CopyOnWriteArrayList<>();
            try (BrowserBridge bridge = BrowserBridge.start(0, got::add)) {
                String body = "{\"url\":\"https://cdn.example/v.mp4\",\"referrer\":\"https://site/page\",\"title\":\"T\"}";
                String ok = post(bridge.port(), "chrome-extension://abc", "127.0.0.1:" + bridge.port(), body);
                check(ok.startsWith("HTTP/1.1 200"), ok);
                check(ok.contains("Access-Control-Allow-Origin: chrome-extension://abc"), ok);
                String web = post(bridge.port(), "https://evil.example", "127.0.0.1:" + bridge.port(), body);
                check(web.startsWith("HTTP/1.1 403"), web);
                String rebinding = post(bridge.port(), null, "evil.example:" + bridge.port(), body);
                check(rebinding.startsWith("HTTP/1.1 403"), rebinding);
                String bad = post(bridge.port(), null, "127.0.0.1:" + bridge.port(), "{\"url\":\"file:///c:/x\"}");
                check(bad.startsWith("HTTP/1.1 400"), bad);
                long deadline = System.currentTimeMillis() + 2000;
                while (got.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                eq(got.size(), 1);
                eq(got.get(0).url(), "https://cdn.example/v.mp4");
                eq(got.get(0).referrer(), "https://site/page");
            }
        });
        test("only one velo per data folder", () -> {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("velo-lock");
            SingleInstance first = SingleInstance.acquire(dir);
            check(first != null, "first launch gets the lock");
            check(SingleInstance.acquire(dir) == null, "second launch is refused");
            first.release();
            SingleInstance again = SingleInstance.acquire(dir);
            check(again != null, "lock is free again after release");
            again.release();
        });
        test("a second launch hands its links to the running one", () -> {
            List<BrowserBridge.Request> got = new CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean raised = new java.util.concurrent.atomic.AtomicBoolean();
            try (BrowserBridge bridge = BrowserBridge.start(0, got::add)) {
                bridge.onActivate(() -> raised.set(true));
                check(BrowserBridge.handOver(bridge.port(), List.of("https://x.example/a.zip")), "hand-over answered");
                eq(got.size(), 1);
                eq(got.get(0).url(), "https://x.example/a.zip");
                check(raised.get(), "window raised");
            }
            check(!BrowserBridge.handOver(1, List.of()), "no velo on port 1");
        });
        test("show-in-folder passes the path as its own argument", () -> {
            // An absolute path with spaces and brackets, valid on any OS.
            java.nio.file.Path p = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().resolve("Big Buck Bunny [aqz].mp4");
            eq(CompleteDialog.explorerSelect(p), List.of("explorer.exe", "/select,", p.toString()));
        });
        test("categories by extension", () -> {
            eq(DownloadItem.Type.of("movie.MKV"), DownloadItem.Type.VIDEO);
            eq(DownloadItem.Type.of("setup.exe"), DownloadItem.Type.PROGRAMS);
            eq(DownloadItem.Type.of("notes"), DownloadItem.Type.OTHER);
            eq(DownloadItem.guessName("https://x.com/a/b/clip.mp4?sig=1"), "clip.mp4");
        });
    }

    private static String post(int port, String origin, String host, String body) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder("POST /download HTTP/1.1\r\nHost: ").append(host).append("\r\n");
            if (origin != null) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            req.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

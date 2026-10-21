package dev.velo.core;

import dev.velo.cli.CliTests;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** A dependency-free test runner: java -ea -cp out/main;out/test dev.velo.core.TestMain */
public final class TestMain {
    private static int passed;
    private static int failed;

    public interface Body {
        void run() throws Exception;
    }

    public static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("ok    " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void main(String[] args) throws Exception {
        segmentTableTests();
        fileNameTests();
        miscUnitTests();
        integrationTests();
        CliTests.run();
        dev.velo.ui.UiTests.run();
        dev.velo.media.MediaTests.run();
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void segmentTableTests() {
        test("table splits evenly", () -> {
            SegmentTable t = SegmentTable.create(1000, 4, 100);
            eq(t.snapshot().stream().map(s -> s.start() + "-" + s.end()).toList(),
                    List.of("0-250", "250-500", "500-750", "750-1000"));
        });
        test("table respects min split", () -> {
            SegmentTable t = SegmentTable.create(250, 8, 100);
            eq(t.snapshot().size(), 3);
            eq(t.snapshot().get(2).end(), 250L);
        });
        test("idle connection steals half of largest range", () -> {
            SegmentTable t = SegmentTable.create(1000, 1, 100);
            eq(t.claim(), 0);
            eq(t.reserve(0, 200), 200L);
            t.commit(0, 200);
            eq(t.claim(), 1);
            eq(Arrays.toString(t.pendingRange(0)), "[200, 600]");
            eq(Arrays.toString(t.pendingRange(1)), "[600, 1000]");
            // The first connection's stream is cut at the new boundary.
            eq(t.reserve(0, 1000), 400L);
            t.commit(0, 400);
            check(t.isSegmentDone(0), "first segment done");
            check(!t.isComplete(), "table not complete");
        });
        test("table refuses to split small remainders", () -> {
            SegmentTable t = SegmentTable.create(300, 1, 100);
            t.claim();
            t.reserve(0, 150);
            eq(t.claim(), -1);
        });
        test("release drops unwritten reservation", () -> {
            SegmentTable t = SegmentTable.create(1000, 1, 100);
            t.claim();
            t.reserve(0, 500);
            t.commit(0, 300);
            t.release(0);
            eq(t.claim(), 0);
            eq(Arrays.toString(t.pendingRange(0)), "[300, 1000]");
            eq(t.downloaded(), 300L);
        });
        test("saved state must fit the file", () -> {
            List<Segment> ok = SegmentTable.create(1000, 2, 1).snapshot();
            check(SegmentTable.fromSaved(ok, 1000, 1) != null, "valid state accepted");
            check(SegmentTable.fromSaved(ok, 900, 1) == null, "oversized state rejected");
        });
    }

    private static void fileNameTests() {
        test("content-disposition forms", () -> {
            eq(FileNames.fromContentDisposition("attachment; filename=\"report.pdf\""), "report.pdf");
            eq(FileNames.fromContentDisposition("attachment; filename=plain.zip"), "plain.zip");
            eq(FileNames.fromContentDisposition(
                    "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E2%82%AC%20rates.txt"), "€ rates.txt");
            eq(FileNames.fromContentDisposition("inline"), null);
        });
        test("name from URL", () -> {
            eq(FileNames.fromUri(URI.create("https://example.com/files/My%20Setup+1.exe?x=1")), "My Setup+1.exe");
            eq(FileNames.fromUri(URI.create("https://example.com/dir/")), "dir");
            eq(FileNames.fromUri(URI.create("https://example.com/")), null);
        });
        test("sanitize names", () -> {
            eq(FileNames.sanitize("../../etc/passwd"), "passwd");
            eq(FileNames.sanitize("C:\\evil\\a:b?.txt"), "a_b_.txt");
            eq(FileNames.sanitize("con.txt"), "_con.txt");
            eq(FileNames.sanitize("name. . "), "name");
            eq(FileNames.sanitize("   "), "download");
            String out = FileNames.sanitize("x".repeat(300) + ".iso");
            eq(out.length(), 200);
            check(out.endsWith(".iso"), "keeps extension");
        });
    }

    private static void miscUnitTests() {
        test("content-range total", () -> {
            eq(Probe.contentRangeTotal("bytes 0-0/12345"), 12345L);
            eq(Probe.contentRangeTotal("bytes 0-0/*"), -1L);
            eq(Probe.contentRangeTotal("garbage"), -1L);
        });
        test("state file round trip", () -> {
            Path dir = Files.createTempDirectory("velo-state");
            try {
                Path p = dir.resolve("f.velo");
                var s = new StateFile.Saved("https://x/y?a=b&c", 1000, "\"e\"", null,
                        List.of(new Segment(0, 500, 120), new Segment(500, 1000, 1000)));
                StateFile.save(p, s);
                eq(StateFile.load(p), s);
                Files.writeString(p, "garbage");
                eq(StateFile.load(p), null);
            } finally {
                deleteTree(dir);
            }
        });
        test("rate limiter throttles", () -> {
            RateLimiter limiter = new RateLimiter(100_000);
            long start = System.nanoTime();
            // The first 100 KB is the burst allowance; the next 50 KB costs 0.5 s.
            for (int i = 0; i < 15; i++) {
                limiter.acquire(10_000);
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            check(elapsed > 0.4 && elapsed < 1.0, "elapsed " + elapsed);
        });
    }

    private static void integrationTests() {
        Engine engine = new Engine();
        DownloadOptions small = DownloadOptions.defaults().withMinSplit(256 * 1024);

        test("segmented download matches the source", () -> {
            try (TestServer server = new TestServer(5 * 1024 * 1024 + 123); TempDir dir = new TempDir()) {
                Download d = engine.start(server.url("/files/data.bin"), Destination.dir(dir.path), small);
                check(d.resumable(), "resumable");
                Path saved = finish(d);
                eq(saved.getFileName().toString(), "data.bin");
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
                check(!Files.exists(StateFile.partPath(saved)), ".part removed");
                check(!Files.exists(StateFile.statePath(saved)), ".velo removed");
                eq(d.progress().downloaded(), (long) server.payload.length);
            }
        });
        test("redirects are followed", () -> {
            try (TestServer server = new TestServer(2 * 1024 * 1024); TempDir dir = new TempDir()) {
                Download d = engine.start(server.url("/redirect/real/name.zip"), Destination.dir(dir.path), small);
                Path saved = finish(d);
                eq(saved.getFileName().toString(), "name.zip");
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
            }
        });
        test("dropped connections are retried", () -> {
            try (TestServer server = new TestServer(3 * 1024 * 1024); TempDir dir = new TempDir()) {
                server.dropsLeft.set(4);
                Path saved = finish(engine.start(server.url("/r.bin"), Destination.dir(dir.path),
                        small.withConnections(4)));
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
                eq(server.dropsLeft.get(), 0);
            }
        });
        test("servers without ranges use one stream", () -> {
            try (TestServer server = new TestServer(2 * 1024 * 1024); TempDir dir = new TempDir()) {
                server.ranges = false;
                Download d = engine.start(server.url("/plain.bin"), Destination.dir(dir.path), small);
                check(!d.resumable(), "not resumable");
                Path saved = finish(d);
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
                eq(server.requests.get(), 1);
            }
        });
        test("content-disposition name and unique paths", () -> {
            try (TestServer server = new TestServer(1000); TempDir dir = new TempDir()) {
                server.disposition = "attachment; filename=\"report.txt\"";
                Path first = finish(engine.start(server.url("/download?id=1"), Destination.dir(dir.path), small));
                Path second = finish(engine.start(server.url("/download?id=1"), Destination.dir(dir.path), small));
                eq(first.getFileName().toString(), "report.txt");
                eq(second.getFileName().toString(), "report (1).txt");
            }
        });
        test("cancelled download resumes where it stopped", () -> {
            try (TestServer server = new TestServer(8 * 1024 * 1024); TempDir dir = new TempDir()) {
                server.sleepMillisPerChunk = 5;
                DownloadOptions opts = small.withConnections(4);
                Download d = engine.start(server.url("/big.iso"), Destination.dir(dir.path), opts);
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (d.progress().downloaded() < 1024 * 1024 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                d.cancel();
                try {
                    d.await(Duration.ofSeconds(20));
                    throw new AssertionError("expected cancellation");
                } catch (VeloException e) {
                    eq(e.kind(), VeloException.Kind.CANCELLED);
                }
                check(Files.exists(StateFile.statePath(d.path())), "state saved");
                check(Files.exists(StateFile.partPath(d.path())), "partial data kept");

                server.sleepMillisPerChunk = 0;
                Download resumed = engine.start(server.url("/big.iso"), Destination.dir(dir.path), opts);
                eq(resumed.path(), d.path());
                check(resumed.resumedFrom() >= 1024 * 1024, "resumed from " + resumed.resumedFrom());
                check(resumed.resumedFrom() < server.payload.length, "not already complete");
                Path saved = finish(resumed);
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
            }
        });
        test("speed limit applies", () -> {
            try (TestServer server = new TestServer(3 * 1024 * 1024); TempDir dir = new TempDir()) {
                Engine limited = new Engine(new RateLimiter(2 * 1024 * 1024));
                long start = System.nanoTime();
                Path saved = finish(limited.start(server.url("/slow.bin"), Destination.dir(dir.path), small));
                double elapsed = (System.nanoTime() - start) / 1e9;
                // 2 MiB of burst, then 1 MiB at 2 MiB/s.
                check(elapsed >= 0.4, "elapsed " + elapsed);
                check(Arrays.equals(Files.readAllBytes(saved), server.payload), "content matches");
            }
        });
        test("HTTP errors are reported", () -> {
            try {
                engine.start("http://127.0.0.1:1/nothing", Destination.dir(Path.of(".")), small);
                throw new AssertionError("expected failure");
            } catch (VeloException e) {
                eq(e.kind(), VeloException.Kind.NETWORK);
            }
            try {
                engine.start("ftp://example.com/x", Destination.dir(Path.of(".")), small);
                throw new AssertionError("expected failure");
            } catch (VeloException e) {
                eq(e.kind(), VeloException.Kind.INVALID_URL);
            }
        });
    }

    private static Path finish(Download d) throws Exception {
        Path saved = d.await(Duration.ofSeconds(60));
        check(saved != null, "download finished in time");
        return saved;
    }

    static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static final class TempDir implements AutoCloseable {
        final Path path;

        TempDir() throws IOException {
            path = Files.createTempDirectory("velo-test");
        }

        @Override
        public void close() throws IOException {
            deleteTree(path);
        }
    }
}

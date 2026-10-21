package dev.velo.cli;

import static dev.velo.core.TestMain.check;
import static dev.velo.core.TestMain.eq;
import static dev.velo.core.TestMain.test;

import dev.velo.core.Segment;
import java.util.List;

public final class CliTests {
    private CliTests() {}

    public static void run() {
        test("parse sizes", () -> {
            eq(Main.parseSize("100"), 100L);
            eq(Main.parseSize("500k"), 500L * 1024);
            eq(Main.parseSize("2M"), 2L << 20);
            eq(Main.parseSize("1.5MB"), 3L << 19);
            eq(Main.parseSize("1GiB"), 1L << 30);
            try {
                Main.parseSize("fast");
                throw new AssertionError("expected failure");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        });
        test("parse arguments", () -> {
            Main.Args a = Main.Args.parse(new String[] {"-c", "4", "-l", "1M", "-H", "Cookie: a=b", "http://x/y"});
            eq(a.connections, 4);
            eq(a.limit, 1L << 20);
            eq(a.headers.get("Cookie"), "a=b");
            eq(a.urls, List.of("http://x/y"));
        });
        test("segment map", () -> {
            String map = Main.segmentMap(400, List.of(new Segment(0, 200, 200), new Segment(200, 400, 210)));
            eq(map.length(), 40);
            check(map.startsWith("#".repeat(20)), map);
            check(map.charAt(20) == '#' && map.charAt(21) == ' ', map);
        });
        test("human sizes", () -> {
            eq(Main.human(512), "512 B");
            eq(Main.human(1536), "1.5 KiB");
        });
    }
}

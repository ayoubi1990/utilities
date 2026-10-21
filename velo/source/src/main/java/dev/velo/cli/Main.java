package dev.velo.cli;

import dev.velo.core.Destination;
import dev.velo.core.Download;
import dev.velo.core.DownloadOptions;
import dev.velo.core.Engine;
import dev.velo.core.Progress;
import dev.velo.core.RateLimiter;
import dev.velo.core.Segment;
import dev.velo.core.VeloException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Command-line front end: velo [options] URL... */
public final class Main {
    private static final int MAP_WIDTH = 40;
    private static final String USAGE = """
            Usage: velo [options] URL...

            Fast, resumable, multi-connection downloader.

            Options:
              -o, --dir DIR          folder to save into (default: current folder)
              -O, --output FILE      exact output file (single URL only)
              -c, --connections N    parallel connections per download, 1-32 (default 8)
              -l, --limit SIZE       speed limit per second, e.g. 500K, 2M, 1.5MB
              -H, --header "K: V"    extra request header, repeatable
              -h, --help             show this help

            Press Ctrl+C to pause; run the same command again to resume.
            """;

    private Main() {}

    public static void main(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        if (args.help) {
            System.out.print(USAGE);
            return;
        }
        System.exit(run(args));
    }

    private static int run(Args args) {
        Engine engine = new Engine(new RateLimiter(args.limit));
        AtomicReference<Download> current = new AtomicReference<>();
        Thread hook = new Thread(() -> {
            Download d = current.get();
            if (d != null && !d.isDone()) {
                d.cancel();
                try {
                    // Give the engine time to save its resume state before the JVM exits.
                    d.await(Duration.ofSeconds(10));
                } catch (Exception ignored) {
                    // Exiting anyway.
                }
                System.out.println();
                System.out.println("  paused - run the same command again to resume");
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);

        boolean failed = false;
        for (String url : args.urls) {
            Destination dest = args.output != null ? Destination.file(args.output) : Destination.dir(args.dir);
            DownloadOptions opts = DownloadOptions.defaults()
                    .withConnections(args.connections)
                    .withHeaders(args.headers);
            Download download;
            try {
                download = engine.start(url, dest, opts);
            } catch (VeloException | IllegalArgumentException e) {
                System.err.println(url + ": " + e.getMessage());
                failed = true;
                continue;
            }
            current.set(download);
            System.out.println(download.path().toAbsolutePath());
            if (download.resumedFrom() > 0) {
                System.out.println("  resuming at " + human(download.resumedFrom()));
            } else if (!download.resumable()) {
                System.out.println("  server does not support resume; using one connection");
            }

            try {
                Path saved;
                while ((saved = download.await(Duration.ofMillis(250))) == null) {
                    draw(download.progress());
                }
                draw(download.progress());
                System.out.println();
                System.out.println("  saved " + saved.toAbsolutePath());
            } catch (VeloException e) {
                System.out.println();
                if (e.kind() == VeloException.Kind.CANCELLED) {
                    return 130;
                }
                System.out.println("  failed: " + e.getMessage());
                failed = true;
            } catch (InterruptedException e) {
                return 130;
            }
            current.set(null);
        }
        Runtime.getRuntime().removeShutdownHook(hook);
        return failed ? 1 : 0;
    }

    private static void draw(Progress p) {
        StringBuilder line = new StringBuilder("  ");
        if (p.total() > 0) {
            if (!p.segments().isEmpty()) {
                line.append('[').append(segmentMap(p.total(), p.segments())).append("] ");
            }
            line.append(String.format(Locale.ROOT, "%3d%% %s / %s", p.downloaded() * 100 / p.total(),
                    human(p.downloaded()), human(p.total())));
        } else {
            line.append(human(p.downloaded()));
        }
        line.append("  ").append(human((long) p.speed())).append("/s");
        if (p.total() > 0 && p.speed() > 0) {
            line.append("  eta ").append(eta((long) ((p.total() - p.downloaded()) / p.speed())));
        }
        line.append("  ").append(p.connections()).append(p.connections() == 1 ? " conn" : " conns");
        System.out.print("\r" + line + "    ");
        System.out.flush();
    }

    /** Draws which parts of the file are on disk, like IDM's connection map. */
    static String segmentMap(long total, List<Segment> segments) {
        long[] filled = new long[MAP_WIDTH];
        for (Segment s : segments) {
            for (int i = 0; i < MAP_WIDTH; i++) {
                long lo = cellStart(total, i);
                long hi = cellStart(total, i + 1);
                long a = Math.max(s.start(), lo);
                long b = Math.min(s.written(), hi);
                if (b > a) {
                    filled[i] += b - a;
                }
            }
        }
        StringBuilder sb = new StringBuilder(MAP_WIDTH);
        for (int i = 0; i < MAP_WIDTH; i++) {
            long size = cellStart(total, i + 1) - cellStart(total, i);
            double r = size > 0 ? (double) filled[i] / size : 1;
            sb.append(r >= 1 ? '#' : r >= 0.66 ? '=' : r >= 0.33 ? '-' : r > 0 ? '.' : ' ');
        }
        return sb.toString();
    }

    private static long cellStart(long total, int i) {
        return (long) ((double) total * i / MAP_WIDTH);
    }

    static String human(long bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    private static String eta(long seconds) {
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static long parseSize(String s) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.endsWith("IB")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("B")) {
            t = t.substring(0, t.length() - 1);
        }
        long mul = 1;
        if (!t.isEmpty()) {
            switch (t.charAt(t.length() - 1)) {
                case 'K' -> mul = 1L << 10;
                case 'M' -> mul = 1L << 20;
                case 'G' -> mul = 1L << 30;
                default -> { }
            }
        }
        if (mul != 1) {
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t.trim());
            if (!(v >= 0) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return (long) (v * mul);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid size: " + s);
        }
    }

    static final class Args {
        final List<String> urls = new ArrayList<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        Path dir = Path.of(".");
        Path output;
        int connections = 8;
        long limit;
        boolean help;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "-h", "--help" -> a.help = true;
                    case "-o", "--dir" -> a.dir = Path.of(value(argv, ++i, arg));
                    case "-O", "--output" -> a.output = Path.of(value(argv, ++i, arg));
                    case "-c", "--connections" -> {
                        try {
                            a.connections = Integer.parseInt(value(argv, ++i, arg));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--connections needs a number");
                        }
                        if (a.connections < 1 || a.connections > 32) {
                            throw new IllegalArgumentException("--connections must be between 1 and 32");
                        }
                    }
                    case "-l", "--limit" -> a.limit = parseSize(value(argv, ++i, arg));
                    case "-H", "--header" -> {
                        String h = value(argv, ++i, arg);
                        int colon = h.indexOf(':');
                        if (colon <= 0) {
                            throw new IllegalArgumentException("header must look like \"Name: value\"");
                        }
                        a.headers.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
                    }
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("unknown option " + arg);
                        }
                        a.urls.add(arg);
                    }
                }
            }
            if (!a.help && a.urls.isEmpty()) {
                throw new IllegalArgumentException("no URL given");
            }
            if (a.output != null && a.urls.size() > 1) {
                throw new IllegalArgumentException("--output can only be used with a single URL");
            }
            return a;
        }

        private static String value(String[] argv, int i, String option) {
            if (i >= argv.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return argv[i];
        }
    }
}

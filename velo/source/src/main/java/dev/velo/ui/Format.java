package dev.velo.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Format {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

    private Format() {}

    static String bytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format(Locale.ROOT, v < 10 ? "%.2f %s" : v < 100 ? "%.1f %s" : "%.0f %s", v, units[u]);
    }

    static String speed(double bytesPerSecond) {
        return bytes((long) bytesPerSecond) + "/s";
    }

    static String duration(long seconds) {
        if (seconds < 0) {
            return "";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m " + seconds % 60 + "s";
        }
        return seconds / 3600 + "h " + seconds / 60 % 60 + "m";
    }

    private static final DateTimeFormatter LAST_TRY = DateTimeFormatter.ofPattern("MMM dd hh:mm a", Locale.ENGLISH);

    /** Classic list-view date, e.g. "Oct 19 11:48 AM". */
    static String lastTry(long epochMillis) {
        return LAST_TRY.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    static String when(long epochMillis) {
        var t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
        return t.toLocalDate().equals(LocalDate.now()) ? "Today, " + TIME.format(t) : DATE.format(t);
    }
}

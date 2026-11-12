package dev.velo.ui;

import dev.velo.media.YtDlp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Starting velo in the background when the person signs in, so the browser always has
 * it to hand downloads to: the per-user Run key on Windows, an XDG autostart entry on
 * Linux. Neither needs admin rights.
 */
final class Startup {
    /** The argument that starts velo with no window, in the tray. */
    static final String BACKGROUND = "--background";

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String RUN_VALUE = "velo";
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private Startup() {}

    /**
     * The command that starts this velo in the background: the packaged launcher (velo.exe,
     * /opt/velo/bin/velo), or Java with velo.jar. Null when running from compiled classes.
     */
    static List<String> launchCommand() {
        // jpackage's launcher tells the app where it is.
        String app = System.getProperty("jpackage.app-path");
        if (app != null && !app.isBlank()) {
            return List.of(app, BACKGROUND);
        }
        try {
            Path jar = Path.of(Startup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!jar.toString().endsWith(".jar")) {
                return null;
            }
            Path java = Path.of(System.getProperty("java.home"), "bin", YtDlp.WINDOWS ? "javaw.exe" : "java");
            return List.of(java.toString(), "-jar", jar.toString(), BACKGROUND);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isSupported() {
        return (YtDlp.WINDOWS || LINUX) && launchCommand() != null;
    }

    static boolean isEnabled() {
        if (YtDlp.WINDOWS) {
            try {
                return run(List.of("reg", "query", RUN_KEY, "/v", RUN_VALUE)) == 0;
            } catch (IOException e) {
                return false;
            }
        }
        return LINUX && Files.isRegularFile(autostartFile());
    }

    static void setEnabled(boolean enabled) throws IOException {
        List<String> cmd = launchCommand();
        if (enabled && cmd == null) {
            throw new IOException("velo can only start with the system when it runs from its installed app or velo.jar");
        }
        if (YtDlp.WINDOWS) {
            // A .reg file avoids the command-line quoting pitfalls of `reg add /d "..."`.
            Path reg = Files.createTempFile("velo-startup", ".reg");
            try {
                byte[] text = regFile(enabled ? commandLine(cmd) : null).getBytes(StandardCharsets.UTF_16LE);
                byte[] withBom = new byte[text.length + 2];
                withBom[0] = (byte) 0xFF;
                withBom[1] = (byte) 0xFE;
                System.arraycopy(text, 0, withBom, 2, text.length);
                Files.write(reg, withBom);
                int code = run(List.of("reg", "import", reg.toString()));
                if (code != 0) {
                    throw new IOException("reg import failed with code " + code);
                }
            } finally {
                Files.deleteIfExists(reg);
            }
        } else if (LINUX) {
            Path file = autostartFile();
            if (enabled) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, desktopEntry(cmd));
            } else {
                Files.deleteIfExists(file);
            }
        }
    }

    /** A Windows command line: each part quoted when it contains spaces. */
    static String commandLine(List<String> cmd) {
        List<String> parts = new ArrayList<>();
        for (String p : cmd) {
            parts.add(p.contains(" ") || p.isEmpty() ? "\"" + p + "\"" : p);
        }
        return String.join(" ", parts);
    }

    /** The .reg file that sets (command) or removes (null) velo's Run value. */
    static String regFile(String command) {
        String value = command == null ? "-"
                : "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"
                + "\"" + RUN_VALUE + "\"=" + value + "\r\n";
    }

    /** An XDG autostart entry; Exec arguments quoted as the Desktop Entry spec asks. */
    static String desktopEntry(List<String> cmd) {
        List<String> exec = new ArrayList<>();
        for (String p : cmd) {
            boolean plain = p.matches("[A-Za-z0-9_./:=+,-]+");
            exec.add(plain ? p : "\"" + p.replaceAll("([\"`$\\\\])", "\\\\$1") + "\"");
        }
        return "[Desktop Entry]\nType=Application\nName=velo\nComment=Download manager\n"
                + "Exec=" + String.join(" ", exec) + "\nTerminal=false\nX-GNOME-Autostart-enabled=true\n";
    }

    static Path autostartFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("autostart").resolve("velo.desktop");
    }

    private static int run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            p.getInputStream().readAllBytes();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}

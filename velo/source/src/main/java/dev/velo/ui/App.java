package dev.velo.ui;

import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Desktop entry point: velo-ui [URL...] */
public final class App {
    /**
     * Held for the life of the process. A local in main() would be garbage-collected after
     * main() returns, closing the file and releasing the lock, and a second velo could start.
     */
    private static SingleInstance instanceLock;

    private App() {}

    public static void main(String[] args) {
        // FlatLaf's optional native helper is a DLL unpacked at runtime; application-control
        // policies often block that, and the app looks and works the same without it.
        System.setProperty("flatlaf.useNativeLibrary", "false");
        // High-DPI: Java 9+ is per-monitor DPI aware on Windows and scales all Swing painting by
        // the monitor's scale factor (125%, 150%, ...). Keep that on even if a launcher passes
        // -Dsun.java2d.uiScale.enabled=false; icons here are font glyphs and stay sharp.
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        // Render text with the user's ClearType / font-smoothing settings.
        System.setProperty("sun.awt.useSystemAAFontSettings", "on");
        Path store = DownloadManager.defaultStore();

        // --background (used when velo starts with the system): no window, just the tray icon.
        boolean background = List.of(args).contains(Startup.BACKGROUND);
        String[] urls = java.util.Arrays.stream(args).filter(a -> !a.equals(Startup.BACKGROUND)).toArray(String[]::new);

        // One velo per user: a second copy would overwrite the first one's download list.
        SingleInstance instance;
        try {
            instance = SingleInstance.acquire(store.getParent());
        } catch (IOException e) {
            instance = null;
        }
        instanceLock = instance;
        if (instance == null) {
            if (background) {
                System.exit(0); // already running: a sign-in start has nothing to add
                return;
            }
            if (!BrowserBridge.handOver(BrowserBridge.DEFAULT_PORT, List.of(urls))) {
                JOptionPane.showMessageDialog(null, "velo is already running.", "velo",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            System.exit(0);
            return;
        }

        DownloadManager manager = DownloadManager.open(store);
        // Finding Python and yt-dlp takes a second or two; do it off the startup path.
        Thread.ofPlatform().daemon().name("velo-detect").start(
                () -> manager.setMediaTools(detectMediaTools(manager)));
        MainWindow[] window = new MainWindow[1];
        String bridgeStatus;
        try {
            BrowserBridge bridge = BrowserBridge.start(BrowserBridge.DEFAULT_PORT,
                    r -> SwingUtilities.invokeLater(() -> {
                        if (window[0] != null) {
                            window[0].fromBrowser(r);
                        }
                    }));
            bridge.onActivate(() -> SwingUtilities.invokeLater(() -> {
                if (window[0] != null) {
                    window[0].showWindow();
                }
            }));
            bridgeStatus = "Browser integration on (127.0.0.1:" + bridge.port() + ")";
        } catch (IOException e) {
            bridgeStatus = "Browser integration off: port " + BrowserBridge.DEFAULT_PORT + " is in use";
        }

        // The installed app starts with the system by default, like other download managers,
        // so the browser always has velo to hand downloads to. Done once; Options can undo it.
        if (!manager.autostartOffered() && System.getProperty("jpackage.app-path") != null) {
            manager.setAutostartOffered(true);
            Thread.ofPlatform().daemon().name("velo-autostart").start(() -> {
                try {
                    Startup.setEnabled(true);
                } catch (IOException ignored) {
                    // Options shows the real state; nothing to report at startup.
                }
            });
        }

        String status = bridgeStatus;
        SwingUtilities.invokeLater(() -> {
            Theme.apply(manager.theme());
            MainWindow w = new MainWindow(manager, status);
            window[0] = w;
            Tray tray = Tray.install(w::showWindow, manager::pauseAll, w::exitApp);
            w.setTray(tray);
            if (background && tray != null) {
                // Stay in the tray; the browser bridge is already listening.
            } else if (background) {
                w.setExtendedState(Frame.ICONIFIED); // no tray on this desktop: start minimised
                w.setVisible(true);
            } else {
                w.setVisible(true);
            }
            for (String url : urls) {
                w.route(url);
            }
        });
    }

    /** The unpacked browser extension shipped next to velo.jar (or at the project root when run from source). */
    static Path extensionDir() {
        Path dir = bundledDir("extension", "manifest.json");
        return dir != null ? dir : Path.of("extension").toAbsolutePath();
    }

    /** Finds yt-dlp, ffmpeg and deno: velo's bundled "tools" folder first, then the system. */
    static dev.velo.media.YtDlp.Tools detectMediaTools(DownloadManager manager) {
        return dev.velo.media.YtDlp.detect(bundledDir("tools", dev.velo.media.YtDlp.exe("yt-dlp")),
                manager.dataDir().resolve("tools"));
    }

    /**
     * A folder shipped with velo, found by walking up from velo.jar: in a packaged app it sits
     * beside the "app" folder; when running from source, at the project root or under out\.
     */
    static Path bundledDir(String name, String markerFile) {
        try {
            Path here = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path p = here; p != null; p = p.getParent()) {
                for (Path candidate : new Path[] {p.resolve(name), p.resolve("out").resolve(name)}) {
                    if (Files.isRegularFile(candidate.resolve(markerFile))) {
                        return candidate;
                    }
                }
            }
        } catch (Exception ignored) {
            // Not found.
        }
        return null;
    }
}

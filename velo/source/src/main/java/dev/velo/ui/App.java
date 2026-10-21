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

        // One velo per user: a second copy would overwrite the first one's download list.
        SingleInstance instance;
        try {
            instance = SingleInstance.acquire(store.getParent());
        } catch (IOException e) {
            instance = null;
        }
        if (instance == null) {
            if (!BrowserBridge.handOver(BrowserBridge.DEFAULT_PORT, List.of(args))) {
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
                    if (window[0].getExtendedState() == Frame.ICONIFIED) {
                        window[0].setExtendedState(Frame.NORMAL);
                    }
                    window[0].toFront();
                    window[0].requestFocus();
                }
            }));
            bridgeStatus = "Browser integration on (127.0.0.1:" + bridge.port() + ")";
        } catch (IOException e) {
            bridgeStatus = "Browser integration off: port " + BrowserBridge.DEFAULT_PORT + " is in use";
        }

        String status = bridgeStatus;
        SwingUtilities.invokeLater(() -> {
            Theme.apply(manager.theme());
            window[0] = new MainWindow(manager, status);
            window[0].setVisible(true);
            for (String url : args) {
                window[0].route(url);
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

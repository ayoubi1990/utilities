package dev.velo.ui;

import dev.velo.core.TestServer;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Opens the real windows with demo downloads from a local server and saves
 * screenshots: UiPreview out-prefix [light|dark]
 */
public final class UiPreview {
    private UiPreview() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("flatlaf.useNativeLibrary", "false");
        String prefix = args[0];
        boolean dark = args.length > 1 && args[1].equals("dark");
        Path tmp = Files.createTempDirectory("velo-preview");

        try (TestServer fast = new TestServer(3 * 1024 * 1024); TestServer slow = new TestServer(48 * 1024 * 1024)) {
            slow.sleepMillisPerChunk = 25;
            DownloadManager manager = DownloadManager.open(tmp.resolve("store.properties"));
            manager.setTheme(dark ? Theme.DARK : Theme.LIGHT);
            manager.setShowComplete(false);
            MainWindow[] window = new MainWindow[1];
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(dark ? Theme.DARK : Theme.LIGHT);
                window[0] = new MainWindow(manager, "Browser integration on (127.0.0.1:47321)");
                window[0].setSize(1180, 600);
                window[0].setVisible(true);
            });

            add(manager, fast.url("/reports/Quarterly-Report-Q3.pdf"), tmp, true, "Finance report");
            add(manager, fast.url("/music/summer-album.zip"), tmp, true, "");
            Thread.sleep(1500);
            DownloadItem iso = add(manager, slow.url("/releases/ubuntu-24.04-desktop-amd64.iso"), tmp, true,
                    "Ubuntu desktop image");
            add(manager, fast.url("/videos/holiday-highlights.mp4"), tmp, false, "Holiday video");
            add(manager, "http://127.0.0.1:1/tools/setup-x64.exe", tmp, true, "");
            Thread.sleep(3500);
            SwingUtilities.invokeAndWait(() -> {
                window[0].select(iso);
                window[0].openProgress(iso);
            });
            Thread.sleep(1200);

            SwingUtilities.invokeAndWait(() -> {
                capture(window[0], prefix + "-main.png");
                for (Window w : window[0].getOwnedWindows()) {
                    if (w instanceof JDialog d && d.isVisible()) {
                        capture(d, prefix + "-progress.png");
                    }
                }
            });
            manager.shutdown();
        }
        System.exit(0);
    }

    private static DownloadItem add(DownloadManager m, String url, Path dir, boolean start, String description) {
        return m.add(new DownloadManager.Request(url, dir, null, 8, start, description, Map.of()));
    }

    /** Paints the whole window, including its menu bar, at the screen's scale. */
    private static void capture(Window w, String file) {
        Component root = w instanceof JFrame f ? f.getRootPane() : ((JDialog) w).getRootPane();
        double scale = w.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        BufferedImage img = new BufferedImage((int) Math.ceil(root.getWidth() * scale),
                (int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        root.paint(g);
        g.dispose();
        try {
            ImageIO.write(img, "png", Path.of(file).toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

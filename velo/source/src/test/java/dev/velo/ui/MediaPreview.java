package dev.velo.ui;

import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/** Screenshots the video quality picker for a page: MediaPreview URL out.png */
public final class MediaPreview {
    private MediaPreview() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("flatlaf.useNativeLibrary", "false");
        DownloadManager manager = DownloadManager.open(Files.createTempDirectory("velo-media").resolve("s.properties"));
        manager.setMediaTools(App.detectMediaTools(manager));
        SwingUtilities.invokeAndWait(() -> Theme.apply(Theme.LIGHT));
        SwingUtilities.invokeLater(() -> MediaDialog.show(null, manager, args[0], Map.of()));
        Thread.sleep(25_000); // yt-dlp reads the page
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isVisible()) {
                    var root = d.getRootPane();
                    double scale = d.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                    BufferedImage img = new BufferedImage((int) Math.ceil(root.getWidth() * scale),
                            (int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    g.scale(scale, scale);
                    root.paint(g);
                    g.dispose();
                    try {
                        ImageIO.write(img, "png", Path.of(args[1]).toFile());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
        });
        System.exit(0);
    }
}

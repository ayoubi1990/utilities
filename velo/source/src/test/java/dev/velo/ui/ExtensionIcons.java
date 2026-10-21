package dev.velo.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Renders the browser extension's icons from the app's tile icon: ExtensionIcons extension/icons */
public final class ExtensionIcons {
    private ExtensionIcons() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        for (int size : new int[] {16, 32, 48, 128}) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, size).paintIcon(null, g, 0, 0);
            g.dispose();
            ImageIO.write(img, "png", dir.resolve("icon" + size + ".png").toFile());
        }
    }
}

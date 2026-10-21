package dev.velo.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Writes the app icon as a multi-size Windows .ico (16-256 px, PNG-compressed
 * entries, as supported since Windows Vista): MakeIcon packaging/velo.ico
 */
public final class MakeIcon {
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};

    private MakeIcon() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        if (out.toString().endsWith(".png")) {
            // Linux packages take a single 256 px PNG.
            ImageIO.write(render(256), "png", out.toFile());
            System.out.println("wrote " + out);
            return;
        }
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(render(size), "png", png);
            images.add(png.toByteArray());
        }
        Files.write(out, ico(images));
        System.out.println("wrote " + out + " (" + SIZES.length + " sizes)");
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, size).paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }

    /** ICONDIR header, one ICONDIRENTRY per image, then the PNG data; all little-endian. */
    static byte[] ico(List<byte[]> pngs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(le16(0));
        out.write(le16(1)); // 1 = icon
        out.write(le16(pngs.size()));
        int offset = 6 + 16 * pngs.size();
        for (int i = 0; i < pngs.size(); i++) {
            int size = SIZES[i];
            out.writeByte(size >= 256 ? 0 : size); // 0 means 256
            out.writeByte(size >= 256 ? 0 : size);
            out.writeByte(0); // palette colours
            out.writeByte(0); // reserved
            out.write(le16(1)); // colour planes
            out.write(le16(32)); // bits per pixel
            out.write(le32(pngs.get(i).length));
            out.write(le32(offset));
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            out.write(png);
        }
        return bytes.toByteArray();
    }

    private static byte[] le16(int v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array();
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}

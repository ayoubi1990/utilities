package dev.velo.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * A glossy rounded tile in a solid colour with a white glyph on it: the
 * colourful toolbar style of classic download managers, drawn from scratch.
 */
final class TileIcon implements Icon {
    private final char glyph;
    private final Color color;
    private final int size;

    TileIcon(char glyph, Color color, int size) {
        this.glyph = glyph;
        this.color = color;
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean enabled = c == null || c.isEnabled();
            Color base = enabled ? color : desaturate(color);
            int arc = Math.max(4, size / 4);
            g2.setPaint(new GradientPaint(x, y, base.brighter(), x, y + size, base.darker()));
            g2.fillRoundRect(x, y, size, size, arc, arc);
            // Top highlight for a slight gloss.
            g2.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 110), x, y + size / 2f,
                    new Color(255, 255, 255, 0)));
            g2.fillRoundRect(x + 1, y + 1, size - 2, size / 2, arc, arc);
            g2.setColor(base.darker());
            g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc);
        } finally {
            g2.dispose();
        }
        int inner = Math.round(size * 0.58f);
        new Glyph(glyph, inner, Color.WHITE).paintIcon(null, g, x + (size - inner) / 2, y + (size - inner) / 2);
    }

    private static Color desaturate(Color c) {
        int gray = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        return new Color((gray + 2 * 0xB0) / 3, (gray + 2 * 0xB0) / 3, (gray + 2 * 0xB0) / 3);
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}

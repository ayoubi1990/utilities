package dev.velo.ui;

import dev.velo.core.Segment;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.swing.JComponent;

/**
 * IDM-style connection map: one bar for the whole file, with each
 * connection's range drawn where it sits and filled as its bytes arrive.
 */
final class SegmentMap extends JComponent {
    private List<Segment> segments = List.of();
    private long total;
    private double fraction;
    private Color fill = Theme.ACCENT;

    SegmentMap() {
        setPreferredSize(new Dimension(200, 14));
    }

    void set(List<Segment> segments, long total, double fraction, Color fill) {
        this.segments = segments;
        this.total = total;
        this.fraction = fraction;
        this.fill = fill;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Shape track = new RoundRectangle2D.Float(0, 0, w, h, h, h);
            g2.setColor(Theme.track());
            g2.fill(track);
            g2.clip(track);
            g2.setColor(fill);
            if (segments.isEmpty() || total <= 0) {
                // No live ranges (finished, paused, or single stream): show overall progress.
                if (fraction > 0) {
                    g2.fillRect(0, 0, (int) Math.round(w * fraction), h);
                }
                return;
            }
            Color gap = Theme.card();
            for (Segment s : segments) {
                int x0 = (int) Math.round((double) s.start() / total * w);
                int x1 = (int) Math.round((double) s.written() / total * w);
                if (x1 > x0) {
                    g2.setColor(fill);
                    g2.fillRect(x0, 0, x1 - x0, h);
                }
                if (s.start() > 0) {
                    // A thin notch marks where one connection's range begins.
                    g2.setColor(gap);
                    g2.fillRect(x0, 0, 1, h);
                }
            }
        } finally {
            g2.dispose();
        }
    }
}

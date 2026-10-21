package dev.velo.ui;

import java.awt.Component;
import java.util.Locale;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;

/** Compact, list-view style cell renderers for the download table. */
final class Renderers {
    static final int LEFT = SwingConstants.LEFT;
    static final int RIGHT = SwingConstants.RIGHT;
    static final int CENTER = SwingConstants.CENTER;

    private Renderers() {}

    /** Small file-type icon, then the file name. */
    static final class Name extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row,
                int col) {
            DownloadItem item = (DownloadItem) value;
            super.getTableCellRendererComponent(t, item.name, sel, false, row, col);
            DownloadItem.Type type = item.type();
            setIcon(new Glyph(type.glyph, 15, sel ? null : Theme.forType(type)));
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 4));
            setToolTipText(item.url);
            return this;
        }
    }

    /** Plain text derived from the item. */
    static final class Text extends DefaultTableCellRenderer {
        private final Function<DownloadItem, String> text;

        Text(int alignment, Function<DownloadItem, String> text) {
            this.text = text;
            setHorizontalAlignment(alignment);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row,
                int col) {
            super.getTableCellRendererComponent(t, text.apply((DownloadItem) value), sel, false, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            return this;
        }
    }

    /** The queue marker column: a small clock for items waiting their turn. */
    static final class Queue extends DefaultTableCellRenderer {
        Queue() {
            setHorizontalAlignment(CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row,
                int col) {
            super.getTableCellRendererComponent(t, "", sel, false, row, col);
            DownloadItem item = (DownloadItem) value;
            setIcon(item.status == DownloadItem.Status.QUEUED ? new Glyph(Glyph.CLOCK, 12) : null);
            return this;
        }
    }

    /** IDM-style status: a percentage while unfinished, otherwise a word, coloured for errors. */
    static final class Status extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row,
                int col) {
            DownloadItem item = (DownloadItem) value;
            super.getTableCellRendererComponent(t, statusText(item), sel, false, row, col);
            // Always set the colour: the renderer is shared, and a colour set for one row would stick.
            if (sel) {
                setForeground(t.getSelectionForeground());
            } else if (item.status == DownloadItem.Status.FAILED) {
                setForeground(Theme.RED);
            } else if (item.status == DownloadItem.Status.COMPLETED) {
                setForeground(Theme.GREEN);
            } else {
                setForeground(t.getForeground());
            }
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            setToolTipText(item.error);
            return this;
        }
    }

    static String statusText(DownloadItem item) {
        double f = item.fraction();
        return switch (item.status) {
            case DOWNLOADING, PAUSED -> f >= 0 ? String.format(Locale.ROOT, "%.2f%%", f * 100) : item.status.label;
            default -> item.status.label;
        };
    }
}

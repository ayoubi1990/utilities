package dev.velo.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * An icon drawn from Windows' built-in Segoe Fluent Icons font, so the app
 * ships no image files and icons stay crisp at any scale and in either theme.
 */
final class Glyph implements Icon {
    static final char ADD = '';
    static final char PLAY = '';
    static final char PAUSE = '';
    static final char DELETE = '';
    static final char FOLDER = '';
    static final char OPEN = '';
    static final char DOWNLOAD = '';
    static final char CHECK = '';
    static final char ERROR = '';
    static final char LIST = '';
    static final char SEARCH = '';
    static final char SUN = '';
    static final char MOON = '';
    static final char LINK = '';
    static final char COPY = '';
    static final char RETRY = '';
    static final char CLOCK = '';
    static final char SPEED = '';
    static final char FILE = '';
    static final char DOC = '';
    static final char VIDEO = '';
    static final char MUSIC = '';
    static final char PHOTO = '';
    static final char ZIP = '';
    static final char APP = '';
    static final char STOP = '';
    static final char SETTINGS = '';
    static final char CLEAR = '';
    static final char FOLDER_CLOSED = '';
    static final char GLOBE = '';
    static final char INFO = '';
    static final char QUEUE = '';

    /**
     * Where Windows' Segoe icon fonts are missing (Linux, macOS), the same icons come from
     * Microsoft's open-source Fluent UI System Icons font (MIT), bundled in the jar. Its
     * code points differ, so each Segoe character above maps to its Fluent equivalent.
     */
    private static final Map<Character, Character> FLUENT = Map.ofEntries(
            Map.entry(ADD, ''), Map.entry(PLAY, ''), Map.entry(PAUSE, ''),
            Map.entry(DELETE, ''), Map.entry(FOLDER, ''), Map.entry(OPEN, ''),
            Map.entry(DOWNLOAD, ''), Map.entry(CHECK, ''), Map.entry(ERROR, ''),
            Map.entry(LIST, ''), Map.entry(SEARCH, ''), Map.entry(SUN, ''),
            Map.entry(MOON, ''), Map.entry(LINK, ''), Map.entry(COPY, ''),
            Map.entry(RETRY, ''), Map.entry(CLOCK, ''), Map.entry(SPEED, ''),
            Map.entry(FILE, ''), Map.entry(DOC, ''), Map.entry(VIDEO, ''),
            Map.entry(MUSIC, ''), Map.entry(PHOTO, ''), Map.entry(ZIP, ''),
            Map.entry(APP, ''), Map.entry(STOP, ''), Map.entry(SETTINGS, ''),
            Map.entry(CLEAR, ''), Map.entry(FOLDER_CLOSED, ''), Map.entry(GLOBE, ''),
            Map.entry(INFO, ''));

    // -Dvelo.icons=fluent previews the non-Windows icons on Windows.
    private static final boolean FORCE_FLUENT = "fluent".equals(System.getProperty("velo.icons"));
    private static final Font SEGOE = FORCE_FLUENT ? null : pickSegoe();
    private static final Font BASE = SEGOE != null ? SEGOE : loadFluent();

    private final char ch;
    private final int size;
    private final Color color;

    Glyph(char ch, int size) {
        this(ch, size, null);
    }

    /** @param color fixed colour, or null to follow the component's foreground */
    Glyph(char ch, int size, Color color) {
        this.ch = SEGOE != null ? ch : FLUENT.getOrDefault(ch, ch);
        this.size = size;
        this.color = color;
    }

    static boolean available() {
        return BASE != null;
    }

    private static Font pickSegoe() {
        for (String name : new String[] {"Segoe Fluent Icons", "Segoe MDL2 Assets"}) {
            Font f = new Font(name, Font.PLAIN, 16);
            if (f.getFamily().equals(name) && f.canDisplay(ADD)) {
                return f;
            }
        }
        return null;
    }

    private static Font loadFluent() {
        try (InputStream in = Glyph.class.getResourceAsStream("FluentSystemIcons-Regular.ttf")) {
            return in == null ? null : Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(16f);
        } catch (IOException | FontFormatException e) {
            return null;
        }
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        if (BASE == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fg = color;
            if (c != null && !c.isEnabled()) {
                fg = UIManager.getColor("Label.disabledForeground");
            } else if (fg == null) {
                fg = c != null ? c.getForeground() : UIManager.getColor("Label.foreground");
            }
            g2.setColor(fg);
            g2.setFont(BASE.deriveFont((float) size));
            FontMetrics fm = g2.getFontMetrics();
            String s = String.valueOf(ch);
            int w = fm.stringWidth(s);
            g2.drawString(s, x + (size - w) / 2f, y + (size - fm.getHeight()) / 2f + fm.getAscent());
        } finally {
            g2.dispose();
        }
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

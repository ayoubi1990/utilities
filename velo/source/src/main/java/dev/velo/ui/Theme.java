package dev.velo.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Window;
import java.util.Arrays;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Look and feel. "light" and "dark" are compact FlatLaf themes in the spirit of
 * classic Windows download managers; "system" is Swing's native Windows look.
 * FlatLaf failing to load falls back to the native look rather than to Metal.
 */
final class Theme {
    static final String LIGHT = "light";
    static final String DARK = "dark";
    static final String SYSTEM = "system";

    static final Color ACCENT = new Color(0x1F6FD1);
    static final Color GREEN = new Color(0x2E9E45);
    static final Color RED = new Color(0xD7373F);
    static final Color AMBER = new Color(0xE0A100);
    static final Color FOLDER = new Color(0xE8B33A);

    private static String mode = LIGHT;

    private Theme() {}

    static boolean isDark() {
        return DARK.equals(mode);
    }

    static String mode() {
        return mode;
    }

    /** Must run on the event dispatch thread. */
    static void apply(String requested) {
        mode = requested == null ? LIGHT : requested;
        boolean applied = false;
        if (!SYSTEM.equals(mode)) {
            try {
                FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#1F6FD1"));
                applied = DARK.equals(mode) ? FlatDarkLaf.setup() : FlatLightLaf.setup();
                tuneFlatLaf();
            } catch (RuntimeException | LinkageError e) {
                applied = false;
            }
        }
        if (!applied) {
            mode = SYSTEM;
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Keep whatever look is active; the app still works.
            }
        }
        for (Window w : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(w);
        }
    }

    private static void tuneFlatLaf() {
        String family = pickFont();
        if (family != null) {
            UIManager.put("defaultFont", new Font(family, Font.PLAIN, 12));
        }
        UIManager.put("Component.arc", 4);
        UIManager.put("Button.arc", 4);
        UIManager.put("TextComponent.arc", 3);
        UIManager.put("ProgressBar.arc", 2);
        UIManager.put("ProgressBar.horizontalSize", new java.awt.Dimension(200, 16));
        UIManager.put("ScrollBar.showButtons", true);
        UIManager.put("Table.rowHeight", 22);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.cellMargins", new Insets(0, 4, 0, 4));
        UIManager.put("TableHeader.height", 24);
        UIManager.put("Tree.rowHeight", 22);
        UIManager.put("TabbedPane.tabHeight", 28);
        UIManager.put("ToolBar.borderMargins", new Insets(2, 4, 2, 4));
    }

    private static String pickFont() {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String want : new String[] {"Segoe UI", "Tahoma"}) {
            if (Arrays.asList(families).contains(want)) {
                return want;
            }
        }
        return null;
    }

    /** Background of the toolbar and side panels. */
    static Color chrome() {
        return isDark() ? new Color(0x2B2D30) : new Color(0xF0F0F0);
    }

    static Color card() {
        return UIManager.getColor("Table.background");
    }

    static Color muted() {
        return isDark() ? new Color(0x9AA0A6) : new Color(0x5F6368);
    }

    static Color hairline() {
        return isDark() ? new Color(0x3C3F41) : new Color(0xD5D5D5);
    }

    static Color track() {
        return isDark() ? new Color(0x3C3F41) : new Color(0xE3E3E3);
    }

    static Color link() {
        return isDark() ? new Color(0x6CA6F0) : new Color(0x0B57C2);
    }

    static Color forType(DownloadItem.Type type) {
        return switch (type) {
            case COMPRESSED -> new Color(0xC97A08);
            case DOCUMENTS -> new Color(0x1F6FD1);
            case MUSIC -> new Color(0xC2185B);
            case PROGRAMS -> new Color(0x6A3FC8);
            case VIDEO -> new Color(0xC62828);
            case IMAGES -> new Color(0x2E7D32);
            case OTHER -> muted();
        };
    }

    static Color forStatus(DownloadItem.Status status) {
        return switch (status) {
            case DOWNLOADING, CONNECTING -> ACCENT;
            case COMPLETED -> GREEN;
            case FAILED -> RED;
            case PAUSED -> AMBER;
            case QUEUED -> muted();
        };
    }
}

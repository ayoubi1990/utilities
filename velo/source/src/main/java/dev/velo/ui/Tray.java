package dev.velo.ui;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * velo's icon in the notification area: it keeps running there when its window is
 * closed, so the browser always has it to hand downloads to. Null where the desktop
 * has no tray (some Linux desktops); velo then behaves as an ordinary window.
 */
final class Tray {
    private final TrayIcon icon;

    private Tray(TrayIcon icon) {
        this.icon = icon;
    }

    static Tray install(Runnable open, Runnable stopAll, Runnable exit) {
        if (!SystemTray.isSupported()) {
            return null;
        }
        SystemTray tray = SystemTray.getSystemTray();
        Dimension size = tray.getTrayIconSize();
        int px = Math.max(16, Math.max(size.width, size.height));
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, px).paintIcon(null, g, 0, 0);
        g.dispose();

        PopupMenu menu = new PopupMenu();
        MenuItem openItem = new MenuItem("Open velo");
        openItem.setFont(new Font(Font.DIALOG, Font.BOLD, 12));
        openItem.addActionListener(e -> open.run());
        MenuItem stopItem = new MenuItem("Stop all downloads");
        stopItem.addActionListener(e -> stopAll.run());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> exit.run());
        menu.add(openItem);
        menu.add(stopItem);
        menu.addSeparator();
        menu.add(exitItem);

        TrayIcon icon = new TrayIcon(image, "velo", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> open.run()); // double-click on Windows
        try {
            tray.add(icon);
        } catch (AWTException e) {
            return null;
        }
        return new Tray(icon);
    }

    void tooltip(String text) {
        if (!text.equals(icon.getToolTip())) {
            icon.setToolTip(text);
        }
    }

    void notify(String title, String text) {
        icon.displayMessage(title, text, TrayIcon.MessageType.INFO);
    }

    void remove() {
        SystemTray.getSystemTray().remove(icon);
    }
}

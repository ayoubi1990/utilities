package dev.velo.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** Small layout helpers shared by the dialogs. */
final class Dialogs {
    private Dialogs() {}

    /** A two-column form: right-aligned captions, stretching values. */
    static final class Form extends JPanel {
        private int row;

        Form() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        Form row(String caption, Component value) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.gridx = 0;
            c.anchor = GridBagConstraints.LINE_START;
            c.insets = new Insets(3, 0, 3, 10);
            if (caption != null) {
                super.add(new JLabel(caption), c);
            }
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 0);
            super.add(value, c);
            return this;
        }

        Form wide(Component value) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 0);
            super.add(value, c);
            return this;
        }
    }

    static JPanel buttons(JButton... buttons) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        for (JButton b : buttons) {
            // A minimum width only; fixing the preferred size would clip the text when the
            // font changes later (default-button bold, theme switch, DPI change).
            b.putClientProperty("JComponent.minimumWidth", 88);
            p.add(b);
        }
        return p;
    }

    static void escapeCloses(JDialog d) {
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    /** A selectable-looking label that follows the theme's link colour. */
    static JLabel link(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public Color getForeground() {
                return Theme.link();
            }
        };
        l.setToolTipText(text);
        return l;
    }

    /** Keeps the lines that look like web links. */
    static List<String> parseLinks(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            String lower = s.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("http://") || lower.startsWith("https://")) && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    static String clipboardLinks() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return String.join("\n", parseLinks(String.valueOf(data)));
        } catch (Exception e) {
            return "";
        }
    }
}

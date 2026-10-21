package dev.velo.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** "Download complete": where the file went, with Open / Open folder / Close. */
final class CompleteDialog extends JDialog {
    CompleteDialog(Window owner, DownloadManager manager, DownloadItem item) {
        super(owner, "Download complete", ModalityType.MODELESS);
        JLabel icon = new JLabel(new TileIcon(Glyph.CHECK, Theme.GREEN, 48));
        icon.setVerticalAlignment(JLabel.TOP);
        icon.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 14));

        Dialogs.Form form = new Dialogs.Form();
        form.wide(Dialogs.bold("Download complete"));
        form.row("Downloaded", new JLabel(Format.bytes(item.total >= 0 ? item.total : item.downloaded)));
        form.row("Address", Dialogs.link(item.url));
        form.row("The file saved as", new JLabel(String.valueOf(item.path)));
        JCheckBox dontShow = new JCheckBox("Don't show this dialog again");
        form.wide(dontShow);

        JButton open = new JButton("Open");
        JButton folder = new JButton("Open folder");
        JButton close = new JButton("Close");
        open.addActionListener(e -> {
            openFile(item.path);
            dispose();
        });
        folder.addActionListener(e -> {
            showInFolder(item.path);
            dispose();
        });
        close.addActionListener(e -> dispose());
        dontShow.addActionListener(e -> manager.setShowComplete(!dontShow.isSelected()));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        root.add(icon, BorderLayout.WEST);
        root.add(form, BorderLayout.CENTER);
        root.add(Dialogs.buttons(open, folder, close), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(open);
        Dialogs.escapeCloses(this);
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setSize(Math.min(getWidth(), 760), getHeight());
        setLocationRelativeTo(owner);
    }

    static void openFile(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        Thread.ofPlatform().start(() -> open(p));
    }

    /** Opens a file or folder with the desktop's default handler (xdg-open where AWT can't). */
    private static void open(Path p) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(p.toFile());
            } else if (!dev.velo.media.YtDlp.WINDOWS) {
                new ProcessBuilder("xdg-open", p.toString()).start();
            }
        } catch (IOException | RuntimeException ignored) {
            // The OS shows its own "no app associated" message where relevant.
        }
    }

    /**
     * Explorer's "open the folder and highlight this file" command. The path must be its own
     * argument: Java quotes arguments that contain spaces, and a quoted "/select,C:\a b\c" is
     * not understood, so Explorer silently falls back to a default folder.
     */
    static java.util.List<String> explorerSelect(Path p) {
        return java.util.List.of("explorer.exe", "/select,", p.toAbsolutePath().toString());
    }

    static void showInFolder(Path p) {
        if (p == null) {
            return;
        }
        Thread.ofPlatform().start(() -> {
            try {
                if (dev.velo.media.YtDlp.WINDOWS && Files.exists(p)) {
                    new ProcessBuilder(explorerSelect(p)).start();
                } else if (p.getParent() != null && Files.isDirectory(p.getParent())) {
                    // Linux file managers have no common "select this file" command; open the folder.
                    open(p.getParent());
                }
            } catch (IOException | RuntimeException ignored) {
                // The folder may have been removed.
            }
        });
    }
}

package dev.velo.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * "Download File Info": confirm the address, pick a category and where to
 * save, then start now or later. Shown for Add URL and for browser hand-offs.
 */
final class DownloadInfoDialog extends JDialog {
    private final DownloadManager manager;
    private final JTextField url = new JTextField(44);
    private final JComboBox<DownloadItem.Type> category = new JComboBox<>(DownloadItem.Type.values());
    private final JTextField saveAs = new JTextField(44);
    private final JTextField description = new JTextField(44);
    private final Map<String, String> headers;
    private final String guessedName;
    private boolean nameEdited;
    private boolean updating;
    private DownloadManager.Request result;

    private DownloadInfoDialog(Window owner, DownloadManager manager, String address, String suggestedName,
            String desc, Map<String, String> headers) {
        super(owner, "Download File Info", ModalityType.APPLICATION_MODAL);
        this.manager = manager;
        this.headers = headers;
        this.guessedName = suggestedName != null && !suggestedName.isBlank()
                ? suggestedName : DownloadItem.guessName(address);

        url.setText(address);
        url.setCaretPosition(0);
        description.setText(desc == null ? "" : desc);
        DownloadItem.Type type = DownloadItem.Type.of(guessedName);
        category.setSelectedItem(type);
        category.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                DownloadItem.Type t = (DownloadItem.Type) value;
                setText(t.label);
                setIcon(new Glyph(Glyph.FOLDER_CLOSED, 14, Theme.FOLDER));
                return this;
            }
        });
        setSaveAs(manager.folderFor(type).resolve(guessedName));
        category.addActionListener(e -> {
            Path current = Path.of(saveAs.getText().trim());
            setSaveAs(manager.folderFor((DownloadItem.Type) category.getSelectedItem()).resolve(current.getFileName()));
        });
        saveAs.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });

        JButton browse = new JButton("…");
        browse.setToolTipText("Choose where to save");
        browse.addActionListener(e -> browse());
        JPanel saveRow = new JPanel(new BorderLayout(4, 0));
        saveRow.setOpaque(false);
        saveRow.add(saveAs, BorderLayout.CENTER);
        saveRow.add(browse, BorderLayout.EAST);

        Dialogs.Form form = new Dialogs.Form();
        form.row("URL", url);
        form.row("Category", category);
        form.row("Save As", saveRow);
        form.row("Description", description);
        JLabel note = new JLabel(headers.containsKey("Cookie") ? "Sent from your browser with its cookies and referrer."
                : headers.containsKey("Referer") ? "Sent from your browser." : " ") {
            @Override
            public java.awt.Color getForeground() {
                return Theme.muted();
            }
        };
        form.row(null, note);

        JLabel icon = new JLabel(new TileIcon(DownloadItem.Type.of(guessedName).glyph, Theme.forType(type), 48));
        icon.setVerticalAlignment(JLabel.TOP);
        icon.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 14));

        JButton start = new JButton("Start Download");
        JButton later = new JButton("Download Later");
        JButton cancel = new JButton("Cancel");
        start.addActionListener(e -> submit(true));
        later.addActionListener(e -> submit(false));
        cancel.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        root.add(icon, BorderLayout.WEST);
        root.add(form, BorderLayout.CENTER);
        root.add(Dialogs.buttons(start, later, cancel), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(start);
        Dialogs.escapeCloses(this);
        pack();
        setMinimumSize(new Dimension(620, getHeight()));
        setLocationRelativeTo(owner);
    }

    static Optional<DownloadManager.Request> show(Window owner, DownloadManager manager, String url,
            String suggestedName, String description, Map<String, String> headers) {
        DownloadInfoDialog d = new DownloadInfoDialog(owner, manager, url, suggestedName, description, headers);
        d.toFront();
        d.setVisible(true);
        return Optional.ofNullable(d.result);
    }

    private void setSaveAs(Path p) {
        updating = true;
        saveAs.setText(p.toString());
        updating = false;
    }

    private void edited() {
        if (!updating) {
            nameEdited = true;
        }
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(Path.of(saveAs.getText().trim()).toFile());
        chooser.setDialogTitle("Save As");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            setSaveAs(chooser.getSelectedFile().toPath());
            nameEdited = true;
        }
    }

    private void submit(boolean startNow) {
        var links = Dialogs.parseLinks(url.getText());
        if (links.isEmpty()) {
            JOptionPane.showMessageDialog(this, "The URL must start with http:// or https://", "Download File Info",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path target;
        try {
            target = Path.of(saveAs.getText().trim()).toAbsolutePath();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, "That is not a valid file path.", "Download File Info",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = target.getFileName().toString();
        // A guessed name without an extension (e.g. "download?id=7") is probably wrong; let the
        // server's Content-Disposition decide unless the user typed a name themselves.
        boolean letServerName = !nameEdited && !guessedName.contains(".");
        if (!letServerName && Files.exists(target)) {
            int answer = JOptionPane.showConfirmDialog(this, name + " already exists. Replace it?",
                    "Download File Info", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        result = new DownloadManager.Request(links.get(0), target.getParent(), letServerName ? null : name,
                manager.defaultConnections(), startNow, description.getText().trim(), headers);
        dispose();
    }
}

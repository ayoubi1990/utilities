package dev.velo.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/** Downloads > Options. */
final class OptionsDialog extends JDialog {
    OptionsDialog(Window owner, DownloadManager manager, String bridgeStatus) {
        super(owner, "velo Configuration", ModalityType.APPLICATION_MODAL);

        JTextField folder = new JTextField(manager.defaultDir().toString(), 34);
        JButton browse = new JButton("…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(folder.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);
        JLabel folderNote = new JLabel("Each category saves into its own sub-folder here (Video, Music, …).");
        folderNote.setForeground(Theme.muted());

        Dialogs.Form saveForm = new Dialogs.Form();
        saveForm.row("Default download folder", folderRow);
        saveForm.wide(folderNote);

        JSpinner conns = new JSpinner(new SpinnerNumberModel(manager.defaultConnections(), 1, 32, 1));
        JSpinner parallel = new JSpinner(new SpinnerNumberModel(manager.maxActive(), 1, 10, 1));
        long kb = manager.speedLimit() / 1024;
        JSpinner limit = new JSpinner(new SpinnerNumberModel((int) Math.min(kb, 1_000_000), 0, 1_000_000, 64));
        Dialogs.Form connForm = new Dialogs.Form();
        connForm.row("Max. connections per download", conns);
        connForm.row("Downloads at the same time", parallel);
        connForm.row("Speed limit, KB/sec (0 = none)", limit);

        JCheckBox progress = new JCheckBox("Show download progress window when a download starts",
                manager.showProgress());
        JCheckBox complete = new JCheckBox("Show download complete dialog", manager.showComplete());
        Dialogs.Form general = new Dialogs.Form();
        general.wide(progress);
        general.wide(complete);
        general.wide(new JLabel(" "));
        general.wide(Dialogs.bold("Browser integration"));
        general.wide(new JLabel(bridgeStatus));
        general.wide(new JLabel(" "));
        general.wide(Dialogs.bold("Video sites"));
        var tools = manager.mediaTools();
        general.wide(new JLabel(tools != null ? tools.describe() : "Not available (yt-dlp missing or blocked)"));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("General", pad(general));
        tabs.addTab("Save to", pad(saveForm));
        tabs.addTab("Connection", pad(connForm));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            manager.setDefaultDir(Path.of(folder.getText().trim()));
            manager.setDefaultConnections((Integer) conns.getValue());
            manager.setMaxActive((Integer) parallel.getValue());
            manager.setSpeedLimit(((Integer) limit.getValue()) * 1024L);
            manager.setShowProgress(progress.isSelected());
            manager.setShowComplete(complete.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(tabs, BorderLayout.CENTER);
        root.add(Dialogs.buttons(ok, cancel), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        Dialogs.escapeCloses(this);
        pack();
        setMinimumSize(new Dimension(560, 300));
        setLocationRelativeTo(owner);
    }

    private static JPanel pad(JPanel content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(content, BorderLayout.NORTH);
        return p;
    }
}

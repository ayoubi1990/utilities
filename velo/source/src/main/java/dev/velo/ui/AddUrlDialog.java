package dev.velo.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** The small "Enter new address to download" prompt shown by Add URL. */
final class AddUrlDialog extends JDialog {
    private final JTextField address = new JTextField(46);
    private String result;

    private AddUrlDialog(Window owner, String prefill) {
        super(owner, "Enter new address to download", ModalityType.APPLICATION_MODAL);
        JLabel icon = new JLabel(new TileIcon(Glyph.LINK, Theme.ACCENT, 32));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        address.setText(prefill);
        address.selectAll();

        Dialogs.Form form = new Dialogs.Form();
        form.row("Address:", address);
        JLabel error = new JLabel(" ");
        error.setForeground(Theme.RED);
        form.wide(error);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            var links = Dialogs.parseLinks(address.getText());
            if (links.isEmpty()) {
                error.setText("Enter an address that starts with http:// or https://");
                return;
            }
            result = links.get(0);
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        root.add(icon, BorderLayout.WEST);
        root.add(form, BorderLayout.CENTER);
        root.add(Dialogs.buttons(ok, cancel), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        Dialogs.escapeCloses(this);
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    static Optional<String> show(Window owner) {
        AddUrlDialog d = new AddUrlDialog(owner, Dialogs.clipboardLinks().lines().findFirst().orElse(""));
        d.setVisible(true);
        return Optional.ofNullable(d.result);
    }
}

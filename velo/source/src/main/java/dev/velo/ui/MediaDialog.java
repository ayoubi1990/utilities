package dev.velo.ui;

import dev.velo.media.MediaInfo;
import dev.velo.media.YtDlp;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The video quality picker: asks yt-dlp what the page offers, lists the
 * qualities with their sizes, and returns the user's choice.
 */
final class MediaDialog extends JDialog {
    /** What the user chose: download the media, or fall back to a plain file download. */
    sealed interface Choice {
        record Media(DownloadManager.MediaRequest request) implements Choice {}

        record AsFile() implements Choice {}
    }

    private final DownloadManager manager;
    private final String pageUrl;
    private final Map<String, String> headers;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel title = new JLabel("Getting video information…");
    private final JLabel subtitle = mutedLabel();
    private final DefaultListModel<MediaInfo.Option> options = new DefaultListModel<>();
    private final JList<MediaInfo.Option> list = new JList<>(options);
    private final JTextField folder = new JTextField(40);
    private final JTextArea error = new JTextArea(4, 40);
    private final JButton start = new JButton("Start Download");
    private final JButton later = new JButton("Download Later");
    private final JButton asFile = new JButton("Download as file");
    private boolean folderEdited;
    private MediaInfo info;
    private Choice result;

    private MediaDialog(Window owner, DownloadManager manager, String pageUrl, Map<String, String> headers) {
        super(owner, "Download video", ModalityType.APPLICATION_MODAL);
        this.manager = manager;
        this.pageUrl = pageUrl;
        this.headers = headers;

        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        subtitle.setText(host(pageUrl));
        JPanel head = new JPanel(new BorderLayout(0, 3));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(subtitle, BorderLayout.SOUTH);
        JLabel icon = new JLabel(new TileIcon(Glyph.VIDEO, new Color(0xC62828), 48));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
        JPanel top = new JPanel(new BorderLayout());
        top.add(icon, BorderLayout.WEST);
        top.add(head, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        // Loading card.
        JProgressBar spinner = new JProgressBar();
        spinner.setIndeterminate(true);
        JPanel loading = new JPanel(new BorderLayout(0, 8));
        loading.add(new JLabel("Asking yt-dlp which qualities this page offers…"), BorderLayout.NORTH);
        loading.add(spinner, BorderLayout.CENTER);
        JPanel loadingWrap = new JPanel(new BorderLayout());
        loadingWrap.add(loading, BorderLayout.NORTH);
        body.add(loadingWrap, "loading");

        // Choices card.
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new OptionRenderer());
        list.setVisibleRowCount(7);
        list.addListSelectionListener(e -> updateFolder());
        JPanel choose = new JPanel(new BorderLayout(0, 8));
        choose.add(Dialogs.bold("Quality"), BorderLayout.NORTH);
        choose.add(new JScrollPane(list), BorderLayout.CENTER);
        JButton browse = new JButton("…");
        browse.addActionListener(e -> browse());
        JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folderRow.add(new JLabel("Save to  "), BorderLayout.WEST);
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);
        choose.add(folderRow, BorderLayout.SOUTH);
        body.add(choose, "choices");
        folder.setText(manager.folderFor(DownloadItem.Type.VIDEO).toString());
        folder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                folderEdited = folder.hasFocus() || folderEdited;
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                folderEdited = folder.hasFocus() || folderEdited;
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });

        // Error card.
        error.setEditable(false);
        error.setLineWrap(true);
        error.setWrapStyleWord(true);
        error.setOpaque(false);
        JPanel errorPanel = new JPanel(new BorderLayout());
        errorPanel.add(error, BorderLayout.NORTH);
        body.add(errorPanel, "error");

        start.addActionListener(e -> choose(true));
        later.addActionListener(e -> choose(false));
        asFile.addActionListener(e -> {
            result = new Choice.AsFile();
            dispose();
        });
        asFile.setToolTipText("Treat the address as a normal file instead of a video page");
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        start.setEnabled(false);
        later.setEnabled(false);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(Dialogs.buttons(asFile), BorderLayout.WEST);
        buttons.add(Dialogs.buttons(start, later, cancel), BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        root.add(top, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(start);
        Dialogs.escapeCloses(this);
        // Wide enough for the four buttons at any font size; pack() would size to the
        // short loading card instead.
        setSize(Math.max(780, getPreferredSize().width), 440);
        setLocationRelativeTo(owner);
        Thread.ofPlatform().daemon().name("velo-probe").start(this::probe);
    }

    static Optional<Choice> show(Window owner, DownloadManager manager, String pageUrl, Map<String, String> headers) {
        MediaDialog d = new MediaDialog(owner, manager, pageUrl, headers);
        d.setVisible(true);
        return Optional.ofNullable(d.result);
    }

    private void probe() {
        YtDlp.Tools tools = manager.mediaTools();
        if (tools == null) {
            tools = App.detectMediaTools(manager);
            manager.setMediaTools(tools);
        }
        if (tools == null) {
            fail(DownloadManager.MEDIA_MISSING + "\n\n(Running from source? Install yt-dlp with:\n"
                    + YtDlp.INSTALL_HINT + ")");
            return;
        }
        try {
            MediaInfo found = YtDlp.probe(tools, pageUrl, headers);
            boolean merge = tools.ffmpeg();
            SwingUtilities.invokeLater(() -> loaded(found, merge));
        } catch (IOException e) {
            fail("yt-dlp could not read this page:\n" + e.getMessage()
                    + "\n\nIf the address points straight to a file, use \"Download as file\".");
        }
    }

    private void loaded(MediaInfo found, boolean canMerge) {
        info = found;
        title.setText(found.title());
        String duration = found.durationSeconds() > 0 ? "   ·   " + Format.duration((long) found.durationSeconds()) : "";
        String note = canMerge ? "" : "   ·   install ffmpeg for higher qualities";
        subtitle.setText((found.site().isEmpty() ? host(pageUrl) : found.site()) + duration + note);
        if (found.options().isEmpty()) {
            fail("yt-dlp found no downloadable video on this page.");
            return;
        }
        found.options().forEach(options::addElement);
        list.setSelectedIndex(0);
        cards.show(body, "choices");
        start.setEnabled(true);
        later.setEnabled(true);
        list.requestFocusInWindow();
    }

    private void fail(String message) {
        SwingUtilities.invokeLater(() -> {
            title.setText("Can't download this page");
            error.setText(message);
            cards.show(body, "error");
        });
    }

    private void updateFolder() {
        MediaInfo.Option o = list.getSelectedValue();
        if (o != null && !folderEdited) {
            folder.setText(manager.folderFor(o.audioOnly() ? DownloadItem.Type.MUSIC : DownloadItem.Type.VIDEO).toString());
        }
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser(folder.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folder.setText(chooser.getSelectedFile().getAbsolutePath());
            folderEdited = true;
        }
    }

    private void choose(boolean startNow) {
        MediaInfo.Option o = list.getSelectedValue();
        if (o == null || info == null) {
            return;
        }
        Path dir = Path.of(folder.getText().trim()).toAbsolutePath();
        result = new Choice.Media(new DownloadManager.MediaRequest(pageUrl, dir, info.title(), o.ext(), o.selector(),
                o.label(), o.audioOnly(), o.sizeEstimate(), startNow, headers));
        dispose();
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static JLabel mutedLabel() {
        return new JLabel() {
            @Override
            public Color getForeground() {
                return Theme.muted();
            }
        };
    }

    /** A quality row: icon, label and approximate size. */
    private static final class OptionRenderer extends JPanel implements ListCellRenderer<MediaInfo.Option> {
        private final JLabel label = new JLabel();
        private final JLabel size = new JLabel();

        OptionRenderer() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 10));
            label.setIconTextGap(8);
            add(label, BorderLayout.CENTER);
            add(size, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MediaInfo.Option> list, MediaInfo.Option o,
                int index, boolean selected, boolean focus) {
            label.setText(o.label());
            label.setIcon(new Glyph(o.audioOnly() ? Glyph.MUSIC : Glyph.VIDEO, 15,
                    selected ? list.getSelectionForeground() : o.audioOnly() ? new Color(0xC2185B) : new Color(0xC62828)));
            size.setText(o.sizeEstimate() > 0 ? "~" + Format.bytes(o.sizeEstimate()) : "");
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            label.setForeground(fg);
            size.setForeground(selected ? fg : Theme.muted());
            setPreferredSize(new Dimension(10, 30));
            return this;
        }
    }
}

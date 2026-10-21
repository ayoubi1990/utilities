package dev.velo.ui;

import dev.velo.core.Segment;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

/**
 * The per-download window: status figures, a progress bar and, under
 * "Show details", the connection map and a row per connection.
 */
final class ProgressDialog extends JDialog {
    private final DownloadManager manager;
    private final DownloadItem item;
    private final JLabel url;
    private final JLabel status = new JLabel();
    private final JLabel size = new JLabel();
    private final JLabel done = new JLabel();
    private final JLabel rate = new JLabel();
    private final JLabel left = new JLabel();
    private final JLabel resume = new JLabel();
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final SegmentMap map = new SegmentMap();
    private final ConnectionsModel connections = new ConnectionsModel();
    private final JPanel details = new JPanel(new BorderLayout(0, 6));
    private final JButton toggleDetails = new JButton();
    private final JButton pause = new JButton("Pause");

    ProgressDialog(Window owner, DownloadManager manager, DownloadItem item) {
        super(owner, "", ModalityType.MODELESS);
        this.manager = manager;
        this.item = item;
        url = Dialogs.link(item.url);

        Dialogs.Form form = new Dialogs.Form();
        form.row("", url);
        form.row("Status", status);
        form.row("File size", size);
        form.row("Downloaded", done);
        form.row("Transfer rate", rate);
        form.row("Time left", left);
        form.row("Resume capability", resume);

        bar.setPreferredSize(new Dimension(420, 18));
        JPanel statusTab = new JPanel(new BorderLayout(0, 10));
        statusTab.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        statusTab.add(form, BorderLayout.CENTER);
        statusTab.add(bar, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Download status", statusTab);
        tabs.addTab("Speed Limiter", speedTab());
        tabs.addTab("Options on completion", completionTab());

        toggleDetails.addActionListener(e -> setDetailsVisible(!details.isVisible()));
        pause.addActionListener(e -> {
            if (item.status.isRunning() || item.status == DownloadItem.Status.QUEUED) {
                manager.pause(List.of(item));
            } else {
                manager.resume(List.of(item));
            }
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            manager.pause(List.of(item));
            dispose();
        });
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        buttons.add(toggleDetails, BorderLayout.WEST);
        JPanel right = Dialogs.buttons(pause, cancel);
        right.setBorder(null);
        buttons.add(right, BorderLayout.EAST);

        JTable table = new JTable(connections);
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 130));
        map.setPreferredSize(new Dimension(420, 12));
        JPanel mapBox = new JPanel(new BorderLayout(0, 4));
        mapBox.add(new JLabel("Start positions and download progress by connections"), BorderLayout.NORTH);
        mapBox.add(map, BorderLayout.CENTER);
        details.add(mapBox, BorderLayout.NORTH);
        details.add(scroll, BorderLayout.CENTER);
        details.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(tabs, BorderLayout.NORTH);
        root.add(buttons, BorderLayout.CENTER);
        root.add(details, BorderLayout.SOUTH);
        setContentPane(root);
        Dialogs.escapeCloses(this);
        setDetailsVisible(true);
        setResizable(false);
        setLocationRelativeTo(owner);
        refresh();
    }

    DownloadItem item() {
        return item;
    }

    private JPanel speedTab() {
        JCheckBox use = new JCheckBox("Use speed limiter", manager.speedLimit() > 0);
        long kb = manager.speedLimit() > 0 ? manager.speedLimit() / 1024 : 1024;
        JSpinner value = new JSpinner(new SpinnerNumberModel((int) Math.min(kb, 1_000_000), 8, 1_000_000, 64));
        Runnable apply = () -> manager.setSpeedLimit(use.isSelected() ? ((Integer) value.getValue()) * 1024L : 0);
        use.addActionListener(e -> apply.run());
        value.addChangeListener(e -> {
            if (use.isSelected()) {
                apply.run();
            }
        });
        Dialogs.Form form = new Dialogs.Form();
        form.wide(use);
        form.row("Maximum download speed (KB/sec)", value);
        JLabel note = new JLabel("The limit applies to all downloads together.");
        note.setForeground(Theme.muted());
        form.wide(note);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(form, BorderLayout.NORTH);
        return p;
    }

    private JPanel completionTab() {
        JCheckBox complete = new JCheckBox("Show download complete dialog", manager.showComplete());
        complete.addActionListener(e -> manager.setShowComplete(complete.isSelected()));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(complete, BorderLayout.NORTH);
        return p;
    }

    private void setDetailsVisible(boolean visible) {
        details.setVisible(visible);
        toggleDetails.setText(visible ? "<< Hide details" : "Show details >>");
        pack();
    }

    void refresh() {
        DownloadItem i = item;
        double f = i.fraction();
        String pct = f >= 0 ? String.format(Locale.ROOT, "%.0f%% ", f * 100) : "";
        setTitle(pct + i.name);
        status.setText(switch (i.status) {
            case DOWNLOADING -> i.isMedia() && i.stage != null ? i.stage : "Receiving data…";
            case CONNECTING -> "Connecting…";
            case QUEUED -> "Waiting in queue";
            case PAUSED -> "Paused";
            case COMPLETED -> "Complete";
            case FAILED -> "Error: " + (i.error == null ? "unknown" : i.error);
        });
        size.setText(i.total >= 0 ? Format.bytes(i.total) : "Unknown");
        done.setText(Format.bytes(i.downloaded) + (f >= 0 ? String.format(Locale.ROOT, " (%.2f %%)", f * 100) : ""));
        rate.setText(i.status == DownloadItem.Status.DOWNLOADING ? Format.speed(i.speed) : "—");
        String eta = Format.duration(i.etaSeconds());
        left.setText(eta.isEmpty() ? "—" : eta);
        resume.setText(i.status == DownloadItem.Status.CONNECTING ? "…" : i.resumable ? "Yes" : "No");
        bar.setIndeterminate(f < 0 && i.status.isRunning());
        bar.setValue(f < 0 ? 0 : (int) (f * 1000));
        boolean running = i.status.isRunning() || i.status == DownloadItem.Status.QUEUED;
        pause.setText(running ? "Pause" : "Resume");
        pause.setEnabled(i.status != DownloadItem.Status.COMPLETED);
        boolean live = i.status == DownloadItem.Status.DOWNLOADING;
        map.set(live ? i.segments : List.of(), i.total, Math.max(0, f), Theme.ACCENT);
        connections.update(live ? i.segments : List.of(), live);
    }

    /** One row per connection range: number, bytes received, and what it is doing. */
    private static final class ConnectionsModel extends AbstractTableModel {
        private List<Segment> segments = List.of();
        private boolean live;

        void update(List<Segment> segments, boolean live) {
            boolean sameSize = segments.size() == this.segments.size();
            this.segments = segments;
            this.live = live;
            if (sameSize) {
                fireTableRowsUpdated(0, Math.max(0, segments.size() - 1));
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return segments.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"N.", "Downloaded", "Info"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (r >= segments.size()) {
                return "";
            }
            Segment s = segments.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> Format.bytes(s.written() - s.start());
                default -> s.isDone() ? "Finished" : live ? "Receiving data…" : "Paused";
            };
        }
    }
}

package dev.velo.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

/**
 * The main window in the classic download-manager layout: menu bar, a big
 * icon toolbar, a categories tree on the left and the download list.
 */
final class MainWindow extends JFrame {
    private final DownloadManager manager;
    private final String bridgeStatus;
    private final DownloadTableModel model = new DownloadTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<DownloadTableModel> sorter = new TableRowSorter<>(model);
    private final JLabel status = new JLabel();
    private final AtomicBoolean reloadPending = new AtomicBoolean();
    private final Map<Long, ProgressDialog> progressDialogs = new HashMap<>();
    private Predicate<DownloadItem> categoryFilter = i -> true;
    private JSplitPane split;
    private JComponent categories;

    private final List<Runnable> enablers = new ArrayList<>();

    MainWindow(DownloadManager manager, String bridgeStatus) {
        super("velo");
        this.manager = manager;
        this.bridgeStatus = bridgeStatus;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setIconImages(List.of(appIcon(16), appIcon(32), appIcon(48), appIcon(64)));
        setMinimumSize(new Dimension(820, 480));
        setSize(1180, 680);
        setLocationRelativeTo(null);

        setJMenuBar(menuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(center(), BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        setContentPane(root);

        installKeys();
        manager.addListener(this::scheduleReload);
        manager.onCompleted(item -> SwingUtilities.invokeLater(() -> completed(item)));
        reload();
        new Timer(250, e -> tick()).start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                table.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                setTitle("velo — saving…");
                Thread.ofPlatform().start(() -> {
                    manager.shutdown();
                    System.exit(0);
                });
            }
        });
    }

    // ---- layout -----------------------------------------------------------------------------------

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu tasks = new JMenu("Tasks");
        tasks.add(item("Add new download…", Glyph.ADD, KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK),
                this::addUrl));
        tasks.add(item("Add video page (YouTube, Vimeo…)…", Glyph.VIDEO, null, this::addVideoPage));
        tasks.add(item("Add from clipboard", Glyph.LINK, KeyStroke.getKeyStroke(KeyEvent.VK_V,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), this::addFromClipboard));
        tasks.addSeparator();
        tasks.add(item("Exit", (char) 0, null, () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));

        JMenu file = new JMenu("File");
        file.add(item("Open", Glyph.OPEN, null, () -> single(i -> CompleteDialog.openFile(i.path))));
        file.add(item("Open folder", Glyph.FOLDER, null, () -> single(i -> CompleteDialog.showInFolder(
                i.path != null ? i.path : i.dir.resolve(i.name)))));
        file.add(item("Copy address", Glyph.COPY, null, this::copyLinks));
        file.addSeparator();
        file.add(item("Delete…", Glyph.DELETE, null, this::deleteSelected));

        JMenu downloads = new JMenu("Downloads");
        downloads.add(item("Resume", Glyph.PLAY, null, () -> manager.resume(selected())));
        downloads.add(item("Stop", Glyph.STOP, null, () -> manager.pause(selected())));
        downloads.add(item("Stop all", Glyph.STOP, null, manager::pauseAll));
        downloads.addSeparator();
        downloads.add(item("Delete completed", Glyph.CLEAR, null, this::deleteCompleted));
        downloads.addSeparator();
        JCheckBoxMenuItem limiter = new JCheckBoxMenuItem("Speed limiter on", manager.speedLimit() > 0);
        limiter.addActionListener(e -> toggleLimiter(limiter));
        downloads.add(limiter);
        downloads.add(item("Options…", Glyph.SETTINGS, null, this::options));

        JMenu view = new JMenu("View");
        ButtonGroup themes = new ButtonGroup();
        String[][] choices = {{Theme.LIGHT, "Light theme"}, {Theme.DARK, "Dark theme"},
                {Theme.SYSTEM, dev.velo.media.YtDlp.WINDOWS ? "Windows native look" : "System look"}};
        for (String[] c : choices) {
            JRadioButtonMenuItem option = new JRadioButtonMenuItem(c[1], c[0].equals(Theme.mode()));
            option.addActionListener(e -> setTheme(c[0]));
            themes.add(option);
            view.add(option);
        }
        view.addSeparator();
        JCheckBoxMenuItem showCategories = new JCheckBoxMenuItem("Categories", true);
        showCategories.addActionListener(e -> {
            categories.setVisible(showCategories.isSelected());
            split.setDividerSize(showCategories.isSelected() ? 5 : 0);
            split.resetToPreferredSizes();
        });
        view.add(showCategories);

        JMenu help = new JMenu("Help");
        help.add(item("Browser integration…", Glyph.GLOBE, null, this::browserHelp));
        help.add(item("Update video support", Glyph.RETRY, null, this::updateVideoSupport));
        help.add(item("About velo", Glyph.INFO, null, () -> JOptionPane.showMessageDialog(this,
                "velo 0.1\nA fast, resumable, multi-connection download manager.", "About velo",
                JOptionPane.INFORMATION_MESSAGE, new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, 48))));

        bar.add(tasks);
        bar.add(file);
        bar.add(downloads);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private JComponent toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.hairline()),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        bar.add(tool("Add URL", Glyph.ADD, new Color(0x1F6FD1), this::addUrl, () -> true));
        bar.add(tool("Resume", Glyph.PLAY, new Color(0x2E9E45), () -> manager.resume(selected()),
                () -> canResume(selected())));
        bar.add(tool("Stop", Glyph.STOP, new Color(0xD7373F), () -> manager.pause(selected()),
                () -> canStop(selected())));
        bar.add(tool("Stop All", Glyph.STOP, new Color(0x9C1F28), manager::pauseAll,
                () -> canStop(model.rows())));
        bar.add(tool("Delete", Glyph.DELETE, new Color(0x6B7280), this::deleteSelected,
                () -> !selected().isEmpty()));
        bar.add(tool("Delete Co…", Glyph.CLEAR, new Color(0x8A6D3B), this::deleteCompleted,
                () -> model.rows().stream().anyMatch(i -> i.status == DownloadItem.Status.COMPLETED)));
        bar.add(tool("Options", Glyph.SETTINGS, new Color(0x4B5563), this::options, () -> true));
        bar.addSeparator(new Dimension(12, 40));
        bar.add(tool("Start Queue", Glyph.PLAY, new Color(0x0E8A7E), manager::resumeAll,
                () -> canResume(model.rows())));
        bar.add(tool("Stop Queue", Glyph.PAUSE, new Color(0xC27C0E), manager::pauseAll,
                () -> canStop(model.rows())));
        bar.add(javax.swing.Box.createHorizontalGlue());

        JLabel brand = new JLabel("velo", new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, 34), SwingConstants.LEFT) {
            @Override
            public Color getForeground() {
                return Theme.muted();
            }
        };
        brand.setFont(brand.getFont().deriveFont(Font.BOLD | Font.ITALIC, 22f));
        brand.setIconTextGap(8);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        bar.add(brand);
        return bar;
    }

    private JButton tool(String text, char glyph, Color color, Runnable action, java.util.function.BooleanSupplier enabled) {
        // At least 76 x 62, wider when the label needs it (fonts differ between Windows and Linux).
        JButton b = new JButton(text, new TileIcon(glyph, color, 32)) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(Math.max(76, super.getPreferredSize().width + 6), 62);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        b.setVerticalTextPosition(SwingConstants.BOTTOM);
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        b.setIconTextGap(3);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.addActionListener(e -> action.run());
        enablers.add(() -> b.setEnabled(enabled.getAsBoolean()));
        return b;
    }

    private JComponent center() {
        table.setRowSorter(sorter);
        for (int c = 0; c < model.getColumnCount(); c++) {
            sorter.setComparator(c, DownloadTableModel.comparator(c));
        }
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getTableHeader().setReorderingAllowed(true);
        column(DownloadTableModel.NAME, 300, new Renderers.Name());
        column(DownloadTableModel.QUEUE, 28, new Renderers.Queue());
        column(DownloadTableModel.SIZE, 80, new Renderers.Text(Renderers.RIGHT, i -> i.total >= 0 ? Format.bytes(i.total) : ""));
        column(DownloadTableModel.STATUS, 90, new Renderers.Status());
        column(DownloadTableModel.ETA, 80, new Renderers.Text(Renderers.LEFT, i -> Format.duration(i.etaSeconds())));
        column(DownloadTableModel.RATE, 95, new Renderers.Text(Renderers.LEFT,
                i -> i.status == DownloadItem.Status.DOWNLOADING ? Format.speed(i.speed) : ""));
        column(DownloadTableModel.LAST_TRY, 130, new Renderers.Text(Renderers.LEFT,
                i -> Format.lastTry(i.lastTryAt > 0 ? i.lastTryAt : i.addedAt)));
        column(DownloadTableModel.DESCRIPTION, 200, new Renderers.Text(Renderers.LEFT, i -> i.description));
        table.getColumnModel().getColumn(DownloadTableModel.QUEUE).setMaxWidth(32);

        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    primaryAction();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());

        CategoryTree tree = new CategoryTree(filter -> {
            categoryFilter = filter;
            applyFilter();
        });
        JLabel header = new JLabel("  Categories");
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.hairline()),
                BorderFactory.createEmptyBorder(5, 2, 5, 2)));
        JPanel side = new JPanel(new BorderLayout());
        side.add(header, BorderLayout.NORTH);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        side.add(treeScroll, BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(200, 0));
        side.setMinimumSize(new Dimension(140, 0));
        categories = side;

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side, tableScroll);
        split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        return split;
    }

    private void column(int index, int width, javax.swing.table.TableCellRenderer renderer) {
        TableColumn col = table.getColumnModel().getColumn(index);
        col.setPreferredWidth(width);
        col.setCellRenderer(renderer);
    }

    private JComponent statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.hairline()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        bar.add(status, BorderLayout.WEST);
        JLabel bridge = new JLabel(bridgeStatus, new Glyph(Glyph.GLOBE, 13), SwingConstants.LEFT) {
            @Override
            public Color getForeground() {
                return Theme.muted();
            }
        };
        bar.add(bridge, BorderLayout.EAST);
        return bar;
    }

    // ---- actions ----------------------------------------------------------------------------------

    private void installKeys() {
        bind(getRootPane(), KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), "paste", () -> {
            if (!(getFocusOwner() instanceof javax.swing.text.JTextComponent)) {
                addFromClipboard();
            }
        });
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected);
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", this::primaryAction);
    }

    private static void bind(JComponent c, KeyStroke key, String name, Runnable action) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private void addUrl() {
        AddUrlDialog.show(this).ifPresent(this::route);
    }

    private void addVideoPage() {
        AddUrlDialog.show(this).ifPresent(url -> addMedia(url, Map.of()));
    }

    private void addFromClipboard() {
        String links = Dialogs.clipboardLinks();
        if (links.isEmpty()) {
            addUrl();
            return;
        }
        for (String url : links.lines().toList()) {
            route(url);
        }
    }

    /** Video-site pages go to the quality picker; everything else is a file. */
    void route(String url) {
        if (MediaSites.isVideoPage(url)) {
            addMedia(url, Map.of());
        } else {
            confirmAndAdd(url, null, "", Map.of());
        }
    }

    /** Asks yt-dlp for the page's qualities, then queues the chosen one. */
    void addMedia(String pageUrl, Map<String, String> headers) {
        MediaDialog.show(this, manager, pageUrl, headers).ifPresent(choice -> {
            switch (choice) {
                case MediaDialog.Choice.Media m -> {
                    DownloadItem item = manager.addMedia(m.request());
                    reload();
                    select(item);
                    if (m.request().start() && manager.showProgress()) {
                        showProgress(item);
                    }
                }
                case MediaDialog.Choice.AsFile f -> confirmAndAdd(pageUrl, null, "", headers);
            }
        });
    }

    /** Opens Download File Info, then adds the download and (optionally) its progress window. */
    void confirmAndAdd(String url, String fileName, String description, Map<String, String> headers) {
        DownloadInfoDialog.show(this, manager, url, fileName, description, headers).ifPresent(req -> {
            DownloadItem item = manager.add(req);
            reload();
            select(item);
            if (req.start() && manager.showProgress()) {
                showProgress(item);
            }
        });
    }

    /** Called (on the EDT) when the browser extension hands over a link. */
    void fromBrowser(BrowserBridge.Request r) {
        if (getExtendedState() == Frame.ICONIFIED) {
            setExtendedState(Frame.NORMAL);
        }
        toFront();
        Map<String, String> headers = new LinkedHashMap<>();
        if (r.referrer() != null && !r.referrer().isBlank()) {
            headers.put("Referer", r.referrer());
        }
        if (r.userAgent() != null && !r.userAgent().isBlank()) {
            headers.put("User-Agent", r.userAgent());
        }
        if (r.cookie() != null && !r.cookie().isBlank()) {
            headers.put("Cookie", r.cookie());
        }
        if (r.isPage()) {
            addMedia(r.url(), headers);
            return;
        }
        confirmAndAdd(r.url(), r.fileName(), r.title() == null ? "" : r.title(), headers);
    }

    private void showProgress(DownloadItem item) {
        ProgressDialog existing = progressDialogs.get(item.id);
        if (existing != null && existing.isDisplayable()) {
            existing.toFront();
            return;
        }
        ProgressDialog d = new ProgressDialog(this, manager, item);
        progressDialogs.put(item.id, d);
        d.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                progressDialogs.remove(item.id);
            }
        });
        d.setVisible(true);
    }

    private void completed(DownloadItem item) {
        ProgressDialog d = progressDialogs.remove(item.id);
        if (d != null) {
            d.dispose();
        }
        if (manager.showComplete()) {
            new CompleteDialog(this, manager, item).setVisible(true);
        }
    }

    private void primaryAction() {
        single(i -> {
            if (i.status == DownloadItem.Status.COMPLETED) {
                CompleteDialog.openFile(i.path);
            } else {
                showProgress(i);
            }
        });
    }

    private void single(java.util.function.Consumer<DownloadItem> action) {
        List<DownloadItem> sel = selected();
        if (sel.size() == 1) {
            action.accept(sel.get(0));
        }
    }

    private void copyLinks() {
        List<DownloadItem> sel = selected();
        if (!sel.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(String.join("\n", sel.stream().map(i -> i.url).toList())), null);
        }
    }

    private void deleteSelected() {
        List<DownloadItem> sel = selected();
        if (sel.isEmpty()) {
            return;
        }
        JCheckBox files = new JCheckBox("Also delete the files from disk");
        String what = sel.size() == 1 ? "\"" + sel.get(0).name + "\"" : sel.size() + " downloads";
        JPanel msg = new JPanel(new BorderLayout(0, 10));
        msg.add(new JLabel("Delete " + what + " from the list?"), BorderLayout.NORTH);
        msg.add(files, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, msg, "Confirm deletion", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) {
            sel.forEach(i -> {
                ProgressDialog d = progressDialogs.remove(i.id);
                if (d != null) {
                    d.dispose();
                }
            });
            manager.remove(sel, files.isSelected());
        }
    }

    private void deleteCompleted() {
        long n = model.rows().stream().filter(i -> i.status == DownloadItem.Status.COMPLETED).count();
        if (n > 0 && JOptionPane.showConfirmDialog(this, "Remove " + n + " completed download"
                + (n == 1 ? "" : "s") + " from the list? The files stay on disk.", "Delete completed",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            manager.removeCompleted();
        }
    }

    private void toggleLimiter(JCheckBoxMenuItem toggle) {
        if (toggle.isSelected()) {
            String answer = JOptionPane.showInputDialog(this, "Maximum download speed (KB/sec):", "1024");
            try {
                long kb = Long.parseLong(answer == null ? "" : answer.trim());
                manager.setSpeedLimit(Math.max(8, kb) * 1024);
            } catch (NumberFormatException e) {
                toggle.setSelected(false);
            }
        } else {
            manager.setSpeedLimit(0);
        }
    }

    private void options() {
        new OptionsDialog(this, manager, bridgeStatus).setVisible(true);
    }

    private void browserHelp() {
        String dir = App.extensionDir().toString();
        JPanel msg = new JPanel(new BorderLayout(0, 8));
        msg.add(new JLabel("<html><b>Add the velo extension to Chrome or Edge</b><br><br>"
                + "1. Open <b>chrome://extensions</b> (or <b>edge://extensions</b>).<br>"
                + "2. Turn on <b>Developer mode</b>.<br>"
                + "3. Click <b>Load unpacked</b> and choose this folder:</html>"), BorderLayout.NORTH);
        javax.swing.JTextField path = new javax.swing.JTextField(dir);
        path.setEditable(false);
        msg.add(path, BorderLayout.CENTER);
        msg.add(new JLabel("<html>Videos then get a <b>Download with velo</b> button, and links get a "
                + "right-click menu item.<br>Status: " + bridgeStatus + "</html>"), BorderLayout.SOUTH);
        Object[] options = {"Open folder", "Close"};
        if (JOptionPane.showOptionDialog(this, msg, "Browser integration", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, new TileIcon(Glyph.GLOBE, Theme.ACCENT, 40), options, options[1]) == 0) {
            CompleteDialog.showInFolder(App.extensionDir().resolve("manifest.json"));
        }
    }

    /** Video sites change often; this updates yt-dlp (the per-user copy, or the Python package). */
    private void updateVideoSupport() {
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        status.setText("Updating video support…");
        Thread.ofPlatform().daemon().name("velo-update").start(() -> {
            String message;
            int type = JOptionPane.INFORMATION_MESSAGE;
            try {
                dev.velo.media.YtDlp.Tools tools = manager.mediaTools();
                if (tools == null) {
                    tools = App.detectMediaTools(manager);
                }
                if (tools == null) {
                    throw new java.io.IOException(DownloadManager.MEDIA_MISSING);
                }
                String report = dev.velo.media.YtDlp.update(tools);
                dev.velo.media.YtDlp.Tools fresh = App.detectMediaTools(manager);
                manager.setMediaTools(fresh);
                String last = report.lines().reduce((a, b) -> b).orElse("");
                message = "Video support is up to date.\n\n" + (fresh != null ? fresh.describe() : "") + "\n" + last;
            } catch (java.io.IOException e) {
                message = "Could not update video support:\n" + e.getMessage();
                type = JOptionPane.WARNING_MESSAGE;
            }
            String text = message;
            int kind = type;
            SwingUtilities.invokeLater(() -> {
                setCursor(java.awt.Cursor.getDefaultCursor());
                JOptionPane.showMessageDialog(this, text, "Update video support", kind);
            });
        });
    }

    private void setTheme(String mode) {
        Theme.apply(mode);
        manager.setTheme(Theme.mode());
        repaint();
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        List<DownloadItem> sel = selected();
        if (sel.isEmpty()) {
            return;
        }
        boolean one = sel.size() == 1;
        JPopupMenu menu = new JPopupMenu();
        menu.add(enabled(item("Open", Glyph.OPEN, null, () -> CompleteDialog.openFile(sel.get(0).path)),
                one && sel.get(0).status == DownloadItem.Status.COMPLETED));
        menu.add(enabled(item("Open folder", Glyph.FOLDER, null, () -> CompleteDialog.showInFolder(
                sel.get(0).path != null ? sel.get(0).path : sel.get(0).dir.resolve(sel.get(0).name))), one));
        menu.add(enabled(item("Show progress", Glyph.SPEED, null, () -> showProgress(sel.get(0))),
                one && sel.get(0).status != DownloadItem.Status.COMPLETED));
        menu.addSeparator();
        menu.add(enabled(item("Resume", Glyph.PLAY, null, () -> manager.resume(sel)), canResume(sel)));
        menu.add(enabled(item("Stop", Glyph.STOP, null, () -> manager.pause(sel)), canStop(sel)));
        menu.add(item("Copy address", Glyph.COPY, null, this::copyLinks));
        menu.addSeparator();
        menu.add(item("Delete…", Glyph.DELETE, null, this::deleteSelected));
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, char glyph, KeyStroke key, Runnable action) {
        JMenuItem item = new JMenuItem(text, glyph == 0 ? null : new Glyph(glyph, 14));
        if (key != null) {
            item.setAccelerator(key);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private static JMenuItem enabled(JMenuItem item, boolean enabled) {
        item.setEnabled(enabled);
        return item;
    }

    // ---- refresh ----------------------------------------------------------------------------------

    private void applyFilter() {
        Predicate<DownloadItem> filter = categoryFilter;
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DownloadTableModel, ? extends Integer> entry) {
                return filter.test(entry.getModel().at(entry.getIdentifier()));
            }
        });
    }

    private void scheduleReload() {
        if (reloadPending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                reloadPending.set(false);
                reload();
            });
        }
    }

    /** Refreshes the rows while keeping the user's selection. */
    private void reload() {
        Set<Long> keep = new HashSet<>();
        selected().forEach(i -> keep.add(i.id));
        model.setRows(manager.items());
        applyFilter();
        for (int view = 0; view < table.getRowCount(); view++) {
            if (keep.contains(model.at(table.convertRowIndexToModel(view)).id)) {
                table.addRowSelectionInterval(view, view);
            }
        }
        updateButtons();
    }

    private void tick() {
        double speed = manager.poll();
        if (model.getRowCount() > 0) {
            model.fireTableRowsUpdated(0, model.getRowCount() - 1);
        }
        progressDialogs.values().forEach(ProgressDialog::refresh);
        updateButtons();
        List<DownloadItem> all = model.rows();
        long running = all.stream().filter(i -> i.status.isRunning()).count();
        String limit = manager.speedLimit() > 0 ? "   |   Speed limiter: " + Format.speed(manager.speedLimit()) : "";
        status.setText(all.size() + (all.size() == 1 ? " download" : " downloads") + "   |   " + running
                + " active   |   " + Format.speed(speed) + limit);
    }

    private void updateButtons() {
        enablers.forEach(Runnable::run);
    }

    private static boolean canResume(List<DownloadItem> sel) {
        return sel.stream().anyMatch(i -> i.status == DownloadItem.Status.PAUSED
                || i.status == DownloadItem.Status.FAILED);
    }

    private static boolean canStop(List<DownloadItem> sel) {
        return sel.stream().anyMatch(i -> i.status.isRunning() || i.status == DownloadItem.Status.QUEUED);
    }

    /** Selects one item's row, if it is visible under the current filter. */
    void select(DownloadItem item) {
        for (int view = 0; view < table.getRowCount(); view++) {
            if (model.at(table.convertRowIndexToModel(view)) == item) {
                table.setRowSelectionInterval(view, view);
                table.scrollRectToVisible(table.getCellRect(view, 0, true));
                return;
            }
        }
    }

    /** Opens the progress window for an item (used by the preview harness too). */
    void openProgress(DownloadItem item) {
        showProgress(item);
    }

    private List<DownloadItem> selected() {
        List<DownloadItem> out = new ArrayList<>();
        for (int view : table.getSelectedRows()) {
            out.add(model.at(table.convertRowIndexToModel(view)));
        }
        return out;
    }

    private static Image appIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        new TileIcon(Glyph.DOWNLOAD, Theme.ACCENT, size).paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }
}

package dev.velo.ui;

import java.awt.Color;
import java.awt.Component;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/** The "Categories" tree: all downloads by file type, unfinished, finished and the queue. */
final class CategoryTree extends JTree {
    record Category(String label, Predicate<DownloadItem> filter, Color color) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final DefaultMutableTreeNode all;

    CategoryTree(Consumer<Predicate<DownloadItem>> onSelect) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        all = node(new Category("All Downloads", i -> true, Theme.FOLDER));
        for (DownloadItem.Type type : DownloadItem.Type.values()) {
            all.add(node(new Category(type.label, i -> i.type() == type, Theme.FOLDER)));
        }
        root.add(all);
        root.add(node(new Category("Unfinished", i -> i.status != DownloadItem.Status.COMPLETED, Theme.ACCENT)));
        root.add(node(new Category("Finished", i -> i.status == DownloadItem.Status.COMPLETED, Theme.GREEN)));
        DefaultMutableTreeNode queues = node(new Category("Queues",
                i -> i.status == DownloadItem.Status.QUEUED || i.status.isRunning(), Theme.FOLDER));
        queues.add(node(new Category("Main download queue",
                i -> i.status == DownloadItem.Status.QUEUED || i.status.isRunning(), Theme.muted())));
        root.add(queues);

        setModel(new DefaultTreeModel(root));
        setRootVisible(false);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
        setCellRenderer(new Renderer());
        addTreeSelectionListener(e -> {
            Object last = e.getPath().getLastPathComponent();
            if (last instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Category c) {
                onSelect.accept(c.filter());
            }
        });
        setSelectionPath(new TreePath(all.getPath()));
    }

    private static DefaultMutableTreeNode node(Category c) {
        return new DefaultMutableTreeNode(c);
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus);
            if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Category c) {
                char glyph = c.label().equals("Main download queue") ? Glyph.CLOCK
                        : expanded && !leaf ? Glyph.FOLDER : Glyph.FOLDER_CLOSED;
                setIcon(new Glyph(glyph, 15, c.color()));
            }
            return this;
        }
    }
}

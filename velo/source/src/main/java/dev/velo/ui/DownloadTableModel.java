package dev.velo.ui;

import java.util.Comparator;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Every column holds the item itself; renderers and comparators pick what they need. */
final class DownloadTableModel extends AbstractTableModel {
    static final int NAME = 0;
    static final int QUEUE = 1;
    static final int SIZE = 2;
    static final int STATUS = 3;
    static final int ETA = 4;
    static final int RATE = 5;
    static final int LAST_TRY = 6;
    static final int DESCRIPTION = 7;
    private static final String[] COLUMNS =
            {"File Name", "Q", "Size", "Status", "Time left", "Transfer rate", "Last Try Date", "Description"};

    private List<DownloadItem> rows = List.of();

    void setRows(List<DownloadItem> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    DownloadItem at(int modelRow) {
        return rows.get(modelRow);
    }

    List<DownloadItem> rows() {
        return rows;
    }

    static Comparator<DownloadItem> comparator(int column) {
        return switch (column) {
            case NAME -> Comparator.comparing((DownloadItem i) -> i.name, String.CASE_INSENSITIVE_ORDER);
            case QUEUE -> Comparator.comparing((DownloadItem i) -> i.status == DownloadItem.Status.QUEUED);
            case SIZE -> Comparator.comparingLong(i -> i.total);
            case STATUS -> Comparator.comparing((DownloadItem i) -> i.status).thenComparingDouble(DownloadItem::fraction);
            case ETA -> Comparator.comparingLong(DownloadItem::etaSeconds);
            case RATE -> Comparator.comparingDouble(i -> i.speed);
            case LAST_TRY -> Comparator.comparingLong(i -> Math.max(i.lastTryAt, i.addedAt));
            default -> Comparator.comparing((DownloadItem i) -> i.description, String.CASE_INSENSITIVE_ORDER);
        };
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return DownloadItem.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        return rows.get(row);
    }
}

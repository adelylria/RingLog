package com.adelylria.ringlog.ui.model;

import java.util.List;

import javax.swing.table.AbstractTableModel;

import com.adelylria.ringlog.model.view.BirdEventListItem;

public class BirdEventTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
            "Anilla",
            "Fecha",
            "Tipo",
            "Especie",
            "Lugar"
    };

    private List<BirdEventListItem> events;

    public BirdEventTableModel(List<BirdEventListItem> events) {
        this.events = List.copyOf(events);
    }

    @Override
    public int getRowCount() {
        return events.size();
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
    public Object getValueAt(int rowIndex, int columnIndex) {
        BirdEventListItem event = events.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> event.ringNumber();
            case 1 -> event.eventDate();
            case 2 -> event.eventType().toString();
            case 3 -> event.species();
            case 4 -> event.place();
            default -> "";
        };
    }

    public BirdEventListItem getEventAt(int row) {
        return events.get(row);
    }

    public void setEvents(List<BirdEventListItem> events) {
        this.events = List.copyOf(events);
        fireTableDataChanged();
    }
}

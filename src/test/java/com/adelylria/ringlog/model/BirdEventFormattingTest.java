package com.adelylria.ringlog.model;

import java.util.List;

import com.adelylria.ringlog.model.view.BirdEventListItem;
import com.adelylria.ringlog.ui.model.BirdEventTableModel;
import com.adelylria.ringlog.ui.theme.UiKit;

public final class BirdEventFormattingTest {

    private BirdEventFormattingTest() {
    }

    public static void databaseTypesUseNaturalSpanishLabels() {
        require(EventType.fromDatabase("RINGING") == EventType.RINGING,
                "RINGING should map to its event type");
        require(EventType.fromDatabase("control") == EventType.CONTROL,
                "Event type parsing should ignore case");
        require("Recuperación".equals(EventType.RECOVERY.toString()),
                "Recovery should use natural UI language");
    }

    public static void eventTableIncludesTheTypeColumn() {
        BirdEventListItem item = new BirdEventListItem(
                1L,
                "V25943",
                "2025-12-20",
                EventType.CONTROL,
                "TURDUS PHILOMELOS",
                "ELS RAFALS"
        );
        BirdEventTableModel model = new BirdEventTableModel(List.of(item));

        require(model.getColumnCount() == 5,
                "The event table should expose five useful columns");
        require("Tipo".equals(model.getColumnName(2)),
                "The third column should identify the event type");
        require("Control".equals(model.getValueAt(0, 2)),
                "The event type should be readable instead of a database code");
    }

    public static void journalFormattingHandlesMissingValuesAndDates() {
        require("—".equals(UiKit.display(null)),
                "Missing values should use an em dash");
        require("22 ago 2026".equals(UiKit.date("2026-08-22")),
                "ISO dates should be readable in the journal");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        databaseTypesUseNaturalSpanishLabels();
        eventTableIncludesTheTypeColumn();
        journalFormattingHandlesMissingValuesAndDates();
        System.out.println("BirdEventFormattingTest: PASS");
    }
}

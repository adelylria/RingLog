package com.adelylria.ringlog.ui.components;

import java.awt.FlowLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import javax.swing.JComboBox;
import javax.swing.JPanel;

public final class DateSelector extends JPanel {

    private static final String[] MONTHS = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };

    private final JComboBox<String> daySelector;
    private final JComboBox<String> monthSelector;
    private final JComboBox<String> yearSelector;
    private boolean adjusting;

    public DateSelector(LocalDate initialDate) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);
        setName("dateSelector");
        getAccessibleContext().setAccessibleName("Fecha del evento");

        this.daySelector = new JComboBox<>();
        this.monthSelector = new JComboBox<>(MONTHS);
        this.yearSelector = new JComboBox<>();
        daySelector.setName("dateDaySelector");
        monthSelector.setName("dateMonthSelector");
        yearSelector.setName("dateYearSelector");
        daySelector.getAccessibleContext().setAccessibleName("Día del evento");
        monthSelector.getAccessibleContext().setAccessibleName("Mes del evento");
        yearSelector.getAccessibleContext().setAccessibleName("Año del evento");

        for (int year = 1900; year <= 2100; year++) {
            yearSelector.addItem(String.valueOf(year));
        }
        monthSelector.addActionListener(event -> refreshDays());
        yearSelector.addActionListener(event -> refreshDays());

        add(daySelector);
        add(monthSelector);
        add(yearSelector);
        setDate(initialDate);
    }

    public String getIsoDate() {
        return selectedDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public void setDate(LocalDate date) {
        adjusting = true;
        yearSelector.setSelectedItem(String.valueOf(date.getYear()));
        monthSelector.setSelectedIndex(date.getMonthValue() - 1);
        populateDays(date.lengthOfMonth(), date.getDayOfMonth());
        adjusting = false;
    }

    private void refreshDays() {
        if (adjusting) {
            return;
        }
        int selectedDay = Integer.parseInt((String) daySelector.getSelectedItem());
        populateDays(selectedDateWithoutDay().lengthOfMonth(), selectedDay);
    }

    private void populateDays(int maximumDay, int selectedDay) {
        adjusting = true;
        daySelector.removeAllItems();
        for (int day = 1; day <= maximumDay; day++) {
            daySelector.addItem(String.valueOf(day));
        }
        daySelector.setSelectedItem(String.valueOf(Math.min(selectedDay, maximumDay)));
        adjusting = false;
    }

    private LocalDate selectedDate() {
        return selectedDateWithoutDay().withDayOfMonth(
                Integer.parseInt((String) daySelector.getSelectedItem())
        );
    }

    private LocalDate selectedDateWithoutDay() {
        return LocalDate.of(
                Integer.parseInt((String) yearSelector.getSelectedItem()),
                monthSelector.getSelectedIndex() + 1,
                1
        );
    }
}

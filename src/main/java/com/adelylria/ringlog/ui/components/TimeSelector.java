package com.adelylria.ringlog.ui.components;

import java.awt.FlowLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class TimeSelector extends JPanel {

    private final JComboBox<String> hourSelector;
    private final JComboBox<String> minuteSelector;

    public TimeSelector(LocalTime initialTime) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);
        setName("timeSelector");
        getAccessibleContext().setAccessibleName("Hora del evento");

        this.hourSelector = new JComboBox<>(values(24));
        this.minuteSelector = new JComboBox<>(values(60));
        hourSelector.setName("timeHourSelector");
        minuteSelector.setName("timeMinuteSelector");
        hourSelector.getAccessibleContext().setAccessibleName("Hora del evento");
        minuteSelector.getAccessibleContext().setAccessibleName("Minuto del evento");

        add(hourSelector);
        add(new JLabel(":"));
        add(minuteSelector);
        setTime(initialTime);
    }

    public String getIsoTime() {
        if (hourSelector.getSelectedIndex() == 0 || minuteSelector.getSelectedIndex() == 0) {
            return "";
        }
        return LocalTime.of(
                Integer.parseInt((String) hourSelector.getSelectedItem()),
                Integer.parseInt((String) minuteSelector.getSelectedItem())
        ).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public void setTime(LocalTime time) {
        if (time == null) {
            hourSelector.setSelectedIndex(0);
            minuteSelector.setSelectedIndex(0);
            return;
        }
        hourSelector.setSelectedItem(String.format("%02d", time.getHour()));
        minuteSelector.setSelectedItem(String.format("%02d", time.getMinute()));
    }

    private static String[] values(int limit) {
        String[] values = new String[limit + 1];
        values[0] = "—";
        for (int value = 0; value < limit; value++) {
            values[value + 1] = String.format("%02d", value);
        }
        return values;
    }
}

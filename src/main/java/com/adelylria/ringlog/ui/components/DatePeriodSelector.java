package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.YearMonth;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** A bounded month/year editor that does not build a list of every month in the archive. */
public final class DatePeriodSelector extends JPanel {
    public static final String PERIOD_PROPERTY = "period";
    private static final String[] MONTHS = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };
    private final JComboBox<String> month = new JComboBox<>(MONTHS);
    private final JSpinner year;
    private boolean updating;

    public DatePeriodSelector(String namePrefix, String description) {
        super(new BorderLayout(8, 0));
        setOpaque(false);
        YearMonth now = YearMonth.now();
        year = new JSpinner(new SpinnerNumberModel(now.getYear(), 1, 9999, 1));
        year.setEditor(new JSpinner.NumberEditor(year, "#"));
        month.setSelectedIndex(now.getMonthValue() - 1);
        month.setName(namePrefix + "Month");
        year.setName(namePrefix + "Year");
        month.getAccessibleContext().setAccessibleName("Mes " + description.toLowerCase(java.util.Locale.ROOT));
        year.getAccessibleContext().setAccessibleName("Año " + description.toLowerCase(java.util.Locale.ROOT));
        month.setPreferredSize(new Dimension(135, 38));
        year.setPreferredSize(new Dimension(92, 38));
        add(month, BorderLayout.CENTER);
        add(year, BorderLayout.EAST);
        month.addActionListener(event -> notifyChange());
        year.addChangeListener(event -> notifyChange());
    }

    public YearMonth getPeriod() {
        return YearMonth.of(((Number) year.getValue()).intValue(), month.getSelectedIndex() + 1);
    }

    public void setPeriod(YearMonth period) {
        YearMonth previous = getPeriod();
        updating = true;
        try {
            year.setValue(period.getYear());
            month.setSelectedIndex(period.getMonthValue() - 1);
        } finally {
            updating = false;
        }
        firePropertyChange(PERIOD_PROPERTY, previous, getPeriod());
    }

    public void setYearOnly(boolean yearOnly) {
        month.setVisible(!yearOnly);
        year.setPreferredSize(new Dimension(92, 38));
        setPreferredSize(new Dimension(yearOnly ? 92 : 235, 38));
        revalidate();
    }

    private void notifyChange() {
        if (!updating) firePropertyChange(PERIOD_PROPERTY, null, getPeriod());
    }
}

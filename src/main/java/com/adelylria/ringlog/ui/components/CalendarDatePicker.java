package com.adelylria.ringlog.ui.components;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import com.adelylria.ringlog.ui.theme.RoundedBorder;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public final class CalendarDatePicker extends JPanel {

    public static final String SELECTED_DATE_PROPERTY = "selectedDate";

    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter BUTTON_FORMATTER = DateTimeFormatter
            .ofPattern("d MMM uuuu", SPANISH);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter
            .ofPattern("MMMM uuuu", SPANISH);
    private static final DateTimeFormatter ACCESSIBLE_FORMATTER = DateTimeFormatter
            .ofPattern("EEEE, d 'de' MMMM 'de' uuuu", SPANISH);
    private static final String[] WEEKDAYS = {"L", "M", "X", "J", "V", "S", "D"};

    private final JButton triggerButton;
    private final JPopupMenu popup;
    private final JLabel monthLabel;
    private final JPanel dayGrid;
    private final Set<LocalDate> availableDates = new HashSet<>();
    private LocalDate selectedDate;
    private YearMonth displayedMonth;

    public CalendarDatePicker(LocalDate initialDate) {
        super(new BorderLayout());
        setOpaque(false);
        setName("calendarDatePicker");
        getAccessibleContext().setAccessibleName("Filtro de fecha por día");

        selectedDate = initialDate;
        displayedMonth = YearMonth.from(initialDate == null
                ? LocalDate.now()
                : initialDate);

        triggerButton = UiKit.secondaryButton("");
        triggerButton.setName("calendarPickerButton");
        triggerButton.setIcon(new CalendarGlyph());
        triggerButton.setIconTextGap(9);
        triggerButton.setHorizontalAlignment(SwingConstants.LEFT);
        triggerButton.getAccessibleContext().setAccessibleName(
                "Abrir calendario para filtrar por día"
        );
        add(triggerButton, BorderLayout.CENTER);

        popup = new JPopupMenu();
        popup.setName("calendarPopup");
        popup.setBorder(new RoundedBorder(
                null,
                18,
                1,
                new Insets(12, 12, 12, 12)
        ));

        monthLabel = UiKit.valueLabel("");
        monthLabel.setHorizontalAlignment(SwingConstants.CENTER);
        dayGrid = new JPanel(new GridLayout(0, 7, 4, 4));
        dayGrid.setOpaque(false);

        popup.add(createCalendarContent());
        setComponentPopupMenu(popup);
        triggerButton.addActionListener(event -> {
            renderMonth();
            popup.show(triggerButton, 0, triggerButton.getHeight() + 4);
        });

        updateTrigger();
        renderMonth();
        setPreferredSize(new Dimension(210, 38));
    }

    public LocalDate getSelectedDate() {
        return selectedDate;
    }

    public void setSelectedDate(LocalDate date) {
        LocalDate previous = selectedDate;
        selectedDate = date;
        if (date != null) {
            displayedMonth = YearMonth.from(date);
        }
        updateTrigger();
        renderMonth();
        firePropertyChange(SELECTED_DATE_PROPERTY, previous, selectedDate);
    }

    public void setAvailableDates(Collection<LocalDate> dates) {
        availableDates.clear();
        if (dates != null) {
            dates.stream().filter(Objects::nonNull).forEach(availableDates::add);
        }
        renderMonth();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (triggerButton != null) {
            triggerButton.setEnabled(enabled);
        }
    }

    private JPanel createCalendarContent() {
        SurfacePanel content = new SurfacePanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JButton previous = navigationButton("‹", "calendarPreviousMonth", "Mes anterior");
        JButton next = navigationButton("›", "calendarNextMonth", "Mes siguiente");
        previous.addActionListener(event -> {
            displayedMonth = displayedMonth.minusMonths(1);
            renderMonth();
        });
        next.addActionListener(event -> {
            displayedMonth = displayedMonth.plusMonths(1);
            renderMonth();
        });
        header.add(previous, BorderLayout.WEST);
        header.add(monthLabel, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);

        JPanel calendar = new JPanel();
        calendar.setOpaque(false);
        calendar.setLayout(new BoxLayout(calendar, BoxLayout.Y_AXIS));
        calendar.add(createWeekdayHeader());
        calendar.add(Box.createVerticalStrut(5));
        calendar.add(dayGrid);

        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);
        footer.add(UiKit.muted("•  Días con registros"), BorderLayout.CENTER);
        JButton today = UiKit.secondaryButton("Hoy");
        today.setName("calendarToday");
        today.addActionListener(event -> selectFromCalendar(LocalDate.now()));
        footer.add(today, BorderLayout.EAST);

        content.add(header, BorderLayout.NORTH);
        content.add(calendar, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        return content;
    }

    private JPanel createWeekdayHeader() {
        JPanel header = new JPanel(new GridLayout(1, 7, 4, 0));
        header.setOpaque(false);
        for (String weekday : WEEKDAYS) {
            JLabel label = UiKit.muted(weekday);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
            header.add(label);
        }
        return header;
    }

    private void renderMonth() {
        if (dayGrid == null || monthLabel == null) {
            return;
        }
        monthLabel.setText(capitalize(displayedMonth.atDay(1).format(MONTH_FORMATTER)));
        dayGrid.removeAll();

        LocalDate firstDay = displayedMonth.atDay(1);
        int leadingBlanks = firstDay.getDayOfWeek().getValue() - 1;
        int daysInMonth = displayedMonth.lengthOfMonth();
        int occupiedCells = leadingBlanks + daysInMonth;
        int cellCount = ((occupiedCells + 6) / 7) * 7;
        for (int cell = 0; cell < cellCount; cell++) {
            int day = cell - leadingBlanks + 1;
            if (day < 1 || day > daysInMonth) {
                JLabel blank = new JLabel();
                blank.setPreferredSize(new Dimension(38, 38));
                dayGrid.add(blank);
            } else {
                dayGrid.add(createDayButton(displayedMonth.atDay(day)));
            }
        }
        dayGrid.revalidate();
        dayGrid.repaint();
        popup.pack();
    }

    private AbstractButton createDayButton(LocalDate date) {
        boolean hasEntries = availableDates.contains(date);
        JToggleButton button = new JToggleButton(Integer.toString(date.getDayOfMonth()));
        button.setName("calendarDay-" + date);
        button.setSelected(date.equals(selectedDate));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setMargin(new Insets(3, 3, 3, 3));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setIcon(new DayMarkerIcon(hasEntries));
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.TOP);
        button.setIconTextGap(0);
        button.setPreferredSize(new Dimension(38, 38));
        button.getAccessibleContext().setAccessibleName(
                date.format(ACCESSIBLE_FORMATTER)
        );
        button.getAccessibleContext().setAccessibleDescription(
                hasEntries ? "Tiene registros" : "Sin registros"
        );
        button.setToolTipText(hasEntries
                ? date.format(ACCESSIBLE_FORMATTER) + " · Tiene registros"
                : date.format(ACCESSIBLE_FORMATTER));
        if (date.equals(LocalDate.now())) {
            button.setFont(button.getFont().deriveFont(Font.BOLD));
        }
        button.addActionListener(event -> selectFromCalendar(date));
        return button;
    }

    private void selectFromCalendar(LocalDate date) {
        setSelectedDate(date);
        popup.setVisible(false);
    }

    private void updateTrigger() {
        if (selectedDate == null) {
            triggerButton.setText("Elegir día");
            triggerButton.setToolTipText("Abre el calendario");
            return;
        }
        String label = selectedDate.format(BUTTON_FORMATTER).replace(".", "");
        triggerButton.setText(label);
        triggerButton.setToolTipText("Fecha seleccionada: " + label);
    }

    private static JButton navigationButton(String text, String name, String description) {
        JButton button = new JButton(text);
        button.setName(name);
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setMargin(new Insets(3, 8, 3, 8));
        button.setPreferredSize(new Dimension(34, 30));
        button.getAccessibleContext().setAccessibleName(description);
        return button;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static final class DayMarkerIcon implements Icon {

        private final boolean visible;

        private DayMarkerIcon(boolean visible) {
            this.visible = visible;
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 6;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (!visible) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );
                g2.setColor(UiKit.accentColor());
                g2.fillOval(x + 2, y + 2, 4, 4);
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class CalendarGlyph implements Icon {

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );
                Color color = component.getForeground() == null
                        ? Color.DARK_GRAY
                        : component.getForeground();
                g2.setColor(color);
                g2.setStroke(new BasicStroke(
                        1.5f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                ));
                g2.draw(new Rectangle2D.Double(2.5, 3.5, 13, 12));
                g2.draw(new Line2D.Double(2.5, 7, 15.5, 7));
                g2.draw(new Line2D.Double(6, 1.8, 6, 5));
                g2.draw(new Line2D.Double(12, 1.8, 12, 5));
                for (int row = 0; row < 2; row++) {
                    for (int column = 0; column < 3; column++) {
                        g2.fillOval(5 + column * 3, 9 + row * 3, 1, 1);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

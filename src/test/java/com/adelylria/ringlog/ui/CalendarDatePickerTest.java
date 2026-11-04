package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.theme.ThemeManager;
import com.adelylria.ringlog.ui.theme.UiKit;

public final class CalendarDatePickerTest {

    private CalendarDatePickerTest() {
    }

    public static void calendarNavigatesMarksEntriesAndSelectsAnyDay()
            throws Exception {
        ThemeManager.setupDarkTheme();
        SwingUtilities.invokeAndWait(() -> {
            CalendarDatePicker picker = new CalendarDatePicker(
                    LocalDate.of(2026, 2, 9)
            );
            picker.setAvailableDates(Set.of(
                    LocalDate.of(2026, 2, 9),
                    LocalDate.of(2026, 2, 8)
            ));

            AbstractButton trigger = button(picker, "calendarPickerButton");
            require("9 feb 2026".equals(trigger.getText()),
                    "The collapsed picker should show a friendly Spanish date");
            require(trigger.getAccessibleContext().getAccessibleName() != null,
                    "The calendar trigger should be accessible");

            JPopupMenu popup = picker.getComponentPopupMenu();
            require(popup != null, "The date picker should expose a calendar popup");
            AbstractButton recordedDay = button(popup, "calendarDay-2026-02-09");
            require(recordedDay.isSelected(),
                    "The selected day should be visually distinguishable");
            require("Tiene registros".equals(
                            recordedDay.getAccessibleContext().getAccessibleDescription()),
                    "Days with diary entries should be marked");

            button(popup, "calendarNextMonth").doClick();
            AbstractButton arbitraryDay = button(popup, "calendarDay-2026-03-15");
            AtomicReference<LocalDate> notified = new AtomicReference<>();
            picker.addPropertyChangeListener(
                    CalendarDatePicker.SELECTED_DATE_PROPERTY,
                    event -> notified.set((LocalDate) event.getNewValue())
            );
            arbitraryDay.doClick();

            require(LocalDate.of(2026, 3, 15).equals(picker.getSelectedDate()),
                    "The user should be able to choose a day without existing entries");
            require(LocalDate.of(2026, 3, 15).equals(notified.get()),
                    "Selecting a day should notify the archive filter");
        });
    }

    public static void calendarPopupFollowsThemeChanges() throws Exception {
        ThemeManager.setupDarkTheme();
        SwingUtilities.invokeAndWait(() -> {
            CalendarDatePicker picker = new CalendarDatePicker(
                    LocalDate.of(2026, 2, 9)
            );
            JPopupMenu popup = picker.getComponentPopupMenu();
            JLabel month = label(popup, "Febrero 2026");
            java.awt.Color darkForeground = month.getForeground();

            ThemeManager.setupLightTheme();
            SwingUtilities.updateComponentTreeUI(picker);
            UiKit.refreshSemanticColors(picker);

            require(!darkForeground.equals(UiKit.textColor()),
                    "The test needs two visibly different theme palettes");
            require(UiKit.textColor().equals(month.getForeground()),
                    "The detached calendar popup should follow the active theme"
                            + " (expected " + UiKit.textColor()
                            + ", got " + month.getForeground()
                            + ", token " + month.getClientProperty(
                                    "RingLog.foregroundToken"
                            ) + ")");
        });
    }

    public static void calendarReusesCellsWithoutStaleDateListeners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CalendarDatePicker picker = new CalendarDatePicker(LocalDate.of(2024, 2, 29));
            JPopupMenu popup = picker.getComponentPopupMenu();
            List<JToggleButton> originalCells = dayButtons(popup);
            require(originalCells.size() == 42,
                    "The calendar should retain a fixed six-week grid rather than rebuild its controls");
            AbstractButton leapDay = button(popup, "calendarDay-2024-02-29");
            picker.setAvailableDates(Set.of(LocalDate.of(2024, 2, 29)));
            require("Tiene registros".equals(leapDay.getAccessibleContext().getAccessibleDescription()),
                    "Refreshing entry markers should update an existing calendar cell");

            button(popup, "calendarNextMonth").doClick();
            require(find(popup, "calendarDay-2024-02-29") == null,
                    "A reused cell must no longer expose its previous month's date");
            AbstractButton marchDay = button(popup, "calendarDay-2024-03-28");
            require(marchDay == leapDay,
                    "Month navigation should reuse the cell at the same grid position");
            require("Sin registros".equals(marchDay.getAccessibleContext().getAccessibleDescription()),
                    "A reused cell must not keep the previous date's entry marker");
            marchDay.doClick();
            require(LocalDate.of(2024, 3, 28).equals(picker.getSelectedDate()),
                    "The reused listener must select the newly displayed date, not the old date");
            marchDay.doClick();
            require(marchDay.isSelected(),
                    "Clicking an already selected day must keep the current filter visibly selected");
            picker.setAvailableDates(Set.of(LocalDate.of(2024, 3, 28)));
            picker.setAvailableDates(Set.of(LocalDate.of(2024, 3, 28)));
            picker.setSelectedDate(LocalDate.of(2024, 3, 28));
            picker.setSelectedDate(null);
            require(!marchDay.isSelected(), "Clearing the filter must clear its reused selected cell");
            require(originalCells.equals(dayButtons(popup)),
                    "Selection and marker refreshes must retain the existing grid components");
            long visibleDays = originalCells.stream().filter(Component::isVisible).count();
            require(visibleDays == 31, "Unused cells must remain blank when navigating to a 31-day month");
        });
    }

    private static List<JToggleButton> dayButtons(Container root) {
        List<JToggleButton> buttons = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JToggleButton button) buttons.add(button);
            if (child instanceof Container nested) buttons.addAll(dayButtons(nested));
        }
        return buttons;
    }

    private static AbstractButton button(Container root, String name) {
        Component found = find(root, name);
        require(found instanceof AbstractButton, "Missing calendar control: " + name);
        return (AbstractButton) found;
    }

    private static JLabel label(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (component instanceof Container nested) {
                try {
                    return label(nested, text);
                } catch (AssertionError ignored) {
                    // Continue searching sibling branches.
                }
            }
        }
        throw new AssertionError("Missing calendar label: " + text);
    }

    private static Component find(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent swingComponent
                    && name.equals(swingComponent.getName())) {
                return component;
            }
            if (component instanceof Container nested) {
                Component found = find(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        calendarNavigatesMarksEntriesAndSelectsAnyDay();
        calendarPopupFollowsThemeChanges();
        calendarReusesCellsWithoutStaleDateListeners();
        System.out.println("CalendarDatePickerTest: PASS");
    }
}

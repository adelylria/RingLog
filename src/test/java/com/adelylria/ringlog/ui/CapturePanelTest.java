package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.DateSelector;
import com.adelylria.ringlog.ui.components.TimeSelector;
import com.adelylria.ringlog.ui.panels.CapturePanel;

public final class CapturePanelTest {

    private CapturePanelTest() {
    }

    public static void blankOptionalNumbersBecomeNull() {
        require(
                CapturePanel.parseOptionalDouble("") == null,
                "Blank optional number should become null"
        );
    }

    public static void decimalCommaIsAccepted() {
        Double parsed = CapturePanel.parseOptionalDouble("24,5");
        require(parsed != null && parsed == 24.5, "Decimal comma should be accepted");
    }

    public static void formNeverScrollsHorizontally() {
        CapturePanel panel = new CapturePanel();
        JScrollPane scroll = find(panel, JScrollPane.class);

        require(scroll != null, "The capture fields should have a vertical scroll area");
        require(
                scroll.getHorizontalScrollBarPolicy()
                        == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                "The capture form should never expose a horizontal scrollbar"
        );
    }

    public static void saveActionStaysOutsideScrollableFields() {
        CapturePanel panel = new CapturePanel();
        JScrollPane scroll = find(panel, JScrollPane.class);
        JButton save = findButton(panel, "Guardar evento");

        require(scroll != null && save != null, "Capture form actions are missing");
        require(
                !isDescendant(scroll.getViewport().getView(), save),
                "The save action should remain fixed below the scrolling fields"
        );
    }

    public static void formDoesNotExposeRawCaptureCode() {
        CapturePanel panel = new CapturePanel();
        require(
                !containsTextField(panel, "CAPTURE"),
                "The form should show a human capture type instead of its database code"
        );
    }

    public static void dateSelectorUsesSpanishMonthsAndCorrectsInvalidDays() {
        DateSelector selector = new DateSelector(LocalDate.of(2024, 1, 31));
        JComboBox<?> month = findNamed(selector, "dateMonthSelector", JComboBox.class);

        require(month != null, "The date selector should expose its month control");
        require("enero".equals(month.getSelectedItem()), "Months should be shown in Spanish");
        month.setSelectedItem("febrero");

        require(
                "2024-02-29".equals(selector.getIsoDate()),
                "Changing to February should correct an invalid selected day"
        );
    }

    public static void timeSelectorProvidesIsoHourAndMinute() {
        TimeSelector selector = new TimeSelector(LocalTime.of(9, 5));
        JComboBox<?> hour = findNamed(selector, "timeHourSelector", JComboBox.class);
        JComboBox<?> minute = findNamed(selector, "timeMinuteSelector", JComboBox.class);

        require(hour != null && minute != null, "The time selector should expose hour and minute controls");
        hour.setSelectedItem("17");
        minute.setSelectedItem("08");

        require("17:08".equals(selector.getIsoTime()), "Time should be delivered as HH:mm");
    }

    public static void timeSelectorCanRepresentAnUnknownTime() {
        TimeSelector selector = new TimeSelector(LocalTime.of(9, 5));
        selector.setTime(null);

        require(selector.getIsoTime().isEmpty(),
                "An event without a known time must remain without a time when edited");
    }

    public static void formEmbedsAccessibleDateAndTimeSelectors() {
        CapturePanel panel = new CapturePanel();
        DateSelector date = find(panel, DateSelector.class);
        TimeSelector time = find(panel, TimeSelector.class);

        require(date != null && time != null, "The capture form should use visual date and time selectors");
        require(
                "Fecha del evento".equals(date.getAccessibleContext().getAccessibleName()),
                "The date selector needs an accessible name"
        );
        require(
                "Hora del evento".equals(time.getAccessibleContext().getAccessibleName()),
                "The time selector needs an accessible name"
        );
    }

    public static void editModeUsesTheFormAndKeepsHistoricalCatalogValues()
            throws Exception {
        CatalogRepository emptyCatalog = new CatalogRepository("unused.db") {
            @Override
            public List<SpeciesOption> findSpeciesOptions() {
                return List.of();
            }

            @Override
            public List<PlaceSummary> findPlaceSummaries() {
                return List.of();
            }
        };
        AtomicLong editedId = new AtomicLong();
        AtomicReference<BirdEventInput> editedInput = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        BirdEventRepository editingRepository = new BirdEventRepository("unused.db") {
            @Override
            public long insert(BirdEventInput input) {
                throw new AssertionError("Editing must not insert another event");
            }

            @Override
            public void update(long eventId, BirdEventInput input) {
                editedId.set(eventId);
                editedInput.set(input);
                saved.countDown();
            }
        };
        CapturePanel[] holder = new CapturePanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CapturePanel(
                editingRepository,
                emptyCatalog,
                ignored -> { },
                () -> { }
        ));
        CapturePanel panel = holder[0];
        BirdEventDetail detail = new BirdEventDetail(
                31L, 11L, "V6494", "Petirrojo europeo", EventType.RECOVERY,
                "2026-03-01", null, "Bosque antiguo", "Pollença", null,
                "RECAPTURE", "F", "6", null, null, "AR", "PENDING",
                null, null, null, null, null, null, null, null, 18.2,
                null, null, null, null, 39.85, 2.98, "Nota original", false,
                "REVIEW", "Pendiente de comprobar", List.of(), 91L, 92L
        );
        SwingUtilities.invokeAndWait(() -> panel.edit(detail));

        JComboBox<?> species = findNamed(panel, "speciesField", JComboBox.class);
        JComboBox<?> place = findNamed(panel, "placeField", JComboBox.class);
        waitFor(() -> species.getSelectedItem() instanceof SpeciesOption speciesOption
                        && speciesOption.id() == 91L
                        && place.getSelectedItem() instanceof PlaceSummary placeOption
                        && placeOption.id() == 92L,
                "The historical species and place should remain selectable while editing");

        JTextField ring = findNamed(panel, "ringNumberField", JTextField.class);
        TimeSelector time = find(panel, TimeSelector.class);
        require("V6494".equals(ring.getText()),
                "Edit mode should populate the ring number");
        require(time.getIsoTime().isEmpty(),
                "Edit mode should preserve an unknown event time");
        require(findButton(panel, "Guardar cambios") != null,
                "Edit mode should offer an explicit save-changes action");

        JButton save = findButton(panel, "Guardar cambios");
        SwingUtilities.invokeAndWait(save::doClick);
        require(saved.await(2, TimeUnit.SECONDS),
                "Saving an edit should call the repository update operation");
        require(editedId.get() == 31L,
                "Editing should update the original event rather than insert another one");
        require(editedInput.get() != null
                        && "PENDING".equals(editedInput.get().status())
                        && editedInput.get().eventTime().isEmpty(),
                "Saving should preserve coded and unknown values from the existing entry");
    }

    private static boolean containsTextField(Component component, String text) {
        if (component instanceof JTextField field && text.equals(field.getText())) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsTextField(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T findNamed(
            Component component,
            String name,
            Class<T> type
    ) {
        if (type.isInstance(component) && name.equals(component.getName())) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findNamed(child, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isDescendant(Component root, Component expected) {
        if (root == expected) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (isDescendant(child, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void waitFor(Check check, String failureMessage) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean[] complete = new boolean[1];
            SwingUtilities.invokeAndWait(() -> complete[0] = check.evaluate());
            if (complete[0]) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(failureMessage);
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        blankOptionalNumbersBecomeNull();
        decimalCommaIsAccepted();
        formNeverScrollsHorizontally();
        saveActionStaysOutsideScrollableFields();
        formDoesNotExposeRawCaptureCode();
        dateSelectorUsesSpanishMonthsAndCorrectsInvalidDays();
        timeSelectorProvidesIsoHourAndMinute();
        timeSelectorCanRepresentAnUnknownTime();
        formEmbedsAccessibleDateAndTimeSelectors();
        editModeUsesTheFormAndKeepsHistoricalCatalogValues();
        System.out.println("CapturePanelTest: PASS");
    }
}

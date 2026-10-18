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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JList;
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
import com.adelylria.ringlog.ui.theme.UiKit;

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

    public static void birdStatusSelectorUsesTheOfficialCatalogAndStoresItsCode()
            throws Exception {
        CatalogRepository catalog = new CatalogRepository("unused.db") {
            @Override
            public List<SpeciesOption> findSpeciesOptions() {
                return List.of(new SpeciesOption(7L, "Turdus philomelos"));
            }

            @Override
            public List<PlaceSummary> findPlaceSummaries() {
                return List.of();
            }
        };
        AtomicReference<BirdEventInput> savedInput = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override
            public long insert(BirdEventInput input) {
                savedInput.set(input);
                saved.countDown();
                return 1L;
            }
        };
        CapturePanel[] holder = new CapturePanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CapturePanel(
                repository,
                catalog,
                ignored -> { },
                () -> { }
        ));
        CapturePanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            panel.edit(historicalDetail());
            panel.prepareNew();
            requireRequestedFieldLayout(panel);
            require(findNamed(panel, "ageEuringCodeField", JTextField.class).getText().isEmpty(),
                    "Starting a new entry must clear the previous age");
        });
        JComboBox<?> species = findNamed(panel, "speciesField", JComboBox.class);
        JComboBox<?> status = findNamed(panel, "birdStatusField", JComboBox.class);
        waitFor(() -> species.getItemCount() == 1,
                "The test species should be available in the form");

        require(status != null && status.getItemCount() == 49,
                "The status selector should contain 48 official states plus Sin indicar");
        Object injuredByRing = null;
        for (int index = 0; index < status.getItemCount(); index++) {
            Object item = status.getItemAt(index);
            if (item != null && item.toString().startsWith("F5 · ")) {
                injuredByRing = item;
                break;
            }
        }
        require(injuredByRing != null,
                "The official F5 status should be selectable with its description");
        Object selectedStatus = injuredByRing;
        SwingUtilities.invokeAndWait(() -> {
            findNamed(panel, "ringNumberField", JTextField.class).setText("V12345");
            findNamed(panel, "ageEuringCodeField", JTextField.class).setText("4");
            species.setSelectedIndex(0);
            status.setSelectedItem(selectedStatus);
            JComboBox<?> condition = fieldLabeled(panel, "Condición general", JComboBox.class);
            require(hasOption(condition, "Muerto"), "Muerto must be offered for new entries");
            condition.setSelectedItem("Muerto");
            require(selectedStatus.equals(status.getSelectedItem()),
                    "Selecting Muerto must not alter the independently selected official status");
            require(!find(panel, JCheckBox.class).isSelected(),
                    "Selecting Muerto must not mark Ave fallecida automatically");
            findButton(panel, "Guardar evento").doClick();
        });

        require(saved.await(2, TimeUnit.SECONDS),
                "Saving the form should deliver the selected bird status");
        require(savedInput.get() != null && "F5".equals(savedInput.get().status()),
                "Only the stable official code should be persisted");
        require("DEAD".equals(savedInput.get().birdCondition()) && !savedInput.get().dead(),
                "Condition must be saved independently of official status and vital state");
        require("4".equals(savedInput.get().ageEuringCode()),
                "Edad must still save the original EURING code field");
        require(savedInput.get().ringerInitials() == null,
                "A new entry must not inherit hidden initials from the previously edited event");
        require(savedInput.get().captureType() == null,
                "A new entry must not inherit the hidden historical classification");
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
        BirdEventDetail detail = historicalDetail();
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
        SwingUtilities.invokeAndWait(() -> {
            requireRequestedFieldLayout(panel);
            JTextField age = findNamed(panel, "ageEuringCodeField", JTextField.class);
            require("6".equals(age.getText()), "Edad must load the historical EURING value");
            age.setText("5");
            JComboBox<?> condition = fieldLabeled(panel, "Condición general", JComboBox.class);
            require("Muerto".equals(condition.getSelectedItem()),
                    "A saved DEAD condition must reopen as Muerto");
            require("Muerto".equals(UiKit.friendlyCode("DEAD")),
                    "The detail view should also use the human-readable condition");
            require(!find(panel, JCheckBox.class).isSelected(),
                    "Loading Muerto must not change the original vital state");
        });

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
        require("AR".equals(editedInput.get().ringerInitials()),
                "Removing the initials control must not erase historical initials on save");
        require("RECAPTURE".equals(editedInput.get().captureType()),
                "Removing the classification control must not erase historical values on save");
        require("5".equals(editedInput.get().ageEuringCode()),
                "The relocated Edad control must remain editable");
        require("DEAD".equals(editedInput.get().birdCondition()) && !editedInput.get().dead(),
                "Editing must preserve the condition independently of the vital state");
    }

    @SuppressWarnings("unchecked")
    public static void placeSelectorShowsCoordinatesInOptionsAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CapturePanel panel = new CapturePanel();
            JComboBox<PlaceSummary> places = (JComboBox<PlaceSummary>) findNamed(
                    panel, "placeField", JComboBox.class);
            JList<PlaceSummary> list = new JList<>();
            PlaceSummary first = new PlaceSummary(1, "Lasarell", "Pollença",
                    39.8475, 2.9780, null, false, false, 0);
            PlaceSummary second = new PlaceSummary(2, "Lasarell", "Pollença",
                    39.8971, 3.0298, null, false, false, 0);
            for (int index : new int[] {-1, 0}) {
                JLabel rendered = (JLabel) places.getRenderer().getListCellRendererComponent(
                        list, first, index, true, false);
                require("Lasarell · Pollença · 39.847500, 2.978000".equals(rendered.getText()),
                        "Both the selected place and its dropdown option need coordinates");
                rendered = (JLabel) places.getRenderer().getListCellRendererComponent(
                        list, second, index, false, false);
                require("Lasarell · Pollença · 39.897100, 3.029800".equals(rendered.getText()),
                        "Same-named places must be distinguishable by their actual coordinates");
            }
            places.addItem(first);
            places.setSelectedItem(first);
            require(((PlaceSummary) places.getSelectedItem()).id() == 1L,
                    "Rendering coordinates must not change the selected place identity");
            require(places.getToolTipText().contains("39.847500, 2.978000"),
                    "A narrow selector should expose its complete label as a tooltip");
            for (PlaceSummary missing : List.of(
                    new PlaceSummary(3, "Bosque", null, null, null, null, false, false, 0),
                    new PlaceSummary(4, "Bosque", null, 39.85, null, null, false, false, 0),
                    new PlaceSummary(5, "Bosque", null, null, 2.98, null, false, false, 0))) {
                JLabel rendered = (JLabel) places.getRenderer().getListCellRendererComponent(
                        list, missing, 0, false, false);
                require("Bosque · Sin coordenadas".equals(rendered.getText()),
                        "Incomplete coordinates must not be invented or rendered as null");
            }
            PlaceSummary zero = new PlaceSummary(6, "Costa", "", 0.0, -2.5,
                    null, false, false, 0);
            JLabel rendered = (JLabel) places.getRenderer().getListCellRendererComponent(
                    list, zero, 0, false, false);
            require("Costa · 0.000000, -2.500000".equals(rendered.getText()),
                    "Zero and negative coordinates are valid, including places without locality");
            rendered = (JLabel) places.getRenderer().getListCellRendererComponent(
                    list, null, -1, false, false);
            require(!rendered.getText().contains("coordenadas"),
                    "An unselected place must not pretend to have location data");
        });
    }

    private static BirdEventDetail historicalDetail() {
        return new BirdEventDetail(
                31L, 11L, "V6494", "Petirrojo europeo", EventType.RECOVERY,
                "2026-03-01", null, "Bosque antiguo", "Pollença", null,
                "RECAPTURE", "F", "6", null, null, "AR", "PENDING",
                null, null, null, "DEAD", null, null, null, null, 18.2,
                null, null, null, null, 39.85, 2.98, "Nota original", false,
                "REVIEW", "Pendiente de comprobar", List.of(), 91L, 92L
        );
    }

    private static void requireRequestedFieldLayout(CapturePanel panel) {
        JTextField age = fieldLabeled(panel, "Edad", JTextField.class);
        JTextField wing = fieldLabeled(panel, "Ala · mm", JTextField.class);
        require(age != null && wing != null, "The form must show Edad next to the measurements");
        JPanel measurements = findNamed(panel, "measurementsGrid", JPanel.class);
        require(measurements != null && SwingUtilities.isDescendingFrom(age, measurements)
                        && SwingUtilities.isDescendingFrom(wing, measurements),
                "Edad and Ala must belong to the same measurement grid");
        require(findLabel(panel, "Código de edad EURING") == null,
                "The old age label must not remain in Datos de registro");
        require(findLabel(panel, "Iniciales") == null && !containsTextField(panel, "AR"),
                "Initials must no longer appear as an editable field");
    }

    private static boolean hasOption(JComboBox<?> combo, String label) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (label.equals(combo.getItemAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Component> T fieldLabeled(Component root, String label, Class<T> type) {
        JLabel found = findLabel(root, label);
        return found == null ? null : find(found.getParent(), type);
    }

    private static JLabel findLabel(Component component, String text) {
        if (component instanceof JLabel label && text.equals(label.getText())) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        birdStatusSelectorUsesTheOfficialCatalogAndStoresItsCode();
        editModeUsesTheFormAndKeepsHistoricalCatalogValues();
        placeSelectorShowsCoordinatesInOptionsAndSelection();
        System.out.println("CapturePanelTest: PASS");
    }
}

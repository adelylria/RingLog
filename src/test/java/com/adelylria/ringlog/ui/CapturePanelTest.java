package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.adelylria.ringlog.ui.components.EnvironmentSelector;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
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

    public static void ambientTemperatureLoadsSavesAndClearsIndependentlyOfThermalSensation()
            throws Exception {
        CatalogRepository catalog = new CatalogRepository("unused.db") {
            @Override public List<SpeciesOption> findSpeciesOptions() { return List.of(); }
            @Override public List<PlaceSummary> findPlaceSummaries() { return List.of(); }
            @Override public List<com.adelylria.ringlog.model.view.CaptureMethodOption> findCaptureMethods() {
                return List.of();
            }
        };
        AtomicReference<BirdEventInput> savedInput = new AtomicReference<>();
        AtomicInteger saveCallbacks = new AtomicInteger();
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override public void update(long eventId, BirdEventInput input) {
                require(eventId == 31L, "Temperature edits must update the original event");
                savedInput.set(input);
            }
            @Override public long insert(BirdEventInput input) {
                throw new AssertionError("Editing the weather must not create a second event");
            }
        };
        CapturePanel[] holder = new CapturePanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CapturePanel(
                repository, catalog, ignored -> saveCallbacks.incrementAndGet(), () -> { }
        ));
        CapturePanel panel = holder[0];
        for (Integer temperature : new Integer[] {-99, 0, null, 99}) {
            BirdEventDetail detail = detailWithWeather(temperature, "17,5");
            SwingUtilities.invokeAndWait(() -> panel.edit(detail));
            waitFor(() -> findNamed(panel, "speciesField", JComboBox.class).getSelectedItem()
                            instanceof SpeciesOption option && option.id() == detail.speciesId()
                            && !panel.hasUnsavedChanges(),
                    "Loading weather and its catalogs must not create unsaved changes");
            int callbacksBeforeSave = saveCallbacks.get();
            Integer expectedTemperature = Integer.valueOf(-99).equals(temperature)
                    ? Integer.valueOf(-12) : temperature;
            SwingUtilities.invokeAndWait(() -> {
                JTextField ambient = findNamed(panel, "temperatureCelsiusField", JTextField.class);
                EnvironmentSelector thermal = fieldLabeled(panel, "Sensación térmica", EnvironmentSelector.class);
                require(ambient != null && thermal != null,
                        "Ambient temperature and thermal sensation need separate inputs");
                require((temperature == null ? "" : temperature.toString()).equals(ambient.getText()),
                        "Editing must load the original optional ambient temperature");
                require("17,5".equals(thermal.value()),
                        "Loading ambient temperature must preserve the original thermal sensation text");
                if (!Objects.equals(expectedTemperature, temperature)) {
                    ambient.selectAll();
                    ambient.replaceSelection(expectedTemperature.toString());
                    require(panel.hasUnsavedChanges(), "Changing only ambient temperature is an edit");
                }
                findButton(panel, "Guardar cambios").doClick();
            });
            waitFor(() -> saveCallbacks.get() == callbacksBeforeSave + 1,
                    "The temperature edit should finish saving");
            require(savedInput.get() != null
                            && Objects.equals(expectedTemperature, savedInput.get().temperatureCelsius()),
                    "Saving must distinguish a negative temperature, zero and an unspecified value");
            require("17,5".equals(savedInput.get().thermalSensation()),
                    "Ambient temperature must never overwrite independent thermal sensation");
        }
        SwingUtilities.invokeAndWait(() -> {
            panel.edit(detailWithWeather(-5, "Sensación original"));
            JTextField ambient = findNamed(panel, "temperatureCelsiusField", JTextField.class);
            ambient.selectAll();
            ambient.replaceSelection("-");
            int previousCallbacks = saveCallbacks.get();
            BirdEventInput previousInput = savedInput.get();
            findButton(panel, "Guardar cambios").doClick();
            require(saveCallbacks.get() == previousCallbacks && savedInput.get() == previousInput,
                    "An unfinished negative temperature must not reach the repository");
            JLabel error = findNamed(panel, "temperatureCelsiusError", JLabel.class);
            require(error != null && !error.getText().isBlank() && "-".equals(ambient.getText()),
                    "A failed save must explain and preserve the unfinished temperature");
            panel.prepareNew();
            require(ambient.getText().isEmpty()
                            && fieldLabeled(panel, "Sensación térmica", EnvironmentSelector.class).value().isEmpty(),
                    "A new event must clear both weather inputs without inheriting the edited values");
            require(error.getText().isEmpty(), "A new event must clear the previous temperature error");
        });
    }

    public static void detailShowsAmbientTemperatureSeparatelyFromThermalSensation() throws Exception {
        AtomicReference<BirdEventDetail> current = new AtomicReference<>(detailWithWeather(-5, "18.2"));
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override public Optional<BirdEventDetail> findDetail(long eventId) {
                return Optional.of(current.get());
            }
            @Override public List<com.adelylria.ringlog.model.view.BirdEventTimelineItem> findEventsByBird(long birdId) {
                return List.of();
            }
        };
        CaptureDetailPanel[] holder = new CaptureDetailPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new CaptureDetailPanel(repository, () -> { }, ignored -> { });
            holder[0].setEventId(31L);
        });
        CaptureDetailPanel panel = holder[0];
        waitFor(() -> findLabel(panel, "-5 °C") != null && findLabel(panel, "18.2 °C") != null,
                "The detail must show ambient temperature and thermal sensation independently");
        current.set(detailWithWeather(0, "18.2"));
        SwingUtilities.invokeAndWait(() -> panel.setEventId(31L));
        waitFor(() -> findLabel(panel, "0 °C") != null && findLabel(panel, "-5 °C") == null,
                "Zero must display as a real temperature, not as missing data");
        current.set(detailWithWeather(null, "18.2"));
        SwingUtilities.invokeAndWait(() -> panel.setEventId(31L));
        waitFor(() -> findLabel(panel, "Temperatura ambiental") != null
                        && findLabel(panel, "0 °C") == null && findLabel(panel, "18.2 °C") != null,
                "An unspecified ambient temperature must not erase thermal sensation or its caption");

        for (String qualitative : new String[] {"Templado", "Mucho frío", "Templat"}) {
            current.set(detailWithWeather(22, qualitative));
            SwingUtilities.invokeAndWait(() -> panel.setEventId(31L));
            waitFor(() -> findLabel(panel, "22 °C") != null
                            && findLabel(panel, qualitative) != null
                            && findLabel(panel, qualitative + " °C") == null,
                    "Qualitative thermal sensation must remain literal while ambient temperature shows its unit");
        }
        current.set(detailWithWeather(22, "23.8"));
        SwingUtilities.invokeAndWait(() -> panel.setEventId(31L));
        waitFor(() -> findLabel(panel, "22 °C") != null && findLabel(panel, "23.8 °C") != null,
                "An older numeric thermal sensation must retain its Celsius presentation");
        current.set(detailWithWeather(22, "-3,5 °C"));
        SwingUtilities.invokeAndWait(() -> panel.setEventId(31L));
        waitFor(() -> findLabel(panel, "22 °C") != null
                        && findLabel(panel, "-3,5 °C") != null
                        && findLabel(panel, "-3,5 °C °C") == null,
                "A historical thermal sensation with an explicit unit must never receive a second unit");
    }

    public static void savingBeforeCatalogLoadKeepsHistoricalSpeciesAndPlace() throws Exception {
        CountDownLatch initialPlaces = new CountDownLatch(1);
        CountDownLatch slowPlaces = new CountDownLatch(1);
        CountDownLatch releasePlaces = new CountDownLatch(1);
        AtomicInteger placeLoads = new AtomicInteger();
        CatalogRepository catalog = new CatalogRepository("unused.db") {
            @Override public List<SpeciesOption> findSpeciesOptions() { return List.of(); }
            @Override public List<PlaceSummary> findPlaceSummaries() {
                if (placeLoads.incrementAndGet() == 1) {
                    initialPlaces.countDown();
                    return List.of();
                }
                slowPlaces.countDown();
                try {
                    require(releasePlaces.await(5, TimeUnit.SECONDS), "Release the deliberately delayed catalog");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return List.of();
            }
            @Override public List<com.adelylria.ringlog.model.view.CaptureMethodOption> findCaptureMethods() {
                return List.of();
            }
            @Override public List<com.adelylria.ringlog.model.view.EnvironmentOption> findEnvironmentOptions() {
                return List.of();
            }
        };
        AtomicReference<BirdEventInput> saved = new AtomicReference<>();
        AtomicLong savedId = new AtomicLong();
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override public void update(long eventId, BirdEventInput input) {
                require(eventId == 31L, "A fast save must update the same historical event");
                saved.set(input);
            }
        };
        CapturePanel[] holder = new CapturePanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new CapturePanel(repository, catalog, savedId::set, () -> { }));
        require(initialPlaces.await(3, TimeUnit.SECONDS), "The initial catalog request should complete");
        CapturePanel panel = holder[0];
        try {
            SwingUtilities.invokeAndWait(() -> panel.edit(historicalDetail()));
            require(slowPlaces.await(3, TimeUnit.SECONDS), "The edit catalog must be blocked before saving");
            SwingUtilities.invokeAndWait(() -> {
                SpeciesOption species = (SpeciesOption) findNamed(panel, "speciesField", JComboBox.class).getSelectedItem();
                PlaceSummary place = (PlaceSummary) findNamed(panel, "placeField", JComboBox.class).getSelectedItem();
                require(species != null && species.id() == 91L && place != null && place.id() == 92L,
                        "Historical species and place must be selected immediately, before catalog I/O completes");
                findNamed(panel, "observationsField", javax.swing.JTextArea.class).setText("Notas guardadas antes de cargar");
                findButton(panel, "Guardar cambios").doClick();
            });
            waitFor(() -> savedId.get() == 31L, "Saving must not wait for the blocked catalog query");
            require(releasePlaces.getCount() == 1 && saved.get() != null && saved.get().speciesId() == 91L
                            && Long.valueOf(92L).equals(saved.get().placeId()),
                    "A fast save must keep both original identifiers while the catalog is still unavailable");
        } finally { releasePlaces.countDown(); }
    }

    private static BirdEventDetail detailWithWeather(Integer temperature, String thermalSensation) {
        BirdEventDetail source = historicalDetail();
        return new BirdEventDetail(
                source.id(), source.birdId(), source.ringNumber(), source.species(), source.eventType(),
                source.eventDate(), source.eventTime(), source.place(), source.locality(), source.locationText(),
                source.captureType(), source.sexCode(), source.ageEuringCode(), source.fatScore(),
                source.muscleScore(), source.ringerInitials(), source.status(), source.reproductiveStatus(),
                source.moultIntensity(), source.moultExtension(), source.birdCondition(), source.returnStatus(),
                source.wing(), source.p3(), source.torso(), source.weight(), source.clouds(), source.rain(),
                thermalSensation, source.wind(), null, null, source.observations(), source.dead(),
                source.reviewStatus(), source.reviewNote(), source.photoPaths(), source.speciesId(),
                source.placeId(), source.captureMethodId(), source.captureMethod(), temperature
        );
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
        ambientTemperatureLoadsSavesAndClearsIndependentlyOfThermalSensation();
        detailShowsAmbientTemperatureSeparatelyFromThermalSensation();
        savingBeforeCatalogLoadKeepsHistoricalSpeciesAndPlace();
        System.out.println("CapturePanelTest: PASS");
    }
}

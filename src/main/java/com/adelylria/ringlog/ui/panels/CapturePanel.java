package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.BirdStatusCatalog;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdLookup;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.media.EventPhotoService;
import com.adelylria.ringlog.ui.components.EventPhotoEditor;
import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.components.MeasurementInput;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.DateSelector;
import com.adelylria.ringlog.ui.components.TimeSelector;
import com.adelylria.ringlog.ui.theme.RoundedBorder;
import com.adelylria.ringlog.ui.theme.UiKit;

public class CapturePanel extends JPanel {

    private final BirdEventRepository eventRepository;
    private final CatalogRepository catalogRepository;
    private final Consumer<Long> onSaved;
    private final Runnable onCancel;
    private final JTextField ringNumberField;
    private final JComboBox<SpeciesOption> speciesCombo;
    private final JComboBox<EventType> eventTypeCombo;
    private final JComboBox<PlaceSummary> placeCombo;
    private final JComboBox<String> sexCombo;
    private final JComboBox<BirdStatusChoice> statusCombo;
    private final JComboBox<String> conditionCombo;
    private final JComboBox<String> captureTypeCombo;
    private final DateSelector dateSelector;
    private final TimeSelector timeSelector;
    private final JTextField locationTextField;
    private final JTextField ageEuringCodeField;
    private final JTextField reproductiveField;
    private final JTextField moultIntensityField;
    private final JTextField moultExtensionField;
    private final JTextField returnField;
    private final JTextField cloudsField;
    private final JTextField rainField;
    private final JTextField thermalField;
    private final JTextField windField;
    private final JCheckBox deadCheck;
    private final JTextArea observationsArea;
    private final Map<String, JTextField> numberFields;
    private final JLabel birdLookupLabel;
    private final JLabel statusLabel;
    private final JButton saveButton;
    private final Timer ringLookupTimer;
    private final JPanel headerHolder;
    private Long editingEventId;
    private String preservedRingerInitials;
    private Long pendingSpeciesId;
    private Long pendingPlaceId;
    private SpeciesOption pendingSpeciesFallback;
    private PlaceSummary pendingPlaceFallback;
    private int catalogLoadGeneration;
    private final EventPhotoService photoService;
    private final EventPhotoEditor photoEditor;
    private JButton cancelButton;
    private boolean saving;
    private boolean mutationsEnabled = true;
    private long formGeneration;
    private final Map<String, MeasurementInput> measurementInputs = new LinkedHashMap<>();
    private final FormChanges changes = new FormChanges(this, () -> photoDraft());

    private Object photoDraft() {
        return photoEditor == null ? List.of() : List.of(photoEditor.pendingPhotos(), photoEditor.removedPhotos());
    }

    public boolean hasUnsavedChanges() { return changes.hasChanges(); }
    public void setDiscardConfirmation(java.util.function.Predicate<javax.swing.JOptionPane> confirmation) {
        changes.setConfirmation(confirmation);
    }
    public boolean confirmLeave() {
        if (saving) {
            statusLabel.setText("Espera a que termine el guardado antes de salir.");
            return false;
        }
        if (!changes.confirmDiscard()) return false;
        ringLookupTimer.stop();
        catalogLoadGeneration++;
        if (photoEditor != null) photoEditor.discardPending();
        changes.markClean();
        return true;
    }

    public CapturePanel(
            BirdEventRepository eventRepository,
            CatalogRepository catalogRepository,
            Consumer<Long> onSaved,
            Runnable onCancel
    ) {
        this(eventRepository, catalogRepository, onSaved, onCancel, null);
    }

    public CapturePanel(BirdEventRepository eventRepository, CatalogRepository catalogRepository,
            Consumer<Long> onSaved, Runnable onCancel, EventPhotoService photoService) {
        this(eventRepository, catalogRepository, onSaved, onCancel, photoService,
                photoService == null ? null : new EventPhotoEditor());
    }

    public CapturePanel(BirdEventRepository eventRepository, CatalogRepository catalogRepository,
            Consumer<Long> onSaved, Runnable onCancel, EventPhotoService photoService, EventPhotoEditor photoEditor) {
        this.eventRepository = eventRepository;
        this.catalogRepository = catalogRepository;
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        this.photoService = photoService;
        this.photoEditor = photoService == null ? null : java.util.Objects.requireNonNull(photoEditor);
        this.ringNumberField = new JTextField();
        this.speciesCombo = new JComboBox<>();
        this.eventTypeCombo = new JComboBox<>(EventType.values());
        this.placeCombo = placeSelector();
        this.sexCombo = combo("Sin indicar", "Macho", "Hembra");
        this.statusCombo = birdStatusCombo();
        this.statusCombo.setName("birdStatusField");
        this.conditionCombo = combo("Buen estado", "Regular", "Delicado", "Muerto");
        this.captureTypeCombo = combo("Sin indicar", "Captura", "Recaptura");
        this.dateSelector = new DateSelector(LocalDate.now());
        this.timeSelector = new TimeSelector(LocalTime.now());
        this.locationTextField = new JTextField();
        this.ageEuringCodeField = new JTextField();
        this.ageEuringCodeField.setName("ageEuringCodeField");
        this.reproductiveField = new JTextField();
        this.moultIntensityField = new JTextField();
        this.moultExtensionField = new JTextField();
        this.returnField = new JTextField();
        this.cloudsField = new JTextField();
        this.rainField = new JTextField();
        this.thermalField = new JTextField();
        this.windField = new JTextField();
        this.deadCheck = new JCheckBox("Ave fallecida");
        this.deadCheck.setOpaque(false);
        this.observationsArea = new JTextArea(5, 40);
        this.observationsArea.setName("observationsField");
        this.numberFields = new LinkedHashMap<>();
        this.birdLookupLabel = UiKit.muted(
                "Escribe una anilla para comprobar su historial."
        );
        this.statusLabel = UiKit.muted(
                "Completa los datos del momento que quieres guardar."
        );
        this.saveButton = UiKit.primaryButton("Guardar evento");
        this.ringLookupTimer = new Timer(450, event -> lookupRing());
        this.ringLookupTimer.setRepeats(false);
        this.headerHolder = new JPanel(new BorderLayout());
        this.headerHolder.setOpaque(false);
        this.ringNumberField.setName("ringNumberField");
        this.speciesCombo.setName("speciesField");
        this.placeCombo.setName("placeField");
        this.saveButton.setName("saveEventButton");

        initialize();
        prepareNew();
    }

    public CapturePanel() {
        this(
                new BirdEventRepository(),
                new CatalogRepository(),
                ignored -> { },
                () -> { }
        );
    }

    private void initialize() {
        setLayout(new BorderLayout());

        JPanel page = UiKit.pagePanel();
        page.add(headerHolder, BorderLayout.NORTH);

        JPanel form = UiKit.verticalScrollPanel();
        form.add(createEssentialsSection());
        form.add(Box.createVerticalStrut(14));
        form.add(createNotesSection());
        form.add(Box.createVerticalStrut(14));
        form.add(createBirdSection());
        form.add(Box.createVerticalStrut(14));
        form.add(createWeatherSection());
        form.add(Box.createVerticalStrut(14));
        form.add(createRegistrationSection());

        JPanel actions = new JPanel(new BorderLayout(16, 0));
        actions.setOpaque(false);
        actions.setBorder(new javax.swing.border.EmptyBorder(2, 0, 0, 0));
        actions.add(statusLabel, BorderLayout.CENTER);
        saveButton.addActionListener(event -> save());
        actions.add(saveButton, BorderLayout.EAST);

        JScrollPane scroll = UiKit.scrollPane(form);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        page.add(scroll, BorderLayout.CENTER);
        page.add(actions, BorderLayout.SOUTH);
        add(page, BorderLayout.CENTER);

        ringNumberField.putClientProperty(
                "JTextField.placeholderText",
                "Ej. V25943"
        );
        locationTextField.putClientProperty(
                "JTextField.placeholderText",
                "Solo si el lugar no puede catalogarse"
        );
        ringNumberField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleRingLookup();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleRingLookup();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleRingLookup();
            }
        });
        ringNumberField.addActionListener(event -> lookupRing());
    }

    public void prepareNew() {
        formGeneration++;
        saving = false;
        if (photoEditor != null) photoEditor.reset(List.of());
        editingEventId = null;
        preservedRingerInitials = null;
        pendingSpeciesId = null;
        pendingPlaceId = null;
        pendingSpeciesFallback = null;
        pendingPlaceFallback = null;
        ringLookupTimer.stop();
        ringNumberField.setText("");
        eventTypeCombo.setSelectedItem(EventType.RINGING);
        dateSelector.setDate(LocalDate.now());
        timeSelector.setTime(LocalTime.now());
        locationTextField.setText("");
        resetCombo(sexCombo, "Sin indicar", "Macho", "Hembra");
        resetBirdStatusCombo("B0");
        resetCombo(conditionCombo, "Buen estado", "Regular", "Delicado", "Muerto");
        resetCombo(captureTypeCombo, "Sin indicar", "Captura", "Recaptura");
        ageEuringCodeField.setText("");
        reproductiveField.setText("");
        moultIntensityField.setText("");
        moultExtensionField.setText("");
        returnField.setText("");
        cloudsField.setText("");
        rainField.setText("");
        thermalField.setText("");
        windField.setText("");
        observationsArea.setText("");
        numberFields.values().forEach(field -> field.setText(""));
        deadCheck.setSelected(false);
        speciesCombo.setEnabled(true);
        birdLookupLabel.setText("Escribe una anilla para comprobar su historial.");
        statusLabel.setText("Completa los datos del momento que quieres guardar.");
        saveButton.setText("Guardar evento");
        renderHeader();
        updateActionAvailability();
        measurementInputs.values().forEach(MeasurementInput::clearError);
        changes.markClean();
        refreshCatalogs();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationsEnabled = enabled;
        updateActionAvailability();
    }

    private void updateActionAvailability() {
        saveButton.setEnabled(mutationsEnabled && !saving);
        if (photoEditor != null) photoEditor.setMutationActionsEnabled(mutationsEnabled && !saving);
        if (cancelButton != null) cancelButton.setEnabled(!saving);
    }

    public void edit(BirdEventDetail detail) {
        if (detail == null) {
            throw new IllegalArgumentException("Falta el registro que quieres editar.");
        }
        formGeneration++;
        saving = false;
        if (photoEditor != null) photoEditor.reset(detail.photoPaths());
        editingEventId = detail.id();
        // No longer editable, but still part of the historical event and its exports.
        preservedRingerInitials = detail.ringerInitials();
        pendingSpeciesId = detail.speciesId();
        pendingPlaceId = detail.placeId();
        pendingSpeciesFallback = detail.speciesId() < 1
                ? null
                : new SpeciesOption(detail.speciesId(), UiKit.display(detail.species()));
        pendingPlaceFallback = detail.placeId() == null
                ? null
                : new PlaceSummary(
                        detail.placeId(),
                        UiKit.display(detail.place()),
                        detail.locality(),
                        detail.latitude(),
                        detail.longitude(),
                        null,
                        false,
                        false,
                        0
                );
        ringLookupTimer.stop();
        ringNumberField.setText(text(detail.ringNumber()));
        eventTypeCombo.setSelectedItem(detail.eventType());
        dateSelector.setDate(parseDate(detail.eventDate()));
        timeSelector.setTime(parseTime(detail.eventTime()));
        locationTextField.setText(text(detail.locationText()));
        selectCode(sexCombo, detail.sexCode());
        selectBirdStatusCode(detail.status());
        selectCode(conditionCombo, detail.birdCondition());
        selectCode(captureTypeCombo, detail.captureType());
        ageEuringCodeField.setText(text(detail.ageEuringCode()));
        reproductiveField.setText(text(detail.reproductiveStatus()));
        moultIntensityField.setText(text(detail.moultIntensity()));
        moultExtensionField.setText(text(detail.moultExtension()));
        returnField.setText(text(detail.returnStatus()));
        cloudsField.setText(text(detail.clouds()));
        rainField.setText(text(detail.rain()));
        thermalField.setText(text(detail.thermalSensation()));
        windField.setText(text(detail.wind()));
        observationsArea.setText(text(detail.observations()));
        setNumber("wing", detail.wing());
        setNumber("p3", detail.p3());
        setNumber("torso", detail.torso());
        setNumber("weight", detail.weight());
        setNumber("fat", detail.fatScore());
        setNumber("muscle", detail.muscleScore());
        deadCheck.setSelected(detail.dead());
        speciesCombo.setEnabled(true);
        birdLookupLabel.setText(
                "La anilla y la especie identifican al ave y se actualizarán en todo su historial."
        );
        statusLabel.setText("Revisa los cambios antes de guardarlos.");
        saveButton.setText("Guardar cambios");
        renderHeader();
        updateActionAvailability();
        measurementInputs.values().forEach(MeasurementInput::clearError);
        changes.markClean();
        refreshCatalogs();
    }

    private void renderHeader() {
        boolean editing = editingEventId != null;
        cancelButton = UiKit.secondaryButton(editing ? "Cancelar edición" : "Cancelar");
        cancelButton.setName("cancelEventEdit");
        cancelButton.addActionListener(event -> cancel());
        headerHolder.removeAll();
        headerHolder.add(new PageHeader(
                editing ? "EDITAR ENTRADA" : "NUEVA ENTRADA",
                editing ? "Editar registro" : "Nuevo registro",
                editing
                        ? "Corrige la ficha sin perder su historial, trazabilidad ni fotografías."
                        : "Anillamiento, control o recuperación sin duplicar el ave ni el lugar.",
                cancelButton
        ), BorderLayout.CENTER);
        headerHolder.revalidate();
        headerHolder.repaint();
    }

    private void cancel() {
        if (!confirmLeave()) return;
        formGeneration++;
        if (photoEditor != null) photoEditor.discardPending();
        if (editingEventId != null) {
            onSaved.accept(editingEventId);
        } else {
            onCancel.run();
        }
    }

    private JPanel createEssentialsSection() {
        JPanel panel = UiKit.sectionPanel();
        panel.add(sectionHeading(
                "Lo esencial",
                "Identifica el ave y reutiliza un lugar ya guardado."
        ), BorderLayout.NORTH);
        JPanel grid = grid();
        addField(grid, 0, "Tipo de registro *", eventTypeCombo);
        addField(grid, 1, "Número de anilla *", ringNumberField);
        addField(grid, 2, "Fecha *", dateSelector);
        addField(grid, 3, "Hora", timeSelector);
        addField(grid, 4, "Especie *", speciesCombo);
        addField(grid, 5, "Lugar", placeCombo);
        addField(grid, 6, "Ubicación libre", locationTextField);
        panel.add(grid, BorderLayout.CENTER);
        JPanel lookup = new JPanel(new BorderLayout());
        lookup.setOpaque(false);
        lookup.setBorder(new javax.swing.border.EmptyBorder(8, 0, 0, 0));
        lookup.add(birdLookupLabel, BorderLayout.WEST);
        panel.add(lookup, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createBirdSection() {
        JPanel panel = UiKit.sectionPanel();
        panel.add(sectionHeading(
                "Estado y medidas",
                "Completa solo los datos que hayas podido observar o medir."
        ), BorderLayout.NORTH);
        JPanel grid = grid();
        addField(grid, 0, "Sexo", sexCombo);
        addField(grid, 1, "Estado", statusCombo);
        addField(grid, 2, "Condición general", conditionCombo);
        addField(grid, 3, "Reproducción", reproductiveField);
        addField(grid, 4, "Intensidad de muda", moultIntensityField);
        addField(grid, 5, "Extensión de muda", moultExtensionField);
        addField(grid, 6, "Vuelta", returnField);
        panel.add(grid, BorderLayout.CENTER);

        JPanel measurements = new JPanel(new GridLayout(0, photoEditor == null ? 4 : 2, 12, 10));
        measurements.setName("measurementsGrid");
        measurements.setOpaque(false);
        addMeasurement(measurements, "Edad", ageEuringCodeField);
        addNumber(measurements, "Ala", "wing");
        addNumber(measurements, "P3", "p3");
        addNumber(measurements, "Tarso", "torso");
        addNumber(measurements, "Peso", "weight");
        addNumber(measurements, "Grasa", "fat");
        addNumber(measurements, "Músculo", "muscle");
        JPanel lower = new JPanel(new BorderLayout(20, 0));
        lower.setOpaque(false);
        lower.setBorder(new javax.swing.border.EmptyBorder(15, 0, 0, 0));
        JPanel measurementHolder = new JPanel(new BorderLayout());
        measurementHolder.setOpaque(false);
        measurementHolder.add(measurements, BorderLayout.NORTH);
        lower.add(measurementHolder, BorderLayout.CENTER);
        if (photoEditor != null) lower.add(photoEditor, BorderLayout.EAST);
        panel.add(lower, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createWeatherSection() {
        JPanel panel = UiKit.sectionPanel();
        panel.add(sectionHeading(
                "Entorno",
                "El contexto ambiental es opcional y ayuda a recordar el momento."
        ), BorderLayout.NORTH);
        JPanel grid = grid();
        addField(grid, 0, "Nubes", cloudsField);
        addField(grid, 1, "Lluvia", rainField);
        addField(grid, 2, "Viento", windField);
        addField(grid, 3, "Sensación térmica", thermalField);
        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createNotesSection() {
        JPanel panel = UiKit.sectionPanel();
        panel.add(sectionHeading(
                "Notas de campo",
                "Escribe aquí aquello que no cabe en una medida."
        ), BorderLayout.NORTH);
        observationsArea.setLineWrap(true);
        observationsArea.setWrapStyleWord(true);
        observationsArea.putClientProperty(
                "JTextArea.placeholderText",
                "Comportamiento, hábitat, incidencias o cualquier detalle que quieras recordar…"
        );
        observationsArea.setBorder(new RoundedBorder(
                null,
                16,
                1,
                new Insets(11, 12, 11, 12)
        ));
        panel.add(observationsArea, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createRegistrationSection() {
        JPanel panel = UiKit.sectionPanel();
        panel.add(sectionHeading(
                "Datos de registro",
                "Información administrativa para completar la ficha."
        ), BorderLayout.NORTH);
        JPanel grid = grid();
        addField(grid, 0, "Clasificación histórica", captureTypeCombo);
        addField(grid, 1, "Estado vital", deadCheck);
        panel.add(grid, BorderLayout.CENTER);
        return panel;
    }

    private JPanel sectionHeading(String title, String description) {
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(UiKit.valueLabel(title));
        heading.add(Box.createVerticalStrut(3));
        heading.add(UiKit.muted(description));
        return heading;
    }

    private JPanel grid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        return grid;
    }

    private void addField(JPanel grid, int position, String label, Component field) {
        JPanel item = new JPanel(new BorderLayout(0, 6));
        item.setOpaque(false);
        item.add(UiKit.muted(label), BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);
        item.setMinimumSize(new Dimension(0, item.getMinimumSize().height));

        int column = position % 2;
        int row = position / 2;
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.weightx = 0.5;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(
                6,
                column == 0 ? 0 : 8,
                6,
                column == 0 ? 8 : 0
        );
        grid.add(item, constraints);
    }

    private void addNumber(JPanel parent, String label, String key) {
        boolean integer = "fat".equals(key) || "muscle".equals(key);
        MeasurementInput input = new MeasurementInput(label, key, integer ? null : "weight".equals(key) ? "g" : "mm", integer);
        measurementInputs.put(key, input);
        numberFields.put(key, input.field());
        parent.add(input);
    }

    private void addMeasurement(JPanel parent, String label, JTextField field) {
        JPanel item = new JPanel(new BorderLayout(0, 3));
        item.setOpaque(false);
        JLabel caption = UiKit.muted(label);
        caption.setLabelFor(field);
        item.add(caption, BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(item, BorderLayout.NORTH);
        parent.add(holder);
    }

    private static JComboBox<String> combo(String... values) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setPreferredSize(new Dimension(260, combo.getPreferredSize().height));
        return combo;
    }

    private static JComboBox<PlaceSummary> placeSelector() {
        JComboBox<PlaceSummary> combo = new JComboBox<>();
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focused
            ) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                String label = placeLabel((PlaceSummary) value);
                setText(label);
                setToolTipText(label);
                return this;
            }
        });
        combo.setPreferredSize(new Dimension(260, combo.getPreferredSize().height));
        combo.setMinimumSize(new Dimension(0, combo.getPreferredSize().height));
        combo.addActionListener(event -> combo.setToolTipText(
                placeLabel((PlaceSummary) combo.getSelectedItem())));
        return combo;
    }

    private static String placeLabel(PlaceSummary place) {
        if (place == null) {
            return "";
        }
        String coordinates = place.latitude() == null || place.longitude() == null
                ? "Sin coordenadas"
                : String.format(Locale.ROOT, "%.6f, %.6f", place.latitude(), place.longitude());
        return place + " · " + coordinates;
    }

    private static JComboBox<BirdStatusChoice> birdStatusCombo() {
        JComboBox<BirdStatusChoice> combo = new JComboBox<>();
        combo.setMaximumRowCount(16);
        combo.setPreferredSize(new Dimension(260, combo.getPreferredSize().height));
        combo.getAccessibleContext().setAccessibleName("Estado del ave");
        combo.getAccessibleContext().setAccessibleDescription(
                "Código y descripción oficial del estado observado en el ave"
        );
        return combo;
    }

    public void refreshCatalogs() {
        SpeciesOption selectedSpecies = (SpeciesOption) speciesCombo.getSelectedItem();
        PlaceSummary selectedPlace = (PlaceSummary) placeCombo.getSelectedItem();
        Long selectedSpeciesId = pendingSpeciesId != null
                ? pendingSpeciesId
                : selectedSpecies == null ? null : selectedSpecies.id();
        Long selectedPlaceId = pendingPlaceId != null
                ? pendingPlaceId
                : selectedPlace == null ? null : selectedPlace.id();
        boolean editing = editingEventId != null;
        int generation = ++catalogLoadGeneration;

        new SwingWorker<Options, Void>() {
            @Override
            protected Options doInBackground() {
                return new Options(
                        catalogRepository.findSpeciesOptions(),
                        catalogRepository.findPlaceSummaries()
                );
            }

            @Override
            protected void done() {
                if (generation != catalogLoadGeneration || saving) {
                    return;
                }
                try {
                    Options options = get();
                    boolean cleanSpecies = !changes.isChanged(speciesCombo);
                    boolean cleanPlace = !changes.isChanged(placeCombo);
                    SpeciesOption liveSpecies = (SpeciesOption) speciesCombo.getSelectedItem();
                    PlaceSummary livePlace = (PlaceSummary) placeCombo.getSelectedItem();
                    Long speciesToSelect = cleanSpecies ? selectedSpeciesId : liveSpecies == null ? null : liveSpecies.id();
                    Long placeToSelect = cleanPlace ? selectedPlaceId : livePlace == null ? null : livePlace.id();
                    speciesCombo.removeAllItems();
                    options.species().forEach(speciesCombo::addItem);
                    if (speciesToSelect != null) {
                        if (!selectSpecies(speciesToSelect)
                                && pendingSpeciesFallback != null) {
                            speciesCombo.addItem(pendingSpeciesFallback);
                            speciesCombo.setSelectedItem(pendingSpeciesFallback);
                        }
                    }
                    pendingSpeciesId = null;
                    pendingSpeciesFallback = null;

                    placeCombo.removeAllItems();
                    placeCombo.addItem(null);
                    options.places().forEach(placeCombo::addItem);
                    if (placeToSelect != null) {
                        if (!selectPlace(placeToSelect)
                                && pendingPlaceFallback != null) {
                            placeCombo.addItem(pendingPlaceFallback);
                            placeCombo.setSelectedItem(pendingPlaceFallback);
                        }
                    } else if (!editing && cleanPlace) {
                        options.places().stream()
                                .filter(PlaceSummary::isDefault)
                                .findFirst()
                                .ifPresent(placeCombo::setSelectedItem);
                    }
                    pendingPlaceId = null;
                    pendingPlaceFallback = null;
                    if (cleanSpecies) changes.acceptValue(speciesCombo);
                    if (cleanPlace) changes.acceptValue(placeCombo);
                    statusLabel.setText(editing
                            ? "Revisa los cambios antes de guardarlos."
                            : options.species().isEmpty()
                                    ? "No hay especies activas para crear un anillamiento."
                                    : "Completa los datos del momento que quieres guardar.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Se ha interrumpido la carga de catálogos.");
                } catch (ExecutionException exception) {
                    statusLabel.setText("No se han podido cargar especies y lugares.");
                }
            }
        }.execute();
    }

    private boolean selectPlace(long placeId) {
        for (int index = 0; index < placeCombo.getItemCount(); index++) {
            PlaceSummary option = placeCombo.getItemAt(index);
            if (option != null && option.id() == placeId) {
                placeCombo.setSelectedIndex(index);
                return true;
            }
        }
        return false;
    }

    private void scheduleRingLookup() {
        if (editingEventId != null) {
            return;
        }
        if (ringNumberField.getText().isBlank()) {
            ringLookupTimer.stop();
            birdLookupLabel.setText("Escribe una anilla para comprobar su historial.");
            speciesCombo.setEnabled(true);
            return;
        }
        ringLookupTimer.restart();
    }

    private void lookupRing() {
        if (editingEventId != null) {
            return;
        }
        String requestedRing = normalizedRing();
        if (requestedRing.isBlank()) {
            return;
        }
        birdLookupLabel.setText("Comprobando la anilla…");

        new SwingWorker<Optional<BirdLookup>, Void>() {
            @Override
            protected Optional<BirdLookup> doInBackground() {
                return eventRepository.findBirdByRingNumber(requestedRing);
            }

            @Override
            protected void done() {
                if (!requestedRing.equals(normalizedRing()) || saving) {
                    return;
                }
                try {
                    Optional<BirdLookup> result = get();
                    if (result.isPresent()) {
                        BirdLookup bird = result.get();
                        selectSpecies(bird.speciesId());
                        speciesCombo.setEnabled(false);
                        birdLookupLabel.setText(
                                "Ave encontrada · "
                                        + bird.species()
                                        + " · "
                                        + bird.eventCount()
                                        + (bird.eventCount() == 1
                                        ? " evento anterior"
                                        : " eventos anteriores")
                        );
                    } else {
                        speciesCombo.setEnabled(true);
                        birdLookupLabel.setText(
                                "Anilla nueva · se creará con su primer anillamiento."
                        );
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    birdLookupLabel.setText("Comprobación interrumpida.");
                } catch (ExecutionException exception) {
                    speciesCombo.setEnabled(true);
                    birdLookupLabel.setText("No se ha podido comprobar la anilla.");
                }
            }
        }.execute();
    }

    private boolean selectSpecies(long speciesId) {
        for (int index = 0; index < speciesCombo.getItemCount(); index++) {
            SpeciesOption option = speciesCombo.getItemAt(index);
            if (option.id() == speciesId) {
                speciesCombo.setSelectedIndex(index);
                return true;
            }
        }
        return false;
    }

    private void save() {
        if (saving || !mutationsEnabled) return;
        final BirdEventInput input;
        try {
            input = collectInput();
        } catch (IllegalArgumentException exception) {
            statusLabel.setText(exception.getMessage());
            return;
        }

        saving = true;
        ringLookupTimer.stop();
        changes.suspendEditing();
        updateActionAvailability();
        long generation = formGeneration;
        List<Path> photos = photoEditor == null ? List.of() : photoEditor.pendingPhotos();
        List<String> removedPhotos = photoEditor == null ? List.of() : photoEditor.removedPhotos();
        Long eventId = editingEventId;
        statusLabel.setText(eventId == null
                ? "Guardando el registro…"
                : "Guardando los cambios…");

        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() throws IOException {
                if (!photos.isEmpty() || !removedPhotos.isEmpty()) {
                    return photoService.saveEvent(eventId, input, photos, removedPhotos);
                }
                if (eventId == null) {
                    return eventRepository.insert(input);
                }
                eventRepository.update(eventId, input);
                return eventId;
            }

            @Override
            protected void done() {
                if (generation != formGeneration) return;
                try {
                    long id = get();
                    if (photoEditor != null) photoEditor.discardPending();
                    saving = false;
                    changes.resumeEditing();
                    changes.markClean();
                    statusLabel.setText(eventId == null
                            ? "Registro guardado"
                            : "Cambios guardados");
                    onSaved.accept(id);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    statusLabel.setText(cause instanceof IllegalArgumentException || cause instanceof IOException
                            ? cause.getMessage()
                            : "No se ha podido guardar; revisa los datos e inténtalo de nuevo.");
                } finally {
                    saving = false;
                    changes.resumeEditing();
                    updateActionAvailability();
                }
            }
        }.execute();
    }

    private BirdEventInput collectInput() {
        MeasurementInput firstInvalid = null;
        for (MeasurementInput measurement : measurementInputs.values()) {
            if (!measurement.validateValue() && firstInvalid == null) firstInvalid = measurement;
        }
        if (firstInvalid != null) {
            MeasurementInput target = firstInvalid;
            javax.swing.SwingUtilities.invokeLater(() -> {
                target.scrollRectToVisible(new java.awt.Rectangle(0, 0, target.getWidth(), target.getHeight()));
                target.field().requestFocusInWindow();
            });
            throw new IllegalArgumentException("Revisa las medidas señaladas antes de guardar.");
        }
        if (ringNumberField.getText().isBlank()) {
            throw new IllegalArgumentException("Indica el número de anilla.");
        }
        SpeciesOption species = (SpeciesOption) speciesCombo.getSelectedItem();
        return new BirdEventInput(
                normalizedRing(),
                species == null ? null : species.id(),
                (EventType) eventTypeCombo.getSelectedItem(),
                dateSelector.getIsoDate(),
                timeSelector.getIsoTime(),
                selectedPlaceId(),
                locationTextField.getText().trim(),
                sexCode((String) sexCombo.getSelectedItem()),
                ageEuringCodeField.getText().trim(),
                parseOptionalInteger(numberFields.get("fat").getText()),
                parseOptionalInteger(numberFields.get("muscle").getText()),
                preservedRingerInitials,
                selectedBirdStatusCode(),
                reproductiveField.getText().trim(),
                moultIntensityField.getText().trim(),
                moultExtensionField.getText().trim(),
                conditionCode((String) conditionCombo.getSelectedItem()),
                returnField.getText().trim(),
                parseOptionalDouble(numberFields.get("wing").getText()),
                parseOptionalDouble(numberFields.get("p3").getText()),
                parseOptionalDouble(numberFields.get("torso").getText()),
                parseOptionalDouble(numberFields.get("weight").getText()),
                observationsArea.getText().trim(),
                cloudsField.getText().trim(),
                rainField.getText().trim(),
                thermalField.getText().trim(),
                windField.getText().trim(),
                captureTypeCode((String) captureTypeCombo.getSelectedItem()),
                deadCheck.isSelected()
        );
    }

    private String normalizedRing() {
        return ringNumberField.getText().trim().toUpperCase(Locale.ROOT);
    }

    private Long selectedPlaceId() {
        PlaceSummary place = (PlaceSummary) placeCombo.getSelectedItem();
        return place == null ? null : place.id();
    }

    private static String sexCode(String value) {
        return switch (UiKit.display(value)) {
            case "Macho" -> "M";
            case "Hembra" -> "F";
            case "Sin indicar", "—" -> null;
            default -> UiKit.display(value).toUpperCase(Locale.ROOT);
        };
    }

    private static String captureTypeCode(String value) {
        return switch (UiKit.display(value)) {
            case "Captura" -> "CAPTURE";
            case "Recaptura" -> "RECAPTURE";
            case "Sin indicar", "—" -> null;
            default -> UiKit.display(value).toUpperCase(Locale.ROOT);
        };
    }

    private static String conditionCode(String value) {
        return switch (UiKit.display(value)) {
            case "Buen estado" -> "GOOD";
            case "Delicado" -> "POOR";
            case "Muerto" -> "DEAD";
            case "Sin indicar", "—" -> null;
            default -> UiKit.display(value).toUpperCase(Locale.ROOT);
        };
    }

    private static void resetCombo(JComboBox<String> combo, String... values) {
        combo.removeAllItems();
        for (String value : values) {
            combo.addItem(value);
        }
        combo.setSelectedIndex(0);
    }

    private void resetBirdStatusCombo(String selectedCode) {
        statusCombo.removeAllItems();
        statusCombo.addItem(BirdStatusChoice.unspecified());
        for (BirdStatusCatalog.Entry entry : BirdStatusCatalog.entries()) {
            statusCombo.addItem(BirdStatusChoice.official(entry));
        }
        selectBirdStatusCode(selectedCode);
    }

    private void selectBirdStatusCode(String code) {
        if (code == null || code.isBlank()) {
            statusCombo.setSelectedIndex(0);
            return;
        }
        String normalized = code.trim();
        for (int index = 0; index < statusCombo.getItemCount(); index++) {
            BirdStatusChoice choice = statusCombo.getItemAt(index);
            if (choice.matches(normalized)) {
                statusCombo.setSelectedIndex(index);
                return;
            }
        }
        BirdStatusChoice historical = BirdStatusChoice.historical(normalized);
        statusCombo.addItem(historical);
        statusCombo.setSelectedItem(historical);
    }

    private String selectedBirdStatusCode() {
        BirdStatusChoice choice = (BirdStatusChoice) statusCombo.getSelectedItem();
        return choice == null ? null : choice.code();
    }

    private static void selectCode(JComboBox<String> combo, String value) {
        String display = value == null || value.isBlank()
                ? "Sin indicar"
                : UiKit.friendlyCode(value);
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (display.equals(combo.getItemAt(index))) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        combo.addItem(display);
        combo.setSelectedItem(display);
    }

    private record BirdStatusChoice(String code, String label) {

        private static BirdStatusChoice unspecified() {
            return new BirdStatusChoice(null, "Sin indicar");
        }

        private static BirdStatusChoice official(BirdStatusCatalog.Entry entry) {
            return new BirdStatusChoice(entry.code(), entry.displayLabel());
        }

        private static BirdStatusChoice historical(String code) {
            String readable = UiKit.friendlyCode(code);
            String label = code.equalsIgnoreCase(readable)
                    ? code + " · Valor conservado del registro"
                    : code + " · " + readable + " (valor conservado)";
            return new BirdStatusChoice(code, label);
        }

        private boolean matches(String value) {
            return code != null && code.equalsIgnoreCase(value);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void setNumber(String key, Number value) {
        numberFields.get(key).setText(value == null ? "" : value.toString());
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            return LocalDate.now();
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static Integer parseOptionalInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Usa números enteros en las puntuaciones.");
        }
    }

    public static Double parseOptionalDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Usa números válidos en las medidas.");
        }
    }

    private record Options(
            List<SpeciesOption> species,
            List<PlaceSummary> places
    ) {
    }
}

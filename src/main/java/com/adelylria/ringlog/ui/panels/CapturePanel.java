package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
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
    private final JTextField initialsField;
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
    private Long pendingSpeciesId;
    private Long pendingPlaceId;
    private SpeciesOption pendingSpeciesFallback;
    private PlaceSummary pendingPlaceFallback;
    private int catalogLoadGeneration;

    public CapturePanel(
            BirdEventRepository eventRepository,
            CatalogRepository catalogRepository,
            Consumer<Long> onSaved,
            Runnable onCancel
    ) {
        this.eventRepository = eventRepository;
        this.catalogRepository = catalogRepository;
        this.onSaved = onSaved;
        this.onCancel = onCancel;
        this.ringNumberField = new JTextField();
        this.speciesCombo = new JComboBox<>();
        this.eventTypeCombo = new JComboBox<>(EventType.values());
        this.placeCombo = new JComboBox<>();
        this.sexCombo = combo("Sin indicar", "Macho", "Hembra");
        this.statusCombo = birdStatusCombo();
        this.statusCombo.setName("birdStatusField");
        this.conditionCombo = combo("Buen estado", "Regular", "Delicado");
        this.captureTypeCombo = combo("Sin indicar", "Captura", "Recaptura");
        this.dateSelector = new DateSelector(LocalDate.now());
        this.timeSelector = new TimeSelector(LocalTime.now());
        this.locationTextField = new JTextField();
        this.ageEuringCodeField = new JTextField();
        this.initialsField = new JTextField();
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
        editingEventId = null;
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
        resetCombo(conditionCombo, "Buen estado", "Regular", "Delicado");
        resetCombo(captureTypeCombo, "Sin indicar", "Captura", "Recaptura");
        ageEuringCodeField.setText("");
        initialsField.setText("");
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
        saveButton.setEnabled(true);
        renderHeader();
        refreshCatalogs();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        saveButton.setEnabled(enabled);
    }

    public void edit(BirdEventDetail detail) {
        if (detail == null) {
            throw new IllegalArgumentException("Falta el registro que quieres editar.");
        }
        editingEventId = detail.id();
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
        initialsField.setText(text(detail.ringerInitials()));
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
        saveButton.setEnabled(true);
        renderHeader();
        refreshCatalogs();
    }

    private void renderHeader() {
        boolean editing = editingEventId != null;
        JButton cancelButton = UiKit.secondaryButton(editing ? "Cancelar edición" : "Cancelar");
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
        addField(grid, 3, "Iniciales", initialsField);
        addField(grid, 4, "Reproducción", reproductiveField);
        addField(grid, 5, "Intensidad de muda", moultIntensityField);
        addField(grid, 6, "Extensión de muda", moultExtensionField);
        addField(grid, 7, "Vuelta", returnField);
        panel.add(grid, BorderLayout.CENTER);

        JPanel measurements = new JPanel(new GridLayout(2, 3, 12, 10));
        measurements.setOpaque(false);
        measurements.setBorder(new javax.swing.border.EmptyBorder(15, 0, 0, 0));
        addNumber(measurements, "Ala", "wing");
        addNumber(measurements, "P3", "p3");
        addNumber(measurements, "Tarso", "torso");
        addNumber(measurements, "Peso", "weight");
        addNumber(measurements, "Grasa", "fat");
        addNumber(measurements, "Músculo", "muscle");
        panel.add(measurements, BorderLayout.SOUTH);
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
        addField(grid, 0, "Código de edad EURING", ageEuringCodeField);
        addField(grid, 1, "Clasificación histórica", captureTypeCombo);
        addField(grid, 2, "Estado vital", deadCheck);
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
        JPanel item = new JPanel(new BorderLayout(0, 3));
        item.setOpaque(false);
        item.add(UiKit.muted(label), BorderLayout.NORTH);
        item.add(addNumberField(key), BorderLayout.CENTER);
        parent.add(item);
    }

    private JTextField addNumberField(String key) {
        JTextField field = new JTextField(7);
        numberFields.put(key, field);
        return field;
    }

    private static JComboBox<String> combo(String... values) {
        JComboBox<String> combo = new JComboBox<>(values);
        combo.setPreferredSize(new Dimension(260, combo.getPreferredSize().height));
        return combo;
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
                if (generation != catalogLoadGeneration) {
                    return;
                }
                try {
                    Options options = get();
                    speciesCombo.removeAllItems();
                    options.species().forEach(speciesCombo::addItem);
                    if (selectedSpeciesId != null) {
                        if (!selectSpecies(selectedSpeciesId)
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
                    if (selectedPlaceId != null) {
                        if (!selectPlace(selectedPlaceId)
                                && pendingPlaceFallback != null) {
                            placeCombo.addItem(pendingPlaceFallback);
                            placeCombo.setSelectedItem(pendingPlaceFallback);
                        }
                    } else if (!editing) {
                        options.places().stream()
                                .filter(PlaceSummary::isDefault)
                                .findFirst()
                                .ifPresent(placeCombo::setSelectedItem);
                    }
                    pendingPlaceId = null;
                    pendingPlaceFallback = null;
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
                if (!requestedRing.equals(normalizedRing())) {
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
        final BirdEventInput input;
        try {
            input = collectInput();
        } catch (IllegalArgumentException exception) {
            statusLabel.setText(exception.getMessage());
            return;
        }

        saveButton.setEnabled(false);
        Long eventId = editingEventId;
        statusLabel.setText(eventId == null
                ? "Guardando el registro…"
                : "Guardando los cambios…");

        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() {
                if (eventId == null) {
                    return eventRepository.insert(input);
                }
                eventRepository.update(eventId, input);
                return eventId;
            }

            @Override
            protected void done() {
                try {
                    long id = get();
                    statusLabel.setText(eventId == null
                            ? "Registro guardado"
                            : "Cambios guardados");
                    onSaved.accept(id);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    saveButton.setEnabled(true);
                    statusLabel.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    saveButton.setEnabled(true);
                    Throwable cause = exception.getCause();
                    statusLabel.setText(cause instanceof IllegalArgumentException
                            ? cause.getMessage()
                            : "No se ha podido guardar; revisa los datos e inténtalo de nuevo.");
                }
            }
        }.execute();
    }

    private BirdEventInput collectInput() {
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
                initialsField.getText().trim(),
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

package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.JFileChooser;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventReportRow;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportResult;
import com.adelylria.ringlog.report.RecordReportSelection;
import com.adelylria.ringlog.report.RecordReportService;
import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.TimelineView;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public class CaptureListPanel extends JPanel {

    private static final int PAGE_SIZE = 30;
    private static final int FILTERED_PAGE_SIZE = 100;
    private static final String ALL_SPECIES = "Todas las especies";
    private static final String ALL_PLACES = "Todos los lugares";
    private static final String ALL_DATES = "Cualquier fecha";
    private static final String DATE_BY_DAY = "Día";
    private static final String DATE_BY_MONTH = "Mes";
    private static final String DATE_BY_YEAR = "Año";
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter
            .ofPattern("MMM uuuu", Locale.forLanguageTag("es-ES"));

    private final BirdEventRepository eventRepository;
    private final Consumer<Long> onOpenEvent;
    private final Runnable onNewEvent;
    private final BiConsumer<RecordReportFormat, RecordReportSelection> exportAction;
    private final JTextField searchField;
    private final JComboBox<String> speciesCombo;
    private final JComboBox<String> placeCombo;
    private final JComboBox<Object> typeCombo;
    private final JComboBox<String> dateModeCombo;
    private final JComboBox<DateFilterOption> datePeriodCombo;
    private final JComboBox<DateSortOrder> dateSortOrderCombo;
    private final CalendarDatePicker dayPicker;
    private final TimelineView timelineView;
    private final JLabel statusLabel;
    private final JButton showMoreButton;
    private final JButton showAllButton;
    private final JButton clearFiltersButton;
    private final JButton exportButton;
    private final boolean canCreateEvent;
    private JButton newEventButton;
    private List<BirdEventTimelineItem> events = List.of();
    private int visibleLimit = PAGE_SIZE;
    private boolean updatingFilterOptions;
    private boolean exportInProgress;

    public CaptureListPanel(
            BirdEventRepository eventRepository,
            Consumer<Long> onOpenEvent,
            Runnable onNewEvent
    ) {
        this(eventRepository, onOpenEvent, onNewEvent, null, true);
    }

    public CaptureListPanel(
            BirdEventRepository eventRepository,
            Consumer<Long> onOpenEvent,
            Runnable onNewEvent,
            BiConsumer<RecordReportFormat, RecordReportSelection> exportAction
    ) {
        this(eventRepository, onOpenEvent, onNewEvent, exportAction, true);
    }

    public CaptureListPanel(
            BirdEventRepository eventRepository,
            Consumer<Long> onOpenEvent,
            Runnable onNewEvent,
            BiConsumer<RecordReportFormat, RecordReportSelection> exportAction,
            boolean canCreateEvent
    ) {
        this.eventRepository = eventRepository;
        this.onOpenEvent = onOpenEvent;
        this.onNewEvent = onNewEvent;
        this.canCreateEvent = canCreateEvent;
        this.searchField = new JTextField(20);
        this.speciesCombo = new JComboBox<>();
        this.placeCombo = new JComboBox<>();
        this.typeCombo = new JComboBox<>();
        this.dateModeCombo = new JComboBox<>(new String[] {
                ALL_DATES,
                DATE_BY_DAY,
                DATE_BY_MONTH,
                DATE_BY_YEAR
        });
        this.dateModeCombo.setName("dateFilterMode");
        this.dateModeCombo.getAccessibleContext().setAccessibleName(
                "Agrupar filtro de fecha"
        );
        this.datePeriodCombo = new JComboBox<>();
        this.datePeriodCombo.setName("dateFilterPeriod");
        this.datePeriodCombo.getAccessibleContext().setAccessibleName(
                "Periodo del filtro de fecha"
        );
        this.dateSortOrderCombo = new JComboBox<>(DateSortOrder.values());
        this.dateSortOrderCombo.setName("dateSortOrder");
        this.dateSortOrderCombo.getAccessibleContext().setAccessibleName(
                "Orden de los registros por fecha"
        );
        this.dayPicker = new CalendarDatePicker(null);
        this.typeCombo.addItem("Todos los tipos");
        for (EventType type : EventType.values()) {
            this.typeCombo.addItem(type);
        }
        this.timelineView = new TimelineView();
        this.statusLabel = UiKit.muted("Cargando…");
        this.showMoreButton = UiKit.secondaryButton("Mostrar más");
        this.showMoreButton.setVisible(false);
        this.showMoreButton.addActionListener(event -> {
            visibleLimit += PAGE_SIZE;
            applyFilters();
        });
        this.showAllButton = UiKit.secondaryButton("Mostrar todo");
        this.showAllButton.setVisible(false);
        this.showAllButton.addActionListener(event -> {
            visibleLimit = Integer.MAX_VALUE;
            applyFilters();
        });
        this.clearFiltersButton = UiKit.secondaryButton("Limpiar filtros");
        this.clearFiltersButton.addActionListener(event -> clearFilters());
        this.exportButton = UiKit.secondaryButton("Exportar ▾");
        this.exportButton.setName("exportRecords");
        this.exportButton.setEnabled(false);
        this.exportAction = exportAction == null
                ? this::chooseAndExport
                : exportAction;
        configureExportMenu();

        initialize();
    }

    public CaptureListPanel() {
        this(new BirdEventRepository(), ignored -> { }, () -> { });
    }

    private void initialize() {
        setLayout(new BorderLayout());

        JPanel page = UiKit.pagePanel();
        javax.swing.JButton action = null;
        if (canCreateEvent) {
            newEventButton = UiKit.primaryButton("Nuevo registro");
            newEventButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newEventButton.setIconTextGap(9);
            newEventButton.addActionListener(event -> onNewEvent.run());
            action = newEventButton;
        }
        page.add(new PageHeader(
                "ARCHIVO VIVO",
                "Registros",
                "Anillamientos, controles y recuperaciones como páginas de tu diario.",
                action
        ), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.add(createFilters(), BorderLayout.NORTH);
        JPanel archive = new JPanel(new BorderLayout(0, 12));
        archive.setOpaque(false);
        archive.add(timelineView, BorderLayout.CENTER);
        JPanel pagination = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        pagination.setOpaque(false);
        pagination.add(showMoreButton);
        pagination.add(showAllButton);
        archive.add(pagination, BorderLayout.SOUTH);
        body.add(archive, BorderLayout.CENTER);
        page.add(body, BorderLayout.CENTER);

        JScrollPane scroll = UiKit.scrollPane(page);
        add(scroll, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filterChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filterChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filterChanged();
            }
        });
        speciesCombo.addActionListener(event -> filterChanged());
        placeCombo.addActionListener(event -> filterChanged());
        typeCombo.addActionListener(event -> filterChanged());
        dateModeCombo.addActionListener(event -> dateModeChanged());
        datePeriodCombo.addActionListener(event -> filterChanged());
        dateSortOrderCombo.addActionListener(event -> filterChanged());
        dayPicker.addPropertyChangeListener(
                CalendarDatePicker.SELECTED_DATE_PROPERTY,
                event -> filterChanged()
        );
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (newEventButton != null) {
            newEventButton.setEnabled(enabled);
        }
    }

    private JPanel createFilters() {
        SurfacePanel filters = new SurfacePanel(new BorderLayout(0, 10));
        filters.setBorder(new com.adelylria.ringlog.ui.theme.RoundedBorder(
                null,
                18,
                1,
                new java.awt.Insets(11, 13, 11, 13)
        ));

        JPanel searchRow = new JPanel(new BorderLayout(14, 0));
        searchRow.setOpaque(false);
        searchField.putClientProperty(
                "JTextField.placeholderText",
                "Buscar por anilla, fecha, especie, lugar, tipo o nota…"
        );
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(statusLabel, BorderLayout.EAST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        controls.setOpaque(false);
        typeCombo.setPreferredSize(new java.awt.Dimension(170, 38));
        speciesCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        placeCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        dateSortOrderCombo.setPreferredSize(new java.awt.Dimension(190, 38));
        controls.add(typeCombo);
        controls.add(speciesCombo);
        controls.add(placeCombo);
        controls.add(UiKit.muted("Orden"));
        controls.add(dateSortOrderCombo);

        JPanel dateControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        dateControls.setOpaque(false);
        dateModeCombo.setPreferredSize(new java.awt.Dimension(170, 38));
        datePeriodCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        dayPicker.setPreferredSize(new java.awt.Dimension(210, 38));
        dayPicker.setVisible(false);
        dateControls.add(UiKit.muted("Fecha"));
        dateControls.add(dateModeCombo);
        dateControls.add(dayPicker);
        dateControls.add(datePeriodCombo);
        dateControls.add(clearFiltersButton);

        JPanel dateAndExport = new JPanel(new BorderLayout(10, 0));
        dateAndExport.setOpaque(false);
        dateAndExport.add(dateControls, BorderLayout.CENTER);
        dateAndExport.add(exportButton, BorderLayout.EAST);

        JPanel filterControls = new JPanel();
        filterControls.setOpaque(false);
        filterControls.setLayout(new BoxLayout(filterControls, BoxLayout.Y_AXIS));
        controls.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        dateAndExport.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        filterControls.add(controls);
        filterControls.add(Box.createVerticalStrut(8));
        filterControls.add(dateAndExport);

        filters.add(searchRow, BorderLayout.NORTH);
        filters.add(filterControls, BorderLayout.CENTER);
        return filters;
    }

    private void configureExportMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (RecordReportFormat format : RecordReportFormat.values()) {
            JMenuItem item = new JMenuItem(format.toString());
            item.setName("export" + format.name());
            item.addActionListener(event -> requestExport(format));
            menu.add(item);
        }
        exportButton.setComponentPopupMenu(menu);
        exportButton.addActionListener(event -> {
            if (exportButton.isShowing()) {
                menu.show(exportButton, 0, exportButton.getHeight() + 3);
            }
        });
    }

    private void requestExport(RecordReportFormat format) {
        List<BirdEventTimelineItem> filtered = filteredEvents();
        if (filtered.isEmpty()) {
            return;
        }
        if (filtered.size() > format.maximumRecords()) {
            showExportError(
                    "El formato " + format + " admite un máximo seguro de "
                            + format.maximumRecords() + " registros. "
                            + "Acota la selección con los filtros."
            );
            return;
        }
        exportAction.accept(
                format,
                new RecordReportSelection(
                        filtered.stream().map(BirdEventTimelineItem::id).toList(),
                        selectedFilterDescriptions()
                )
        );
    }

    private List<String> selectedFilterDescriptions() {
        List<String> descriptions = new ArrayList<>();
        String query = searchField.getText().trim();
        if (!query.isBlank()) {
            descriptions.add("Búsqueda: " + query);
        }
        Object type = typeCombo.getSelectedItem();
        if (type instanceof EventType eventType) {
            descriptions.add("Tipo: " + eventType);
        }
        String species = (String) speciesCombo.getSelectedItem();
        if (species != null && !ALL_SPECIES.equals(species)) {
            descriptions.add("Especie: " + species);
        }
        String place = (String) placeCombo.getSelectedItem();
        if (place != null && !ALL_PLACES.equals(place)) {
            descriptions.add("Lugar: " + place);
        }
        String dateMode = (String) dateModeCombo.getSelectedItem();
        if (dateMode != null && !ALL_DATES.equals(dateMode)) {
            DateFilterOption period = selectedDatePeriod(dateMode);
            if (period != null) {
                descriptions.add("Fecha (" + dateMode.toLowerCase(Locale.ROOT)
                        + "): " + period.label());
            }
        }
        if (dateSortOrderCombo.getSelectedItem() == DateSortOrder.OLDEST_FIRST) {
            descriptions.add("Orden: más antiguos primero");
        }
        return List.copyOf(descriptions);
    }

    private void chooseAndExport(
            RecordReportFormat format,
            RecordReportSelection selection
    ) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Guardar informe de registros");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                format.toString(),
                format.extension()
        ));
        chooser.setSelectedFile(new File(
                "RingLog-registros-" + LocalDate.now() + "." + format.extension()
        ));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path destination = format.pathFor(chooser.getSelectedFile().toPath())
                .toAbsolutePath()
                .normalize();
        if (Files.exists(destination)) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Ya existe un archivo con ese nombre. ¿Quieres sustituirlo?",
                    "Sustituir informe",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        exportInBackground(format, selection, destination);
    }

    private void exportInBackground(
            RecordReportFormat format,
            RecordReportSelection selection,
            Path destination
    ) {
        String previousText = exportButton.getText();
        exportInProgress = true;
        exportButton.setText("Exportando…");
        exportButton.setEnabled(false);
        new SwingWorker<RecordReportResult, Void>() {
            @Override
            protected RecordReportResult doInBackground() throws Exception {
                List<BirdEventReportRow> details = eventRepository.findReportRows(
                        selection.eventIds()
                );
                return new RecordReportService().export(
                        format,
                        destination,
                        details,
                        selection.activeFilters()
                );
            }

            @Override
            protected void done() {
                exportInProgress = false;
                exportButton.setText(previousText);
                exportButton.setEnabled(!filteredEvents().isEmpty());
                try {
                    RecordReportResult result = get();
                    JOptionPane.showMessageDialog(
                            CaptureListPanel.this,
                            "Informe guardado correctamente.\n\n"
                                    + result.recordCount() + " "
                                    + (result.recordCount() == 1
                                            ? "registro"
                                            : "registros")
                                    + "\n" + result.path(),
                            "Informe listo",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showExportError("La exportación se ha interrumpido.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showExportError(cause == null || cause.getMessage() == null
                            ? "No se pudo crear el informe."
                            : cause.getMessage());
                }
            }
        }.execute();
    }

    private void showExportError(String message) {
        JOptionPane.showMessageDialog(
                this,
                message,
                "No se pudo exportar",
                JOptionPane.ERROR_MESSAGE
        );
    }

    public void refresh() {
        statusLabel.setText("Cargando…");
        showMoreButton.setVisible(false);
        showAllButton.setVisible(false);
        exportButton.setEnabled(false);
        visibleLimit = PAGE_SIZE;

        new SwingWorker<List<BirdEventTimelineItem>, Void>() {
            @Override
            protected List<BirdEventTimelineItem> doInBackground() {
                return eventRepository.findTimeline();
            }

            @Override
            protected void done() {
                try {
                    events = get();
                    updateFilterOptions();
                    visibleLimit = hasActiveFilters()
                            ? FILTERED_PAGE_SIZE
                            : PAGE_SIZE;
                    applyFilters();
                } catch (InterruptedException | ExecutionException exception) {
                    events = List.of();
                    statusLabel.setText("No se han podido cargar los registros");
                    timelineView.setItems(events, onOpenEvent, 0);
                    exportButton.setEnabled(false);
                }
            }
        }.execute();
    }

    private void updateFilterOptions() {
        String selectedSpecies = (String) speciesCombo.getSelectedItem();
        String selectedPlace = (String) placeCombo.getSelectedItem();
        List<String> species = events.stream()
                .map(BirdEventTimelineItem::species)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        List<String> places = events.stream()
                .map(BirdEventTimelineItem::place)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        updatingFilterOptions = true;
        try {
            speciesCombo.removeAllItems();
            speciesCombo.addItem(ALL_SPECIES);
            species.forEach(speciesCombo::addItem);
            if (selectedSpecies != null && species.contains(selectedSpecies)) {
                speciesCombo.setSelectedItem(selectedSpecies);
            }

            placeCombo.removeAllItems();
            placeCombo.addItem(ALL_PLACES);
            places.forEach(placeCombo::addItem);
            if (selectedPlace != null && places.contains(selectedPlace)) {
                placeCombo.setSelectedItem(selectedPlace);
            }

            updateDatePeriodOptions();
        } finally {
            updatingFilterOptions = false;
        }
    }

    private void dateModeChanged() {
        if (updatingFilterOptions) {
            return;
        }

        updatingFilterOptions = true;
        try {
            updateDatePeriodOptions();
        } finally {
            updatingFilterOptions = false;
        }
        filterChanged();
    }

    private void updateDatePeriodOptions() {
        String mode = (String) dateModeCombo.getSelectedItem();
        DateFilterOption selected = (DateFilterOption) datePeriodCombo
                .getSelectedItem();
        Set<LocalDate> availableDays = availableEventDates();
        dayPicker.setAvailableDates(availableDays);
        datePeriodCombo.removeAllItems();

        if (DATE_BY_DAY.equals(mode)) {
            datePeriodCombo.setVisible(false);
            dayPicker.setVisible(true);
            dayPicker.setEnabled(true);
            if (dayPicker.getSelectedDate() == null) {
                dayPicker.setSelectedDate(availableDays.stream()
                        .max(Comparator.naturalOrder())
                        .orElse(LocalDate.now()));
            }
            return;
        }

        dayPicker.setVisible(false);
        datePeriodCombo.setVisible(true);
        if (mode == null || ALL_DATES.equals(mode)) {
            datePeriodCombo.addItem(new DateFilterOption("", "Todo el archivo"));
            datePeriodCombo.setEnabled(false);
            return;
        }

        Map<String, DateFilterOption> options = new TreeMap<>(
                Comparator.reverseOrder()
        );
        for (BirdEventTimelineItem item : events) {
            DateFilterOption option = dateOption(item.eventDate(), mode);
            if (option != null) {
                options.putIfAbsent(option.key(), option);
            }
        }
        options.values().forEach(datePeriodCombo::addItem);
        datePeriodCombo.setEnabled(!options.isEmpty());
        if (selected != null && options.containsKey(selected.key())) {
            datePeriodCombo.setSelectedItem(options.get(selected.key()));
        }
    }

    private void filterChanged() {
        if (updatingFilterOptions) {
            return;
        }
        visibleLimit = hasActiveFilters() ? FILTERED_PAGE_SIZE : PAGE_SIZE;
        applyFilters();
    }

    private void clearFilters() {
        updatingFilterOptions = true;
        try {
            searchField.setText("");
            typeCombo.setSelectedIndex(0);
            if (speciesCombo.getItemCount() > 0) {
                speciesCombo.setSelectedIndex(0);
            }
            if (placeCombo.getItemCount() > 0) {
                placeCombo.setSelectedIndex(0);
            }
            dateModeCombo.setSelectedIndex(0);
            dateSortOrderCombo.setSelectedItem(DateSortOrder.NEWEST_FIRST);
            dayPicker.setSelectedDate(null);
            updateDatePeriodOptions();
        } finally {
            updatingFilterOptions = false;
        }
        visibleLimit = PAGE_SIZE;
        applyFilters();
    }

    private boolean hasActiveFilters() {
        Object type = typeCombo.getSelectedItem();
        String species = (String) speciesCombo.getSelectedItem();
        String place = (String) placeCombo.getSelectedItem();
        String dateMode = (String) dateModeCombo.getSelectedItem();
        return !searchField.getText().isBlank()
                || type instanceof EventType
                || species != null && !ALL_SPECIES.equals(species)
                || place != null && !ALL_PLACES.equals(place)
                || dateMode != null && !ALL_DATES.equals(dateMode);
    }

    private void applyFilters() {
        List<BirdEventTimelineItem> filtered = filteredEvents();

        if (filtered.isEmpty() && hasActiveFilters()) {
            timelineView.setItems(
                    filtered,
                    onOpenEvent,
                    visibleLimit,
                    "No hay registros con estos filtros",
                    "Prueba con otra fecha o ajusta los filtros para ampliar la búsqueda."
            );
        } else {
            timelineView.setItems(filtered, onOpenEvent, visibleLimit);
        }
        int shown = Math.min(filtered.size(), visibleLimit);
        statusLabel.setText(shown == filtered.size()
                ? filtered.size() + " "
                        + (filtered.size() == 1 ? "entrada" : "entradas")
                : "Mostrando " + shown + " de " + filtered.size());

        int remaining = Math.max(0, filtered.size() - visibleLimit);
        showMoreButton.setVisible(remaining > 0);
        showAllButton.setVisible(remaining > PAGE_SIZE);
        exportButton.setEnabled(!exportInProgress && !filtered.isEmpty());
        if (remaining > 0) {
            int nextPage = Math.min(PAGE_SIZE, remaining);
            showMoreButton.setText(
                    "Mostrar " + nextPage + " más · " + remaining + " pendientes"
            );
        } else {
            showMoreButton.setText("Mostrar más");
        }
    }

    private List<BirdEventTimelineItem> filteredEvents() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String species = (String) speciesCombo.getSelectedItem();
        String place = (String) placeCombo.getSelectedItem();
        String dateMode = (String) dateModeCombo.getSelectedItem();
        DateFilterOption datePeriod = selectedDatePeriod(dateMode);
        Object type = typeCombo.getSelectedItem();
        if (species == null) {
            species = ALL_SPECIES;
        }
        if (place == null) {
            place = ALL_PLACES;
        }
        if (dateMode == null) {
            dateMode = ALL_DATES;
        }

        String selectedSpecies = species;
        String selectedPlace = place;
        String selectedDateMode = dateMode;
        return events.stream()
                .filter(item -> selectedSpecies.equals(ALL_SPECIES)
                        || selectedSpecies.equals(item.species()))
                .filter(item -> selectedPlace.equals(ALL_PLACES)
                        || selectedPlace.equals(item.place()))
                .filter(item -> !(type instanceof EventType eventType)
                        || eventType == item.eventType())
                .filter(item -> matchesDate(
                        item.eventDate(),
                        selectedDateMode,
                        datePeriod
                ))
                .filter(item -> query.isBlank() || contains(item, query))
                .sorted(dateComparator())
                .toList();
    }

    private Comparator<BirdEventTimelineItem> dateComparator() {
        DateSortOrder selected = (DateSortOrder) dateSortOrderCombo.getSelectedItem();
        Comparator<String> direction = selected == DateSortOrder.OLDEST_FIRST
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<String> values = Comparator.nullsLast(direction);
        return Comparator.comparing(BirdEventTimelineItem::eventDate, values)
                .thenComparing(BirdEventTimelineItem::eventTime, values);
    }

    private boolean contains(BirdEventTimelineItem item, String query) {
        return String.join(
                        " ",
                        UiKit.display(item.ringNumber()),
                        UiKit.display(item.species()),
                        UiKit.display(item.place()),
                        item.eventType().toString(),
                        UiKit.display(item.eventDate()),
                        UiKit.date(item.eventDate()),
                        UiKit.time(item.eventTime()),
                        UiKit.display(item.observations())
                )
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    private Set<LocalDate> availableEventDates() {
        Set<LocalDate> dates = new HashSet<>();
        for (BirdEventTimelineItem item : events) {
            if (item.eventDate() == null || item.eventDate().isBlank()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(item.eventDate()));
            } catch (DateTimeParseException ignored) {
                // Imported malformed dates remain searchable as text.
            }
        }
        return dates;
    }

    private DateFilterOption selectedDatePeriod(String mode) {
        if (DATE_BY_DAY.equals(mode)) {
            LocalDate selectedDay = dayPicker.getSelectedDate();
            return selectedDay == null
                    ? null
                    : new DateFilterOption(
                            selectedDay.toString(),
                            UiKit.date(selectedDay.toString())
                    );
        }
        return (DateFilterOption) datePeriodCombo.getSelectedItem();
    }

    private static DateFilterOption dateOption(String isoDate, String mode) {
        if (isoDate == null || isoDate.isBlank()) {
            return null;
        }

        try {
            LocalDate date = LocalDate.parse(isoDate);
            return switch (mode) {
                case DATE_BY_DAY -> new DateFilterOption(
                        date.toString(),
                        UiKit.date(date.toString())
                );
                case DATE_BY_MONTH -> new DateFilterOption(
                        date.toString().substring(0, 7),
                        date.format(MONTH_FORMATTER).replace(".", "")
                );
                case DATE_BY_YEAR -> new DateFilterOption(
                        Integer.toString(date.getYear()),
                        Integer.toString(date.getYear())
                );
                default -> null;
            };
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean matchesDate(
            String eventDate,
            String mode,
            DateFilterOption period
    ) {
        if (ALL_DATES.equals(mode)) {
            return true;
        }
        if (eventDate == null || period == null) {
            return false;
        }

        return switch (mode) {
            case DATE_BY_DAY -> eventDate.equals(period.key());
            case DATE_BY_MONTH, DATE_BY_YEAR -> eventDate.startsWith(
                    period.key() + "-"
            );
            default -> true;
        };
    }

    private record DateFilterOption(String key, String label) {

        @Override
        public String toString() {
            return label;
        }
    }

    private enum DateSortOrder {
        NEWEST_FIRST("Más recientes primero"),
        OLDEST_FIRST("Más antiguos primero");

        private final String label;

        DateSortOrder(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}

package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
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
import java.util.concurrent.CancellationException;
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
import javax.swing.DefaultComboBoxModel;
import javax.swing.Timer;
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
import com.adelylria.ringlog.ui.components.DatePeriodSelector;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.TimelineView;
import com.adelylria.ringlog.ui.components.WrappingPanel;
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
    private static final String DATE_RANGE_DAYS = "Rango de días";
    private static final String DATE_RANGE_MONTHS = "Rango de meses";
    private static final String DATE_RANGE_YEARS = "Rango de años";
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
    private final CalendarDatePicker endDayPicker;
    private final DatePeriodSelector rangeStart;
    private final DatePeriodSelector rangeEnd;
    private final JPanel rangeStartHolder;
    private final JPanel rangeEndHolder;
    private final JPanel endDayHolder;
    private final JLabel rangeValidation;
    private final Timer filterDelay;
    private final TimelineView timelineView;
    private final JLabel statusLabel;
    private final JButton showMoreButton;
    private final JButton showAllButton;
    private final JButton clearFiltersButton;
    private final JButton exportButton;
    private final boolean canCreateEvent;
    private JButton newEventButton;
    private int visibleLimit = PAGE_SIZE;
    private boolean updatingFilterOptions;
    private boolean exportInProgress;
    private ArchiveData archiveData = ArchiveData.empty();
    private List<BirdEventTimelineItem> filterResult = List.of();
    private ExactRingSearch filterSummary;
    private FilterSelection lastSelection;
    private List<BirdEventTimelineItem> renderedItems;
    private String renderedEmptyMessage;
    private SwingWorker<FilteredArchive, Void> filterWorker;
    private int loadGeneration;
    private int filterGeneration;
    private boolean loading;
    private boolean filterPending;
    private boolean rangesInitialized;

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
                DATE_BY_YEAR,
                DATE_RANGE_DAYS,
                DATE_RANGE_MONTHS,
                DATE_RANGE_YEARS
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
        this.dayPicker.setName("dateRangeStartDay");
        this.endDayPicker = new CalendarDatePicker(null);
        this.endDayPicker.setName("dateRangeEndDay");
        this.endDayPicker.getAccessibleContext().setAccessibleName("Último día del rango");
        this.rangeStart = new DatePeriodSelector("dateRangeStart", "Desde");
        this.rangeEnd = new DatePeriodSelector("dateRangeEnd", "Hasta");
        this.rangeStartHolder = labeledControl("Desde", rangeStart);
        this.rangeEndHolder = labeledControl("Hasta", rangeEnd);
        this.endDayHolder = labeledControl("Hasta", endDayPicker);
        this.rangeValidation = UiKit.muted("");
        this.rangeValidation.setName("dateRangeValidation");
        this.rangeValidation.setVisible(false);
        this.filterDelay = new Timer(140, event -> applyFilters());
        this.filterDelay.setRepeats(false);
        putClientProperty("RingLog.filterPending", false);
        this.typeCombo.addItem("Todos los tipos");
        for (EventType type : EventType.values()) {
            this.typeCombo.addItem(type);
        }
        this.timelineView = new TimelineView();
        this.statusLabel = UiKit.muted("Cargando…");
        this.statusLabel.setName("recordFilterStatus");
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
        endDayPicker.addPropertyChangeListener(CalendarDatePicker.SELECTED_DATE_PROPERTY,
                event -> filterChanged());
        rangeStart.addPropertyChangeListener(DatePeriodSelector.PERIOD_PROPERTY,
                event -> filterChanged());
        rangeEnd.addPropertyChangeListener(DatePeriodSelector.PERIOD_PROPERTY,
                event -> filterChanged());
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (newEventButton != null) {
            newEventButton.setEnabled(enabled);
        }
    }

    private JPanel createFilters() {
        SurfacePanel filters = new SurfacePanel(new BorderLayout(0, 10));
        filters.setName("recordFilters");
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

        JPanel controls = new WrappingPanel(10, 8);
        controls.setName("primaryFilterRow");
        controls.setOpaque(false);
        typeCombo.setPreferredSize(new java.awt.Dimension(170, 38));
        speciesCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        placeCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        dateSortOrderCombo.setPreferredSize(new java.awt.Dimension(190, 38));
        controls.add(typeCombo);
        controls.add(speciesCombo);
        controls.add(placeCombo);
        JPanel order = new JPanel(new BorderLayout(10, 0));
        order.setOpaque(false);
        order.add(UiKit.muted("Orden"), BorderLayout.WEST);
        order.add(dateSortOrderCombo, BorderLayout.CENTER);
        controls.add(order);

        JPanel dateControls = new WrappingPanel(10, 8);
        dateControls.setName("dateFilterRow");
        dateControls.setOpaque(false);
        dateModeCombo.setPreferredSize(new java.awt.Dimension(170, 38));
        datePeriodCombo.setPreferredSize(new java.awt.Dimension(210, 38));
        dayPicker.setPreferredSize(new java.awt.Dimension(210, 38));
        dayPicker.setVisible(false);
        endDayHolder.setVisible(false);
        rangeStartHolder.setVisible(false);
        rangeEndHolder.setVisible(false);
        JPanel dateMode = new JPanel(new BorderLayout(10, 0));
        dateMode.setOpaque(false);
        dateMode.add(UiKit.muted("Fecha"), BorderLayout.WEST);
        dateMode.add(dateModeCombo, BorderLayout.CENTER);
        dateControls.add(dateMode);
        dateControls.add(dayPicker);
        dateControls.add(datePeriodCombo);
        dateControls.add(endDayHolder);
        dateControls.add(rangeStartHolder);
        dateControls.add(rangeEndHolder);
        dateControls.add(clearFiltersButton);
        dateControls.add(exportButton);

        JPanel filterControls = new JPanel(new BorderLayout(0, 8));
        filterControls.setOpaque(false);
        filterControls.add(controls, BorderLayout.NORTH);
        filterControls.add(dateControls, BorderLayout.CENTER);
        filterControls.add(rangeValidation, BorderLayout.SOUTH);

        filters.add(searchRow, BorderLayout.NORTH);
        filters.add(filterControls, BorderLayout.CENTER);
        return filters;
    }

    private static JPanel labeledControl(String label, java.awt.Component control) {
        JPanel holder = new JPanel(new BorderLayout(8, 0));
        holder.setOpaque(false);
        holder.add(UiKit.muted(label), BorderLayout.WEST);
        holder.add(control, BorderLayout.CENTER);
        return holder;
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
        if (filterPending || loading) return;
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
            if (isRangeMode(dateMode)) {
                FilterSelection selection = selection();
                if (selection.start() != null && selection.end() != null) {
                    descriptions.add("Fecha (" + dateMode.toLowerCase(Locale.ROOT) + "): "
                            + UiKit.date(selection.start().toString()) + " – "
                            + UiKit.date(selection.end().toString()));
                }
            } else {
                DateFilterOption period = selectedDatePeriod(dateMode);
                if (period != null) {
                    descriptions.add("Fecha (" + dateMode.toLowerCase(Locale.ROOT)
                            + "): " + period.label());
                }
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
                    exportButton.setEnabled(!filterPending && !loading && !filterResult.isEmpty());
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
        int generation = ++loadGeneration;
        loading = true;
        invalidateFilter("Cargando…");
        showMoreButton.setVisible(false);
        showAllButton.setVisible(false);
        exportButton.setEnabled(false);
        visibleLimit = PAGE_SIZE;

        new SwingWorker<ArchiveData, Void>() {
            @Override
            protected ArchiveData doInBackground() {
                return buildArchive(eventRepository.findTimeline());
            }

            @Override
            protected void done() {
                if (generation != loadGeneration) return;
                try {
                    archiveData = get();
                    lastSelection = null;
                    loading = false;
                    updateFilterOptions();
                    visibleLimit = hasActiveFilters()
                            ? FILTERED_PAGE_SIZE
                            : PAGE_SIZE;
                    applyFilters();
                } catch (InterruptedException | ExecutionException exception) {
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    loading = false;
                    archiveData = ArchiveData.empty();
                    filterResult = List.of();
                    lastSelection = null;
                    setFilterPending(false);
                    statusLabel.setText("No se han podido cargar los registros");
                    timelineView.setItems(List.of(), onOpenEvent, 0);
                    renderedItems = null;
                    exportButton.setEnabled(false);
                }
            }
        }.execute();
    }

    private void updateFilterOptions() {
        String selectedSpecies = (String) speciesCombo.getSelectedItem();
        String selectedPlace = (String) placeCombo.getSelectedItem();
        List<String> species = archiveData.species();
        List<String> places = archiveData.places();

        updatingFilterOptions = true;
        try {
            speciesCombo.setModel(new DefaultComboBoxModel<>(withAll(ALL_SPECIES, species)));
            if (selectedSpecies != null && species.contains(selectedSpecies)) {
                speciesCombo.setSelectedItem(selectedSpecies);
            }

            placeCombo.setModel(new DefaultComboBoxModel<>(withAll(ALL_PLACES, places)));
            if (selectedPlace != null && places.contains(selectedPlace)) {
                placeCombo.setSelectedItem(selectedPlace);
            }

            dayPicker.setAvailableDates(archiveData.days());
            endDayPicker.setAvailableDates(archiveData.days());
            if (!rangesInitialized) resetRanges();
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
        boolean dayRange = DATE_RANGE_DAYS.equals(mode);
        boolean periodRange = DATE_RANGE_MONTHS.equals(mode) || DATE_RANGE_YEARS.equals(mode);
        rangeStartHolder.setVisible(periodRange);
        rangeEndHolder.setVisible(periodRange);
        endDayHolder.setVisible(dayRange);
        rangeStart.setYearOnly(DATE_RANGE_YEARS.equals(mode));
        rangeEnd.setYearOnly(DATE_RANGE_YEARS.equals(mode));

        if (DATE_BY_DAY.equals(mode) || dayRange) {
            datePeriodCombo.setVisible(false);
            dayPicker.setVisible(true);
            dayPicker.setEnabled(true);
            if (dayPicker.getSelectedDate() == null) {
                dayPicker.setSelectedDate(dayRange ? firstAvailableDate() : lastAvailableDate());
            }
            if (dayRange && endDayPicker.getSelectedDate() == null) endDayPicker.setSelectedDate(lastAvailableDate());
            return;
        }

        dayPicker.setVisible(false);
        datePeriodCombo.setVisible(!periodRange);
        if (periodRange) return;
        if (mode == null || ALL_DATES.equals(mode)) {
            datePeriodCombo.setModel(new DefaultComboBoxModel<>(new DateFilterOption[]{
                    new DateFilterOption("", "Todo el archivo")
            }));
            datePeriodCombo.setEnabled(false);
            return;
        }

        List<DateFilterOption> options = DATE_BY_MONTH.equals(mode) ? archiveData.months() : archiveData.years();
        datePeriodCombo.setModel(new DefaultComboBoxModel<>(options.toArray(DateFilterOption[]::new)));
        datePeriodCombo.setEnabled(!options.isEmpty());
        if (selected != null) options.stream().filter(option -> selected.key().equals(option.key()))
                .findFirst().ifPresent(datePeriodCombo::setSelectedItem);
    }

    private static String[] withAll(String label, List<String> options) {
        String[] values = new String[options.size() + 1];
        values[0] = label;
        for (int index = 0; index < options.size(); index++) values[index + 1] = options.get(index);
        return values;
    }

    private LocalDate firstAvailableDate() {
        return archiveData.first() == null ? LocalDate.now() : archiveData.first();
    }

    private LocalDate lastAvailableDate() {
        return archiveData.last() == null ? LocalDate.now() : archiveData.last();
    }

    private void resetRanges() {
        rangeStart.setPeriod(YearMonth.from(firstAvailableDate()));
        rangeEnd.setPeriod(YearMonth.from(lastAvailableDate()));
        rangesInitialized = true;
    }

    private void filterChanged() {
        if (updatingFilterOptions) {
            return;
        }
        visibleLimit = hasActiveFilters() ? FILTERED_PAGE_SIZE : PAGE_SIZE;
        invalidateFilter(loading ? "Cargando…" : "Filtrando…");
        if (!loading) filterDelay.restart();
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
            endDayPicker.setSelectedDate(null);
            resetRanges();
            if (loading) rangesInitialized = false;
            updateDatePeriodOptions();
        } finally {
            updatingFilterOptions = false;
        }
        visibleLimit = PAGE_SIZE;
        applyFilters();
    }

    private void invalidateFilter(String message) {
        filterDelay.stop();
        filterGeneration++;
        if (filterWorker != null) filterWorker.cancel(true);
        setFilterPending(true);
        statusLabel.setText(message);
        exportButton.setEnabled(false);
        showMoreButton.setVisible(false);
        showAllButton.setVisible(false);
    }

    private void setFilterPending(boolean pending) {
        filterPending = pending;
        putClientProperty("RingLog.filterPending", pending);
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
        if (loading) return;
        invalidateFilter("Filtrando…");
        int generation = filterGeneration;
        FilterSelection selected = selection();
        rangeValidation.setText(selected.error() == null ? "" : selected.error());
        rangeValidation.setVisible(selected.error() != null);
        if (selected.error() != null) {
            filterResult = List.of();
            lastSelection = null;
            renderFiltered(filterResult, selected.error());
            return;
        }
        if (selected.equals(lastSelection)) {
            renderFiltered(filterResult, null);
            return;
        }
        ArchiveData snapshot = archiveData;
        filterWorker = new SwingWorker<FilteredArchive, Void>() {
            @Override protected FilteredArchive doInBackground() {
                List<BirdEventTimelineItem> matches = filterArchive(snapshot, selected);
                return new FilteredArchive(matches, exactRingSearch(matches, selected.query()));
            }

            @Override protected void done() {
                if (isCancelled() || generation != filterGeneration || loading) return;
                try {
                    FilteredArchive result = get();
                    filterResult = result.items();
                    filterSummary = result.summary();
                    lastSelection = selected;
                    renderFiltered(filterResult, null);
                } catch (CancellationException ignored) {
                    // A newer selection owns the result and export controls.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    filterResult = List.of();
                    lastSelection = null;
                    renderFiltered(filterResult, "Se ha interrumpido el filtrado.");
                } catch (ExecutionException exception) {
                    filterResult = List.of();
                    lastSelection = null;
                    renderFiltered(filterResult, "No se han podido filtrar los registros.");
                }
            }
        };
        filterWorker.execute();
    }

    private void renderFiltered(List<BirdEventTimelineItem> filtered, String error) {
        List<BirdEventTimelineItem> visible = filtered.subList(0, Math.min(filtered.size(), visibleLimit));
        String emptyMessage = error != null ? error : filtered.isEmpty() && hasActiveFilters()
                ? "No hay registros con estos filtros" : null;
        boolean sameCards = visible.equals(renderedItems)
                && java.util.Objects.equals(emptyMessage, renderedEmptyMessage);

        if (!sameCards && emptyMessage != null) {
            timelineView.setItems(
                    filtered,
                    onOpenEvent,
                    visibleLimit,
                    emptyMessage,
                    error != null ? "Revisa los límites del rango para continuar."
                            : "Prueba con otra fecha o ajusta los filtros para ampliar la búsqueda."
            );
        } else if (!sameCards) {
            timelineView.setItems(filtered, onOpenEvent, visibleLimit);
        }
        renderedItems = List.copyOf(visible);
        renderedEmptyMessage = emptyMessage;
        int shown = Math.min(filtered.size(), visibleLimit);
        ExactRingSearch exactRing = error == null ? filterSummary : null;
        statusLabel.setText(error != null ? "Rango de fechas no válido" : shown == filtered.size() && exactRing != null
                ? exactRing.description()
                : shown == filtered.size()
                        ? filtered.size() + " "
                                + (filtered.size() == 1 ? "entrada" : "entradas")
                        : "Mostrando " + shown + " de " + filtered.size());

        int remaining = Math.max(0, filtered.size() - visibleLimit);
        showMoreButton.setVisible(remaining > 0);
        showAllButton.setVisible(remaining > PAGE_SIZE);
        exportButton.setEnabled(!exportInProgress && !filtered.isEmpty());
        setFilterPending(false);
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
        return filterResult;
    }

    private FilterSelection selection() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String species = (String) speciesCombo.getSelectedItem();
        String place = (String) placeCombo.getSelectedItem();
        String dateMode = (String) dateModeCombo.getSelectedItem();
        Object type = typeCombo.getSelectedItem();
        LocalDate start = null;
        LocalDate end = null;
        boolean dateActive = dateMode != null && !ALL_DATES.equals(dateMode);
        if (DATE_RANGE_DAYS.equals(dateMode)) {
            start = dayPicker.getSelectedDate();
            end = endDayPicker.getSelectedDate();
        } else if (DATE_RANGE_MONTHS.equals(dateMode)) {
            start = rangeStart.getPeriod().atDay(1);
            end = rangeEnd.getPeriod().atEndOfMonth();
        } else if (DATE_RANGE_YEARS.equals(dateMode)) {
            start = LocalDate.of(rangeStart.getPeriod().getYear(), 1, 1);
            end = LocalDate.of(rangeEnd.getPeriod().getYear(), 12, 31);
        } else if (dateActive) {
            DateFilterOption period = selectedDatePeriod(dateMode);
            if (DATE_BY_DAY.equals(dateMode)) {
                start = dayPicker.getSelectedDate();
                end = start;
            } else if (period != null && DATE_BY_MONTH.equals(dateMode)) {
                YearMonth month = YearMonth.parse(period.key());
                start = month.atDay(1);
                end = month.atEndOfMonth();
            } else if (period != null && DATE_BY_YEAR.equals(dateMode)) {
                int year = Integer.parseInt(period.key());
                start = LocalDate.of(year, 1, 1);
                end = LocalDate.of(year, 12, 31);
            }
        }
        String error = dateActive && (start == null || end == null)
                ? "Elige el inicio y el final del rango de fechas."
                : start != null && end.isBefore(start)
                        ? "El inicio del rango debe ser anterior o igual al final." : null;
        return new FilterSelection(query,
                species == null || ALL_SPECIES.equals(species) ? null : species,
                place == null || ALL_PLACES.equals(place) ? null : place,
                type instanceof EventType eventType ? eventType : null,
                start, end, dateActive,
                dateSortOrderCombo.getSelectedItem() == DateSortOrder.OLDEST_FIRST, error);
    }

    private static Comparator<BirdEventTimelineItem> dateComparator(boolean oldestFirst) {
        Comparator<String> direction = oldestFirst
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<String> values = Comparator.nullsLast(direction);
        return Comparator.comparing(BirdEventTimelineItem::eventDate, values)
                .thenComparing(BirdEventTimelineItem::eventTime, values);
    }

    private static String searchableText(BirdEventTimelineItem item) {
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
                .toLowerCase(Locale.ROOT);
    }

    private static List<BirdEventTimelineItem> filterArchive(ArchiveData data, FilterSelection selected) {
        List<BirdEventTimelineItem> matches = new ArrayList<>();
        List<BirdEventTimelineItem> exact = new ArrayList<>();
        for (IndexedEvent entry : selected.oldestFirst() ? data.oldest() : data.newest()) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            BirdEventTimelineItem item = entry.item();
            if (selected.species() != null && !selected.species().equals(item.species())
                    || selected.place() != null && !selected.place().equals(item.place())
                    || selected.type() != null && selected.type() != item.eventType()) continue;
            if (selected.dateActive() && (entry.date() == null
                    || entry.date().isBefore(selected.start()) || entry.date().isAfter(selected.end()))) continue;
            if (!selected.query().isBlank() && !entry.searchText().contains(selected.query())) continue;
            if (!selected.query().isBlank() && exactRing(item, selected.query())) exact.add(item);
            else matches.add(item);
        }
        if (!exact.isEmpty()) {
            exact.addAll(matches);
            return List.copyOf(exact);
        }
        return List.copyOf(matches);
    }

    private static ArchiveData buildArchive(List<BirdEventTimelineItem> items) {
        List<IndexedEvent> entries = new ArrayList<>(items.size());
        Set<LocalDate> days = new HashSet<>();
        Map<String, DateFilterOption> months = new TreeMap<>(Comparator.reverseOrder());
        Map<String, DateFilterOption> years = new TreeMap<>(Comparator.reverseOrder());
        LocalDate first = null;
        LocalDate last = null;
        for (BirdEventTimelineItem item : items) {
            LocalDate date = null;
            try {
                if (item.eventDate() != null) date = LocalDate.parse(item.eventDate());
                if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) date = null;
            } catch (DateTimeParseException ignored) {
                // Malformed imported dates remain searchable without becoming range boundaries.
            }
            entries.add(new IndexedEvent(item, date, searchableText(item)));
            if (date != null) {
                days.add(date);
                if (first == null || date.isBefore(first)) first = date;
                if (last == null || date.isAfter(last)) last = date;
                String month = date.toString().substring(0, 7);
                if (!months.containsKey(month)) months.put(month,
                        new DateFilterOption(month, date.format(MONTH_FORMATTER).replace(".", "")));
                String year = date.toString().substring(0, 4);
                years.putIfAbsent(year, new DateFilterOption(year, year));
            }
        }
        List<IndexedEvent> newest = entries.stream()
                .sorted(Comparator.comparing(IndexedEvent::item, dateComparator(false))).toList();
        List<IndexedEvent> oldest = entries.stream()
                .sorted(Comparator.comparing(IndexedEvent::item, dateComparator(true))).toList();
        List<String> species = items.stream().map(BirdEventTimelineItem::species)
                .filter(value -> value != null && !value.isBlank()).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> places = items.stream().map(BirdEventTimelineItem::place)
                .filter(value -> value != null && !value.isBlank()).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new ArchiveData(newest, oldest, species, places, Set.copyOf(days),
                List.copyOf(months.values()), List.copyOf(years.values()), first, last);
    }

    private record IndexedEvent(BirdEventTimelineItem item, LocalDate date, String searchText) { }
    private record FilteredArchive(List<BirdEventTimelineItem> items, ExactRingSearch summary) { }
    private record FilterSelection(String query, String species, String place, EventType type,
                                   LocalDate start, LocalDate end, boolean dateActive,
                                   boolean oldestFirst, String error) { }
    private record ArchiveData(List<IndexedEvent> newest, List<IndexedEvent> oldest,
                               List<String> species, List<String> places, Set<LocalDate> days,
                               List<DateFilterOption> months, List<DateFilterOption> years,
                               LocalDate first, LocalDate last) {
        private static ArchiveData empty() {
            return new ArchiveData(List.of(), List.of(), List.of(), List.of(), Set.of(),
                    List.of(), List.of(), null, null);
        }
    }

    private static boolean isRangeMode(String mode) {
        return DATE_RANGE_DAYS.equals(mode) || DATE_RANGE_MONTHS.equals(mode) || DATE_RANGE_YEARS.equals(mode);
    }

    private static ExactRingSearch exactRingSearch(List<BirdEventTimelineItem> matches, String query) {
        if (query.isBlank()) {
            return null;
        }
        List<BirdEventTimelineItem> exact = matches.stream()
                .filter(item -> exactRing(item, query))
                .toList();
        if (exact.isEmpty()) {
            return null;
        }
        long noteMentions = matches.stream()
                .filter(item -> !exactRing(item, query))
                .filter(item -> containsText(item.observations(), query))
                .count();
        int additional = matches.size() - exact.size() - (int) noteMentions;
        return new ExactRingSearch(
                exact.get(0).ringNumber(),
                exact.size(),
                noteMentions,
                additional
        );
    }

    private static boolean exactRing(BirdEventTimelineItem item, String query) {
        return item.ringNumber() != null
                && item.ringNumber().trim().equalsIgnoreCase(query);
    }

    private static boolean containsText(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private record ExactRingSearch(
            String ringNumber,
            int eventCount,
            long noteMentions,
            int additionalMatches
    ) {
        private String description() {
            StringBuilder text = new StringBuilder()
                    .append(eventCount)
                    .append(eventCount == 1 ? " registro de " : " registros de ")
                    .append(ringNumber);
            if (noteMentions > 0) {
                text.append(" · ")
                        .append(noteMentions)
                        .append(noteMentions == 1
                                ? " mención en notas"
                                : " menciones en notas");
            }
            if (additionalMatches > 0) {
                text.append(" · ")
                        .append(additionalMatches)
                        .append(additionalMatches == 1
                                ? " coincidencia adicional"
                                : " coincidencias adicionales");
            }
            return text.toString();
        }
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

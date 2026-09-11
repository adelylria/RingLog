package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportSelection;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.Sidebar;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.CapturePanel;

public final class BirdEventUiTest {

    private BirdEventUiTest() {
    }

    public static void sidebarUsesNaturalEventLanguage() {
        Sidebar sidebar = new Sidebar(ignored -> { });
        List<String> buttons = descendants(sidebar, JButton.class).stream()
                .map(JButton::getText)
                .toList();

        require(buttons.contains("Nuevo registro"),
                "The primary action should use natural event language");
        require(buttons.contains("Registros"),
                "The archive should include every event type");
    }

    public static void diaryCardMakesTheEventTypeVisible() {
        BirdEventCard card = new BirdEventCard(
                new BirdEventTimelineItem(
                        1L,
                        2L,
                        "V25943",
                        EventType.CONTROL,
                        "2025-12-20",
                        "17:25:00",
                        "TURDUS PHILOMELOS",
                        "ELS RAFALS",
                        "Control de prueba",
                        "GOOD",
                        null
                ),
                ignored -> { }
        );
        List<String> labels = descendants(card, JLabel.class).stream()
                .map(JLabel::getText)
                .toList();

        require(labels.contains("Control"),
                "Each diary card should identify its event type");
    }

    public static void diaryCardUsesNaturalEmptyCopy() {
        BirdEventCard card = new BirdEventCard(
                new BirdEventTimelineItem(
                        1L,
                        2L,
                        "V25943",
                        EventType.RINGING,
                        "2025-12-20",
                        "17:25:00",
                        "TURDUS PHILOMELOS",
                        "ELS RAFALS",
                        null,
                        null,
                        null
                ),
                ignored -> { }
        );
        List<String> labels = descendants(card, JLabel.class).stream()
                .map(JLabel::getText)
                .toList();

        require(labels.contains("Sin notas de campo"),
                "Missing observations should read naturally in the diary");
        require(!labels.contains("—"),
                "Missing conditions should not create an unexplained badge");
    }

    public static void eventFormUsesSchemaFieldsInsteadOfEventCoordinates() {
        CapturePanel panel = new CapturePanel(
                new BirdEventRepository("unused.db"),
                new CatalogRepository("unused.db"),
                ignored -> { },
                () -> { }
        );
        List<String> labels = descendants(panel, JLabel.class).stream()
                .map(JLabel::getText)
                .toList();

        require(labels.contains("Código de edad EURING"),
                "The form should use the schema's age EURING name");
        require(labels.contains("Estado") && !labels.contains("Estado oficial (código)"),
                "The bird-state selector should use the concise Estado label");
        require(!labels.contains("Latitud") && !labels.contains("Longitud"),
                "Coordinates belong to places, not events");
        require(descendants(panel, JComboBox.class).stream().anyMatch(combo ->
                        containsItem(combo, EventType.RINGING)),
                "The form should offer all bird event types");
    }

    public static void emptyDiaryOffersANewRecord() {
        EmptyStatePanel empty = new EmptyStatePanel(
                "Sin registros",
                "Todavía no hay entradas",
                () -> { }
        );
        List<String> buttons = descendants(empty, JButton.class).stream()
                .map(JButton::getText)
                .toList();

        require(buttons.contains("Crear un registro"),
                "Empty diary actions should not use obsolete capture wording");
    }

    public static void longDiaryCanBeRevealedProgressively() {
        CaptureListPanel panel = new CaptureListPanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );
        List<String> buttons = descendants(panel, JButton.class).stream()
                .map(JButton::getText)
                .toList();

        require(buttons.contains("Mostrar más"),
                "A long diary should not build every card before becoming usable");
    }

    public static void activeTypeFilterShowsMoreThanTheDefaultPage()
            throws Exception {
        CaptureListPanel panel = populatedDiary();
        JComboBox<?> typeFilter = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> containsItem(combo, EventType.CONTROL))
                .findFirst()
                .orElseThrow());

        SwingUtilities.invokeAndWait(
                () -> typeFilter.setSelectedItem(EventType.CONTROL)
        );

        waitForCardCount(panel, 75);
    }

    public static void placeAndDateFiltersUseTheWholeLoadedDiary()
            throws Exception {
        CaptureListPanel panel = populatedDiary();
        JComboBox<?> placeFilter = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> containsItem(combo, "CUIXAC"))
                .findFirst()
                .orElse(null));
        require(placeFilter != null,
                "The archive should offer an explicit place filter");

        SwingUtilities.invokeAndWait(
                () -> placeFilter.setSelectedItem("CUIXAC")
        );
        waitForCardCount(panel, 75);

        JTextField search = onEdt(() -> descendants(panel, JTextField.class)
                .get(0));
        SwingUtilities.invokeAndWait(() -> search.setText("2025-01-03"));
        waitForCardCount(panel, 75);
    }

    public static void archiveAlternatesDayMonthAndYearFilters()
            throws Exception {
        CaptureListPanel panel = populatedDiary();
        JComboBox<?> dateMode = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> "dateFilterMode".equals(combo.getName()))
                .findFirst()
                .orElse(null));
        JComboBox<?> datePeriod = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> "dateFilterPeriod".equals(combo.getName()))
                .findFirst()
                .orElse(null));
        CalendarDatePicker dayPicker = onEdt(() -> descendants(
                panel,
                CalendarDatePicker.class
        ).stream().findFirst().orElse(null));
        require(dateMode != null && datePeriod != null && dayPicker != null,
                "The archive should offer a day, month and year date filter");

        selectComboLabel(dateMode, "Día");
        SwingUtilities.invokeAndWait(() -> {
            require(dayPicker.isVisible(),
                    "Day mode should replace the long list with a calendar");
            require(!datePeriod.isVisible(),
                    "The old day dropdown should stay hidden");
            dayPicker.setSelectedDate(LocalDate.of(2026, 2, 9));
        });
        waitForCardCount(panel, 20);

        selectComboLabel(dateMode, "Mes");
        require(onEdt(() -> !dayPicker.isVisible() && datePeriod.isVisible()),
                "Month mode should keep its concise period dropdown");
        selectComboLabel(datePeriod, "feb 2026");
        waitForCardCount(panel, 30);

        selectComboLabel(dateMode, "Año");
        selectComboLabel(datePeriod, "2026");
        waitForCardCount(panel, 75);
    }

    public static void archiveCanOrderRecordsByDateInBothDirections()
            throws Exception {
        CaptureListPanel panel = populatedDiary();
        JComboBox<?> order = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> "dateSortOrder".equals(combo.getName()))
                .findFirst()
                .orElse(null));
        require(order != null
                        && containsItemLabel(order, "Más recientes primero")
                        && containsItemLabel(order, "Más antiguos primero"),
                "The archive should offer both date-order directions");

        waitForFirstVisibleRing(panel, "V25030");
        selectComboLabel(order, "Más antiguos primero");
        waitForFirstVisibleRing(panel, "V25075");
    }

    public static void exportUsesEveryFilteredRecordNotOnlyVisibleCards()
            throws Exception {
        List<BirdEventTimelineItem> events = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            boolean control = index < 135;
            events.add(new BirdEventTimelineItem(
                    index + 1L,
                    index + 1L,
                    "V" + (30000 + index),
                    control ? EventType.CONTROL : EventType.RINGING,
                    "2026-08-24",
                    "10:30:00",
                    "TURDUS PHILOMELOS",
                    "ELS RAFALS",
                    null,
                    null,
                    null
            ));
        }
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override
            public List<BirdEventTimelineItem> findTimeline() {
                return events;
            }
        };
        AtomicReference<RecordReportFormat> exportedFormat = new AtomicReference<>();
        AtomicReference<RecordReportSelection> exportedSelection = new AtomicReference<>();
        CaptureListPanel panel = onEdt(() -> new CaptureListPanel(
                repository,
                ignored -> { },
                () -> { },
                (format, selection) -> {
                    exportedFormat.set(format);
                    exportedSelection.set(selection);
                }
        ));
        SwingUtilities.invokeAndWait(panel::refresh);
        waitForCardCount(panel, 30);

        JComboBox<?> typeFilter = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> containsItem(combo, EventType.CONTROL))
                .findFirst()
                .orElseThrow());
        SwingUtilities.invokeAndWait(
                () -> typeFilter.setSelectedItem(EventType.CONTROL)
        );
        waitForCardCount(panel, 100);

        JButton export = onEdt(() -> descendants(panel, JButton.class).stream()
                .filter(button -> "exportRecords".equals(button.getName()))
                .findFirst()
                .orElse(null));
        require(export != null, "The archive should offer one clear export action");
        JPopupMenu menu = onEdt(export::getComponentPopupMenu);
        require(menu != null, "The export action should offer both file formats");
        JMenuItem excel = onEdt(() -> java.util.Arrays.stream(menu.getComponents())
                .filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast)
                .filter(item -> "Excel (.xlsx)".equals(item.getText()))
                .findFirst()
                .orElse(null));
        require(excel != null, "The export menu should include Excel");
        SwingUtilities.invokeAndWait(excel::doClick);

        require(exportedFormat.get() == RecordReportFormat.EXCEL,
                "The selected export format should reach the export workflow");
        require(exportedSelection.get() != null
                        && exportedSelection.get().eventIds().size() == 135,
                "Export must include all matches, not only the first 100 cards");
        require(exportedSelection.get().eventIds().get(0) == 1L
                        && exportedSelection.get().eventIds().get(
                        exportedSelection.get().eventIds().size() - 1) == 135L,
                "The filtered selection should preserve the diary order");
        require(exportedSelection.get().activeFilters().contains("Tipo: Control"),
                "The report should explain the active filter");
    }

    public static void calendarExplainsDaysWithoutMatchingEntries()
            throws Exception {
        CaptureListPanel panel = populatedDiary();
        JComboBox<?> dateMode = onEdt(() -> descendants(panel, JComboBox.class)
                .stream()
                .filter(combo -> "dateFilterMode".equals(combo.getName()))
                .findFirst()
                .orElseThrow());
        CalendarDatePicker dayPicker = onEdt(() -> descendants(
                panel,
                CalendarDatePicker.class
        ).stream().findFirst().orElseThrow());

        selectComboLabel(dateMode, "Día");
        SwingUtilities.invokeAndWait(() -> dayPicker.setSelectedDate(
                LocalDate.of(2030, 1, 1)
        ));
        waitForCardCount(panel, 0);

        List<String> labels = onEdt(() -> descendants(panel, JLabel.class)
                .stream()
                .map(JLabel::getText)
                .toList());
        require(labels.contains("No hay registros con estos filtros"),
                "An empty calendar day should be explained as a filter result");
    }

    public static void detailShowsEveryFieldAndOffersIntegratedEditing() throws Exception {
        String longNotes = "1 de 5. Capturas: Cinco zorzales comunes anillados. "
                + "(V33907, V33908, V33909, V33910, V33911) Aranzadi-Sansebastián. "
                + "Así como los capturados durante la jornada anterior.\n"
                + "La segunda línea también debe conservarse completa y legible.";
        BirdEventDetail detail = new BirdEventDetail(
                1L,
                2L,
                "V25943",
                "TURDUS PHILOMELOS",
                EventType.RINGING,
                "2026-02-09",
                "10:30:00",
                "ELS RAFALS",
                "POLLENSA",
                null,
                null,
                "U",
                "4",
                2,
                2,
                null,
                "B0",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                71.0,
                null,
                null,
                null,
                null,
                39.8502,
                2.9850,
                longNotes,
                false,
                List.of()
        );
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override
            public Optional<BirdEventDetail> findDetail(long eventId) {
                return Optional.of(detail);
            }
        };
        AtomicReference<BirdEventDetail> editRequest = new AtomicReference<>();
        CaptureDetailPanel panel = onEdt(() -> new CaptureDetailPanel(
                repository,
                () -> { },
                editRequest::set
        ));
        SwingUtilities.invokeAndWait(() -> panel.setEventId(1L));
        waitForLabel(panel, "Anilla V25943");

        List<JLabel> labels = onEdt(() -> descendants(panel, JLabel.class));
        require(labels.stream().noneMatch(label ->
                        "Anillamiento · —".equals(label.getText())),
                "The detail identity should not append an unknown status");
        require(labels.stream().noneMatch(label ->
                        "PillLabel".equals(label.getClass().getSimpleName())
                                && "—".equals(label.getText())),
                "The detail identity should not render an empty condition badge");
        List<String> texts = labels.stream().map(JLabel::getText).toList();
        require(texts.contains("Código de estado") && texts.contains("Reproducción"),
                "The complete bird card must keep fields whose values are missing");
        require(texts.contains("Ala") && texts.contains("Tarso")
                        && !texts.contains("Torso") && texts.contains("Nubes"),
                "Measurements and weather must remain visible when empty");
        require(texts.contains("Código de estado") && texts.contains("B0"),
                "The bird card should make the official status code explicit");
        JTextArea statusDescription = onEdt(() -> descendants(panel, JTextArea.class).stream()
                .filter(area -> "birdStatusDescription".equals(area.getName()))
                .findFirst()
                .orElse(null));
        require(statusDescription != null
                        && "Aparentemente en buenas condiciones".equals(
                                statusDescription.getText())
                        && statusDescription.getLineWrap(),
                "The complete status meaning should remain readable without being cut off");
        require(texts.contains("Notas de campo")
                        && texts.contains("Archivo y revisión")
                        && texts.contains("Sin fotografías"),
                "Notes, review state and photo state must always be explicit");
        require(descendants(panel, JButton.class).stream().anyMatch(button ->
                        "© OpenStreetMap contributors".equals(button.getText())),
                "A georeferenced record should keep attribution inside its compact map");
        require(!texts.contains("Ubicación en el mapa")
                        && !texts.contains("Vista del lugar guardado en este registro."),
                "The map should not repeat explanatory copy that is already understood");
        JPanel loadedMap = onEdt(() -> descendants(panel, JPanel.class).stream()
                .filter(candidate -> "loadedLocationMap".equals(candidate.getName()))
                .findFirst()
                .orElse(null));
        require(loadedMap != null,
                "The georeferenced detail should show its map without an extra step");
        JPanel mapCanvas = onEdt(() -> descendants(panel, JPanel.class).stream()
                .filter(candidate -> "openStreetMapCanvas".equals(candidate.getName()))
                .findFirst()
                .orElse(null));
        require(mapCanvas != null
                        && mapCanvas.getMouseMotionListeners().length > 0
                        && mapCanvas.getMouseWheelListeners().length > 0,
                "The embedded map should support dragging and wheel zoom like a web map");
        require(descendants(panel, JButton.class).stream().noneMatch(button ->
                        "−".equals(button.getText())
                                || "+".equals(button.getText())
                                || "Abrir en OpenStreetMap".equals(button.getText())),
                "The map should not need separate zoom or external-map buttons");
        JTextArea notes = onEdt(() -> descendants(panel, JTextArea.class).stream()
                .filter(area -> "fieldNotesText".equals(area.getName()))
                .findFirst()
                .orElse(null));
        require(notes != null,
                "Long field notes should use a wrapping text component");
        require(longNotes.equals(notes.getText()),
                "The detail must preserve the complete field notes");
        require(notes.getLineWrap() && notes.getWrapStyleWord() && !notes.isEditable(),
                "Field notes should wrap by words without becoming editable in the detail view");
        int wideHeight = onEdt(() -> {
            notes.setSize(720, Short.MAX_VALUE);
            return notes.getPreferredSize().height;
        });
        int narrowHeight = onEdt(() -> {
            notes.setSize(260, Short.MAX_VALUE);
            return notes.getPreferredSize().height;
        });
        require(narrowHeight > wideHeight,
                "The notes block should grow vertically instead of clipping long text");

        JButton edit = onEdt(() -> descendants(panel, JButton.class).stream()
                .filter(button -> "Editar entrada".equals(button.getText()))
                .findFirst()
                .orElseThrow());
        SwingUtilities.invokeAndWait(edit::doClick);
        require(editRequest.get() == detail,
                "Editing must stay inside RingLog and reuse the loaded entry");
    }

    private static CaptureListPanel populatedDiary() throws Exception {
        List<BirdEventTimelineItem> events = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            boolean control = index < 75;
            String eventDate = index < 20
                    ? "2026-02-09"
                    : index < 30
                            ? "2026-02-10"
                            : index < 75 ? "2026-03-01" : "2025-01-03";
            events.add(new BirdEventTimelineItem(
                    index + 1L,
                    index + 1L,
                    "V" + (25000 + index),
                    control ? EventType.CONTROL : EventType.RINGING,
                    eventDate,
                    "10:30:00",
                    "TURDUS PHILOMELOS",
                    control ? "ELS RAFALS" : "CUIXAC",
                    null,
                    null,
                    null
            ));
        }

        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override
            public List<BirdEventTimelineItem> findTimeline() {
                return events;
            }
        };
        CaptureListPanel panel = onEdt(() -> new CaptureListPanel(
                repository,
                ignored -> { },
                () -> { }
        ));
        SwingUtilities.invokeAndWait(panel::refresh);
        waitForCardCount(panel, 30);
        return panel;
    }

    private static void selectComboLabel(JComboBox<?> combo, String label)
            throws Exception {
        Object item = onEdt(() -> {
            for (int index = 0; index < combo.getItemCount(); index++) {
                Object candidate = combo.getItemAt(index);
                if (label.equals(candidate.toString())) {
                    return candidate;
                }
            }
            return null;
        });
        require(item != null, "Missing date filter option: " + label);
        SwingUtilities.invokeAndWait(() -> combo.setSelectedItem(item));
    }

    private static void waitForCardCount(
            CaptureListPanel panel,
            int expected
    ) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        int actual;
        do {
            actual = onEdt(() -> descendants(panel, BirdEventCard.class).size());
            if (actual == expected) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "Expected " + expected + " visible diary cards, got " + actual
        );
    }

    private static void waitForFirstVisibleRing(
            CaptureListPanel panel,
            String expectedRing
    ) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        String actual;
        do {
            actual = onEdt(() -> descendants(panel, BirdEventCard.class).stream()
                    .findFirst()
                    .map(card -> card.getAccessibleContext().getAccessibleName())
                    .orElse(""));
            if (actual.endsWith(" · " + expectedRing)) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "Expected first visible ring " + expectedRing + ", got " + actual
        );
    }

    private static void waitForLabel(Component component, String expected)
            throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            boolean found = onEdt(() -> descendants(component, JLabel.class)
                    .stream()
                    .anyMatch(label -> expected.equals(label.getText())));
            if (found) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Missing label: " + expected);
    }

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static boolean containsItem(JComboBox<?> combo, Object expected) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (expected.equals(combo.getItemAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsItemLabel(JComboBox<?> combo, String expected) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            Object item = combo.getItemAt(index);
            if (item != null && expected.equals(item.toString())) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Component> List<T> descendants(
            Component component,
            Class<T> type
    ) {
        List<T> matches = new ArrayList<>();
        if (type.isInstance(component)) {
            matches.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                matches.addAll(descendants(child, type));
            }
        }
        return matches;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        sidebarUsesNaturalEventLanguage();
        diaryCardMakesTheEventTypeVisible();
        diaryCardUsesNaturalEmptyCopy();
        eventFormUsesSchemaFieldsInsteadOfEventCoordinates();
        emptyDiaryOffersANewRecord();
        longDiaryCanBeRevealedProgressively();
        activeTypeFilterShowsMoreThanTheDefaultPage();
        placeAndDateFiltersUseTheWholeLoadedDiary();
        archiveAlternatesDayMonthAndYearFilters();
        archiveCanOrderRecordsByDateInBothDirections();
        exportUsesEveryFilteredRecordNotOnlyVisibleCards();
        calendarExplainsDaysWithoutMatchingEntries();
        detailShowsEveryFieldAndOffersIntegratedEditing();
        System.out.println("BirdEventUiTest: PASS");
    }
}

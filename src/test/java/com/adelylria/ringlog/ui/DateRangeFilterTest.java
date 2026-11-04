package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportSelection;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;

/** End-to-end filter controls over an in-memory diary; no windows, database or file export. */
public final class DateRangeFilterTest {

    private static final String FILTER_PENDING = "RingLog.filterPending";

    private DateRangeFilterTest() {
    }

    public static void monthRangesIncludeWholeMonthsAcrossYearsAndGaps() throws Exception {
        Fixture fixture = fixture(List.of(
                event(1, "2023-10-31"), event(2, "2023-11-01"), event(3, "2023-11-30"),
                event(4, "2024-01-01"), event(5, "2024-02-01"), event(6, "2024-02-29"),
                event(7, "2024-03-01"), event(8, "2022-12-31"), event(9, "2025-01-01")
        ));
        runOnEdt(() -> {
            selectMode(fixture.panel(), "Rango de meses");
            JComboBox<?> months = field(fixture.panel(), "dateRangeStartMonth", JComboBox.class);
            require(months.getItemCount() == 12 && "enero".equals(months.getItemAt(0))
                            && "diciembre".equals(months.getItemAt(11)),
                    "Month ranges must offer every Spanish month, including months without entries");
            SpinnerNumberModel years = (SpinnerNumberModel) field(
                    fixture.panel(), "dateRangeStartYear", JSpinner.class).getModel();
            require(Integer.valueOf(1).equals(years.getMinimum()) && Integer.valueOf(9999).equals(years.getMaximum()),
                    "Range years must allow the supported calendar limits 1 through 9999");
            setMonthRange(fixture.panel(), 2023, "noviembre", 2024, "febrero");
        });
        awaitResults(fixture, List.of(6L, 5L, 4L, 3L, 2L));
        runOnEdt(() -> require(validation(fixture.panel()).isBlank(), "A valid cross-year month range needs no error"));

        runOnEdt(() -> {
            setMonthRange(fixture.panel(), 2023, "diciembre", 2023, "diciembre");
            require(pending(fixture.panel()) && !field(fixture.panel(), "exportRecords", JButton.class).isEnabled(),
                    "Changing a range must immediately suspend export while its latest result is pending");
            JPopupMenu menu = field(fixture.panel(), "exportRecords", JButton.class).getComponentPopupMenu();
            field(menu, "exportEXCEL", JMenuItem.class).doClick();
            require(fixture.exported().get() == null,
                    "A menu action during debounce must not export the previous range's results");
        });
        awaitResults(fixture, List.of());
        runOnEdt(() -> require(validation(fixture.panel()).isBlank()
                        && !field(fixture.panel(), "exportRecords", JButton.class).isEnabled(),
                "An empty month is a valid empty result, with export unavailable"));

        runOnEdt(() -> setMonthRange(fixture.panel(), 2024, "febrero", 2024, "febrero"));
        awaitResults(fixture, List.of(6L, 5L));
        require(fixture.queries().get() == 1, "Changing month ranges must reuse the loaded diary");
    }

    public static void yearRangesIncludeBoundariesAndASingleYear() throws Exception {
        Fixture fixture = fixture(List.of(
                event(1, "2021-12-31"), event(2, "2022-01-01"), event(3, "2023-12-31"),
                event(4, "2024-01-01"), event(5, "2024-02-29"), event(6, "2024-12-31"),
                event(7, "2025-01-01")
        ));
        runOnEdt(() -> {
            selectMode(fixture.panel(), "Rango de años");
            require(!visibleWithin(fixture.panel(), field(fixture.panel(), "dateRangeStartMonth", JComboBox.class))
                            && !visibleWithin(fixture.panel(), field(fixture.panel(), "dateRangeEndMonth", JComboBox.class)),
                    "Year ranges must hide the month controls");
            setYearRange(fixture.panel(), 2022, 2024);
        });
        awaitResults(fixture, List.of(6L, 5L, 4L, 3L, 2L));
        RecordReportSelection range = exportExcel(fixture);
        require(range.activeFilters().stream().anyMatch(text -> text.contains("2022") && text.contains("2024")),
                "Export metadata must describe both year-range limits");

        runOnEdt(() -> setYearRange(fixture.panel(), 2024, 2024));
        awaitResults(fixture, List.of(6L, 5L, 4L));
        runOnEdt(() -> setYearRange(fixture.panel(), 2026, 2026));
        awaitResults(fixture, List.of());
        runOnEdt(() -> require(validation(fixture.panel()).isBlank(), "A year without entries is not an invalid range"));
        require(fixture.queries().get() == 1, "Year ranges and exports must not reload timeline data");
    }

    public static void invertedRangesStayInvalidWithoutAdjustingTheirLimits() throws Exception {
        Fixture fixture = fixture(List.of(event(1, "2023-12-01"), event(2, "2024-02-29")));
        runOnEdt(() -> {
            selectMode(fixture.panel(), "Rango de años");
            setYearRange(fixture.panel(), 2025, 2024);
        });
        awaitResults(fixture, List.of());
        runOnEdt(() -> {
            require(!validation(fixture.panel()).isBlank(), "An inverted year range must explain its error");
            require(year(fixture.panel(), "dateRangeStartYear") == 2025
                            && year(fixture.panel(), "dateRangeEndYear") == 2024,
                    "Inverted years must remain as entered rather than silently changing either limit");
            require(!field(fixture.panel(), "exportRecords", JButton.class).isEnabled(),
                    "An invalid range must not export a stale previous selection");
            selectMode(fixture.panel(), "Rango de meses");
            setMonthRange(fixture.panel(), 2024, "marzo", 2024, "febrero");
        });
        awaitResults(fixture, List.of());
        runOnEdt(() -> {
            require(!validation(fixture.panel()).isBlank(), "An inverted month range must explain its error");
            require("marzo".equals(field(fixture.panel(), "dateRangeStartMonth", JComboBox.class).getSelectedItem())
                            && "febrero".equals(field(fixture.panel(), "dateRangeEndMonth", JComboBox.class).getSelectedItem()),
                    "Month-range validation must not rewrite the requested limits");
            setMonthRange(fixture.panel(), 2023, "diciembre", 2024, "febrero");
            setMonthRange(fixture.panel(), 2024, "marzo", 2024, "febrero");
            setMonthRange(fixture.panel(), 2023, "diciembre", 2023, "diciembre");
        });
        awaitResults(fixture, List.of(1L));
        runOnEdt(() -> require(validation(fixture.panel()).isBlank(),
                "The latest valid range must replace an earlier invalid or pending result"));
        require(fixture.queries().get() == 1, "Rapid range edits must not issue database timeline queries");
    }

    public static void dayRangesIncludeLeapDayAndBothEndpoints() throws Exception {
        Fixture fixture = fixture(List.of(
                event(1, "2024-02-28"), event(2, "2024-02-29"),
                event(3, "2024-03-01"), event(4, "2024-03-02")
        ));
        runOnEdt(() -> {
            selectMode(fixture.panel(), "Rango de días");
            field(fixture.panel(), "dateRangeStartDay", CalendarDatePicker.class)
                    .setSelectedDate(LocalDate.of(2024, 2, 29));
            field(fixture.panel(), "dateRangeEndDay", CalendarDatePicker.class)
                    .setSelectedDate(LocalDate.of(2024, 3, 1));
        });
        awaitResults(fixture, List.of(3L, 2L));
        require(fixture.queries().get() == 1, "Day ranges must filter the same loaded diary");
    }

    public static void rangeComposesWithFiltersAndExportsAllMatchesBeforeClear() throws Exception {
        List<BirdEventTimelineItem> events = new ArrayList<>();
        for (int index = 1; index <= 135; index++) {
            events.add(event(index, index <= 70 ? "2023-11-01" : "2024-02-29",
                    EventType.CONTROL, "Mirlo común", "CUIXAC", "objetivo"));
        }
        events.add(event(201, "2024-02-15", EventType.CONTROL, "Petirrojo", "CUIXAC", "objetivo"));
        events.add(event(202, "2024-02-15", EventType.CONTROL, "Mirlo común", "ELS RAFALS", "objetivo"));
        events.add(event(203, "2024-02-15", EventType.RINGING, "Mirlo común", "CUIXAC", "objetivo"));
        events.add(event(204, "2024-02-15", EventType.CONTROL, "Mirlo común", "CUIXAC", "sin marca"));
        events.add(event(205, "2023-10-31", EventType.CONTROL, "Mirlo común", "CUIXAC", "objetivo"));
        events.add(event(206, "2024-03-01", EventType.CONTROL, "Mirlo común", "CUIXAC", "objetivo"));
        Fixture fixture = fixture(events);
        runOnEdt(() -> {
            selectMode(fixture.panel(), "Rango de meses");
            setMonthRange(fixture.panel(), 2023, "noviembre", 2024, "febrero");
            comboOffering(fixture.panel(), EventType.CONTROL).setSelectedItem(EventType.CONTROL);
            comboOffering(fixture.panel(), "Mirlo común").setSelectedItem("Mirlo común");
            comboOffering(fixture.panel(), "CUIXAC").setSelectedItem("CUIXAC");
            searchField(fixture.panel()).setText("objetivo");
        });
        List<Long> newestFirst = LongStream.iterate(135L, id -> id - 1L).limit(135).boxed().toList();
        awaitResults(fixture, newestFirst.subList(0, 100));
        runOnEdt(() -> selectLabel(field(fixture.panel(), "dateSortOrder", JComboBox.class), "Más antiguos primero"));
        List<Long> oldestFirst = LongStream.rangeClosed(1, 135).boxed().toList();
        awaitResults(fixture, oldestFirst.subList(0, 100));
        RecordReportSelection exported = exportExcel(fixture);
        require(exported.eventIds().equals(oldestFirst),
                "Range export must include all 135 matches in the requested order, beyond the first 100 cards");
        require(exported.activeFilters().stream().anyMatch(text ->
                        text.contains("2023") && text.contains("2024")
                                && describesMonth(text, "nov", "2023-11", "/11/2023")
                                && describesMonth(text, "feb", "2024-02", "/02/2024")),
                "Export metadata must describe both month-range limits");
        for (String expected : List.of("Mirlo común", "CUIXAC", "Control", "objetivo")) {
            require(exported.activeFilters().stream().anyMatch(text -> text.contains(expected)),
                    "Export must retain the composed filter description: " + expected);
        }

        runOnEdt(() -> buttonWithText(fixture.panel(), "Limpiar filtros").doClick());
        List<Long> allNewest = sortedIds(events, true);
        awaitResults(fixture, allNewest.subList(0, 30));
        runOnEdt(() -> {
            require("Cualquier fecha".equals(field(fixture.panel(), "dateFilterMode", JComboBox.class).getSelectedItem()),
                    "Clearing must reset the range mode");
            require(searchField(fixture.panel()).getText().isEmpty() && validation(fixture.panel()).isBlank(),
                    "Clearing must remove query text and range errors");
            require("Más recientes primero".equals(field(fixture.panel(), "dateSortOrder", JComboBox.class)
                            .getSelectedItem().toString()), "Clearing must restore the default date order");
        });
        RecordReportSelection all = exportExcel(fixture);
        require(all.eventIds().equals(allNewest) && all.activeFilters().isEmpty(),
                "Cleared export must contain the whole diary without stale range or composed-filter descriptions");
        require(fixture.queries().get() == 1,
                "Typing, range controls, sorting, clearing and exporting must not call findTimeline again");
        runOnEdt(fixture.panel()::refresh);
        await(() -> fixture.queries().get() == 2 && !pending(fixture.panel()), "Explicit refresh should query the diary again");
        require(fixture.queries().get() == 2, "Only an explicit refresh may reload timeline data");
    }

    private static boolean describesMonth(String text, String spanish, String iso, String formatted) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains(spanish) || lower.contains(iso) || lower.contains(formatted);
    }

    private static Fixture fixture(List<BirdEventTimelineItem> source) throws Exception {
        List<BirdEventTimelineItem> events = List.copyOf(source);
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<RecordReportSelection> exported = new AtomicReference<>();
        BirdEventRepository repository = new BirdEventRepository("unused.db") {
            @Override public List<BirdEventTimelineItem> findTimeline() {
                queries.incrementAndGet();
                return events;
            }
        };
        CaptureListPanel panel = onEdt(() -> new CaptureListPanel(repository, ignored -> { }, () -> { },
                (format, selection) -> {
                    require(format == RecordReportFormat.EXCEL, "The chosen Excel action must reach export");
                    exported.set(selection);
                }));
        Fixture fixture = new Fixture(panel, queries, exported);
        runOnEdt(panel::refresh);
        List<Long> initial = sortedIds(events, true);
        awaitResults(fixture, initial.subList(0, Math.min(30, initial.size())));
        require(queries.get() == 1, "Loading a diary should query its timeline once");
        return fixture;
    }

    private static BirdEventTimelineItem event(long id, String date) {
        return event(id, date, EventType.CONTROL, "Mirlo común", "CUIXAC", "Nota");
    }

    private static BirdEventTimelineItem event(long id, String date, EventType type,
            String species, String place, String observations) {
        String time = String.format(Locale.ROOT, "%02d:%02d:00", id / 60, id % 60);
        return new BirdEventTimelineItem(id, id, "DR" + id, type, date, time, species, place,
                observations, null, null);
    }

    private static List<Long> sortedIds(List<BirdEventTimelineItem> events, boolean newestFirst) {
        Comparator<BirdEventTimelineItem> order = Comparator.comparing(BirdEventTimelineItem::eventDate)
                .thenComparing(BirdEventTimelineItem::eventTime);
        if (newestFirst) order = order.reversed();
        return events.stream().sorted(order).map(BirdEventTimelineItem::id).toList();
    }

    private static void selectMode(CaptureListPanel panel, String mode) {
        selectLabel(field(panel, "dateFilterMode", JComboBox.class), mode);
    }

    private static void setYearRange(CaptureListPanel panel, int start, int end) {
        field(panel, "dateRangeStartYear", JSpinner.class).setValue(start);
        field(panel, "dateRangeEndYear", JSpinner.class).setValue(end);
    }

    private static void setMonthRange(CaptureListPanel panel, int startYear, String startMonth,
            int endYear, String endMonth) {
        setYearRange(panel, startYear, endYear);
        selectLabel(field(panel, "dateRangeStartMonth", JComboBox.class), startMonth);
        selectLabel(field(panel, "dateRangeEndMonth", JComboBox.class), endMonth);
    }

    private static int year(CaptureListPanel panel, String name) {
        return ((Number) field(panel, name, JSpinner.class).getValue()).intValue();
    }

    private static String validation(CaptureListPanel panel) {
        String text = field(panel, "dateRangeValidation", JLabel.class).getText();
        return text == null ? "" : text;
    }

    private static boolean visibleWithin(Component root, Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
            if (current == root) return true;
        }
        return false;
    }

    private static boolean pending(CaptureListPanel panel) {
        return !Boolean.FALSE.equals(panel.getClientProperty(FILTER_PENDING));
    }

    private static void awaitResults(Fixture fixture, List<Long> expectedVisible) throws Exception {
        await(() -> !pending(fixture.panel()) && visibleIds(fixture.panel()).equals(expectedVisible)
                        && field(fixture.panel(), "exportRecords", JButton.class).isEnabled() == !expectedVisible.isEmpty(),
                "Latest filtered cards should be " + expectedVisible);
    }

    private static List<Long> visibleIds(CaptureListPanel panel) {
        return descendants(panel, BirdEventCard.class).stream().map(card -> descendants(card, JLabel.class).stream()
                        .map(JLabel::getText).filter(text -> text != null && text.startsWith("Anilla DR"))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing ring label")))
                .map(text -> Long.parseLong(text.substring("Anilla DR".length()))).toList();
    }

    private static RecordReportSelection exportExcel(Fixture fixture) throws Exception {
        fixture.exported().set(null);
        runOnEdt(() -> {
            require(!pending(fixture.panel()) && field(fixture.panel(), "exportRecords", JButton.class).isEnabled(),
                    "Export must wait for the current filter result");
            JPopupMenu menu = field(fixture.panel(), "exportRecords", JButton.class).getComponentPopupMenu();
            require(menu != null, "The export menu must be available");
            field(menu, "exportEXCEL", JMenuItem.class).doClick();
        });
        await(() -> fixture.exported().get() != null, "The export action should receive the completed selection");
        return fixture.exported().get();
    }

    private static JTextField searchField(CaptureListPanel panel) {
        return descendants(panel, JTextField.class).stream()
                .filter(field -> !(field instanceof JFormattedTextField)).findFirst().orElseThrow();
    }

    private static JComboBox<?> comboOffering(Component root, Object option) {
        return descendants(root, JComboBox.class).stream().filter(combo -> {
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (option.equals(combo.getItemAt(index))) return true;
            }
            return false;
        }).findFirst().orElseThrow(() -> new AssertionError("Missing filter option: " + option));
    }

    private static void selectLabel(JComboBox<?> combo, String label) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            Object item = combo.getItemAt(index);
            if (item != null && label.equals(item.toString())) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        throw new AssertionError("Missing filter option: " + label);
    }

    private static JButton buttonWithText(Component root, String text) {
        return descendants(root, JButton.class).stream().filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    private static <T extends Component> T field(Component root, String name, Class<T> type) {
        return descendants(root, type).stream().filter(component -> name.equals(component.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing UI control: " + name));
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container parent) {
            for (Component child : parent.getComponents()) result.addAll(descendants(child, type));
        }
        return result;
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(message);
    }

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(action.get()));
        return value.get();
    }

    private static void runOnEdt(Runnable action) throws Exception {
        onEdt(() -> { action.run(); return null; });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(CaptureListPanel panel, AtomicInteger queries,
            AtomicReference<RecordReportSelection> exported) { }

    public static void main(String[] args) throws Exception {
        monthRangesIncludeWholeMonthsAcrossYearsAndGaps();
        yearRangesIncludeBoundariesAndASingleYear();
        invertedRangesStayInvalidWithoutAdjustingTheirLimits();
        dayRangesIncludeLeapDayAndBothEndpoints();
        rangeComposesWithFiltersAndExportsAllMatchesBeforeClear();
        System.out.println("DateRangeFilterTest: PASS");
    }
}

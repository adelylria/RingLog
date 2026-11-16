package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import com.adelylria.ringlog.application.ApplicationCapabilities;
import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.application.ApplicationMode;
import com.adelylria.ringlog.application.ApplicationMutationServices;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.preferences.ApplicationPreferences;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.DataPaths;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.HomePanel;
import com.j256.ormlite.support.ConnectionSource;

/** Exercises actual routes and a real SQLite archive, including a second page and its viewport. */
public final class CaptureReturnNavigationTest {
    private CaptureReturnNavigationTest() { }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("CaptureReturnNavigationTest: SKIP (requires a graphical desktop)");
            return;
        }
        Path root = Files.createTempDirectory("ringlog-return-navigation-").toAbsolutePath().normalize();
        String previousPreferences = System.getProperty("ringlog.preferences.path");
        System.setProperty("ringlog.preferences.path", root.resolve("preferences.properties").toString());
        MainFrame[] window = new MainFrame[1];
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("data"));
            Database.initialize(paths.databasePath().toString());
            seed(paths);
            ApplicationContext base = ApplicationContext.normal(paths);
            CountingAccess access = new CountingAccess(base.databaseAccess());
            ApplicationContext context = withCountingAccess(base, access);
            onEdt(() -> {
                window[0] = new MainFrame(context);
                window[0].setVisible(true);
            });
            MainFrame frame = window[0];
            HomePanel home = field(frame, "homePanel", HomePanel.class);
            await(() -> labelsContain(home, "5 entradas guardadas"), "The initial diary should finish loading");
            onEdt(() -> frame.navigate(MainFrame.CAPTURES));
            CaptureListPanel list = field(frame, "captureListPanel", CaptureListPanel.class);
            await(() -> archiveReady(list) && cards(list).size() == 30, "Load the first archive page");
            configureFilters(list);
            await(() -> archiveReady(list) && cards(list).size() == 100, "Apply filters using the archive snapshot");
            onEdt(() -> field(list, "showMoreButton", JButton.class).doClick());
            await(() -> archiveReady(list) && cards(list).size() == 130, "Expand the filtered archive to its second page");
            Object selection = invoke(list, "selection");
            int limit = field(list, "visibleLimit", Integer.class);
            require(limit == 130, "The test must exercise an expanded page rather than the first page");
            require(access.archiveReads.get() == 1, "Filtering and pagination must not reread the full database archive");

            Snapshot beforeBack = positionOnCard(frame, list, 7, selection, limit);
            int readsBeforeBack = access.archiveReads.get();
            openCard(list, 7);
            CaptureDetailPanel detail = field(frame, "captureDetailPanel", CaptureDetailPanel.class);
            awaitDetail(frame, detail);
            int accessesBeforeBack = access.opens.get();
            onEdt(() -> backButton(detail).doClick());
            await(() -> route(frame, MainFrame.CAPTURES), "Back from a detail must return to its source archive");
            assertArchiveSnapshot(frame, list, beforeBack, true);
            require(access.archiveReads.get() == readsBeforeBack && access.opens.get() == accessesBeforeBack,
                    "Back without changes must reuse the archive without a database read");

            Snapshot beforeCancel = positionOnCard(frame, list, 9, selection, limit);
            int readsBeforeCancel = access.archiveReads.get();
            openCard(list, 9);
            awaitDetail(frame, detail);
            CapturePanel editor = field(frame, "capturePanel", CapturePanel.class);
            onEdt(() -> find(detail, "editSelectedEvent", JButton.class).doClick());
            awaitEditor(frame, editor);
            await(() -> field(editor, "pendingPlaceId", Long.class) == null
                            && find(editor, "captureMethodField", JComboBox.class).getItemCount() >= 3,
                    "Finish the edit catalogs before measuring the cancel action");
            onEdt(() -> {
                find(editor, "observationsField", JTextArea.class).setText("Objetivo: borrador sin guardar");
                int accessesBeforeCancel = access.opens.get();
                editor.setDiscardConfirmation(pane -> false);
                find(editor, "cancelEventEdit", JButton.class).doClick();
                require(route(frame, MainFrame.NEW_CAPTURE) && editor.hasUnsavedChanges(),
                        "Declining cancellation must keep both the editor and its unsaved draft");
                editor.setDiscardConfirmation(pane -> true);
                find(editor, "cancelEventEdit", JButton.class).doClick();
                require(access.opens.get() == accessesBeforeCancel,
                        "Cancel must reuse the unchanged detail without any database access");
            });
            awaitDetail(frame, detail);
            onEdt(() -> backButton(detail).doClick());
            await(() -> route(frame, MainFrame.CAPTURES), "Cancelling an edit must retain the detail's source archive");
            assertArchiveSnapshot(frame, list, beforeCancel, true);
            require(access.archiveReads.get() == readsBeforeCancel,
                    "Cancelling and returning must not reload the database archive");

            Snapshot beforeSave = positionOnCard(frame, list, 11, selection, limit);
            long editedId = renderedItems(list).get(11).id();
            int readsBeforeSave = access.archiveReads.get();
            openCard(list, 11);
            awaitDetail(frame, detail);
            onEdt(() -> find(detail, "editSelectedEvent", JButton.class).doClick());
            awaitEditor(frame, editor);
            String savedNotes = "Objetivo: nota actualizada al volver";
            onEdt(() -> {
                find(editor, "observationsField", JTextArea.class).setText(savedNotes);
                find(editor, "saveEventButton", JButton.class).doClick();
            });
            await(() -> route(frame, MainFrame.CAPTURE_DETAIL) && labelsContain(detail, savedNotes),
                    "Saving must reopen the updated event without losing its source route");
            var savedEvent = new BirdEventRepository(paths).findDetail(editedId).orElseThrow();
            require(savedNotes.equals(savedEvent.observations()),
                    "Saving through the real MainFrame must update the same database event");
            require(Long.valueOf(1).equals(savedEvent.placeId()) && savedEvent.speciesId() == 1L,
                    "Saving notes must retain the existing place and species: " + savedEvent);
            onEdt(() -> backButton(detail).doClick());
            try {
                await(() -> route(frame, MainFrame.CAPTURES) && archiveReady(list)
                                && labelsContain(list, savedNotes),
                        "Returning after saving must refresh the edited card");
            } catch (AssertionError failure) {
                onEdt(() -> System.err.println("Navigation state: route="
                        + field(frame, "currentScreen", String.class) + ", ready=" + archiveReady(list)
                        + ", archiveReads=" + access.archiveReads + ", selection=" + invoke(list, "selection")
                        + ", limit=" + field(list, "visibleLimit", Integer.class)
                        + ", editedRendered=" + renderedItems(list).stream().filter(item -> item.id() == editedId).toList()));
                throw failure;
            }
            assertArchiveSnapshot(frame, list, beforeSave, false);
            require(access.archiveReads.get() == readsBeforeSave + 1,
                    "A saved change must invalidate the archive once while preserving its browsing state");

            onEdt(() -> frame.navigate(MainFrame.HOME));
            await(() -> labelsContain(home, "5 entradas guardadas"), "The diary should load after the saved change");
            JScrollPane homeScroll = find(home, null, JScrollPane.class);
            Point[] homePosition = new Point[1];
            BirdEventCard[] homeCard = new BirdEventCard[1];
            onEdt(() -> {
                frame.validate();
                homeScroll.getViewport().setViewPosition(new Point(0, 170));
                homePosition[0] = homeScroll.getViewport().getViewPosition();
                require(homePosition[0].y > 0, "The diary test must start away from the top");
                homeCard[0] = cards(home).get(1);
                button(homeCard[0], "Abrir").doClick();
            });
            awaitDetail(frame, detail);
            int homeReadsBeforeBack = access.homeReads.get();
            int accessesBeforeHomeBack = access.opens.get();
            onEdt(() -> backButton(detail).doClick());
            await(() -> route(frame, MainFrame.HOME), "A record opened from Diario must return to Diario");
            flushLayouts(frame);
            onEdt(() -> require(homePosition[0].equals(homeScroll.getViewport().getViewPosition())
                            && cards(home).get(1) == homeCard[0],
                    "The diary must preserve its viewport and cards when returning without changes"));
            require(access.homeReads.get() == homeReadsBeforeBack && access.opens.get() == accessesBeforeHomeBack,
                    "Returning to the unchanged diary must not query the database again");
            System.out.println("CaptureReturnNavigationTest: PASS");
        } finally {
            onEdt(() -> { if (window[0] != null) window[0].dispose(); });
            if (previousPreferences == null) System.clearProperty("ringlog.preferences.path");
            else System.setProperty("ringlog.preferences.path", previousPreferences);
            Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            require(root.startsWith(temporaryRoot) && !root.equals(temporaryRoot),
                    "Only this test's isolated temporary directory may be removed");
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void configureFilters(CaptureListPanel list) throws Exception {
        onEdt(() -> {
            field(list, "searchField", JTextField.class).setText("Objetivo");
            select(field(list, "speciesCombo", JComboBox.class), "Ave objetivo");
            select(field(list, "placeCombo", JComboBox.class), "Zona prueba");
            field(list, "typeCombo", JComboBox.class).setSelectedItem(EventType.RINGING);
            field(list, "dateModeCombo", JComboBox.class).setSelectedItem("Año");
            select(field(list, "datePeriodCombo", JComboBox.class), "2026");
            field(list, "dateSortOrderCombo", JComboBox.class).setSelectedIndex(1);
        });
    }

    private static Snapshot positionOnCard(MainFrame frame, CaptureListPanel list, int index,
            Object selection, int limit) throws Exception {
        Snapshot[] snapshot = new Snapshot[1];
        onEdt(() -> {
            frame.validate();
            JScrollPane scroll = find(list, "captureArchiveScroll", JScrollPane.class);
            BirdEventCard card = cards(list).get(index);
            Point location = SwingUtilities.convertPoint(card, 0, 0, scroll.getViewport().getView());
            scroll.getViewport().setViewPosition(new Point(0, Math.max(1, location.y - 70)));
            Point position = scroll.getViewport().getViewPosition();
            require(position.y > 0, "The archive test must start away from the top");
            snapshot[0] = new Snapshot(selection, limit, position, cards(list).get(0));
        });
        return snapshot[0];
    }

    private static void assertArchiveSnapshot(MainFrame frame, CaptureListPanel list, Snapshot before,
            boolean unchangedCards) throws Exception {
        flushLayouts(frame);
        await(() -> before.position().equals(find(list, "captureArchiveScroll", JScrollPane.class)
                .getViewport().getViewPosition()), "Restore the archive viewport after returning");
        onEdt(() -> {
            require(before.selection().equals(invoke(list, "selection")),
                    "Returning must retain search, species, place, event type, date and sort order");
            require(before.limit() == field(list, "visibleLimit", Integer.class) && cards(list).size() == 130,
                    "Returning must retain the expanded second page");
            if (unchangedCards) require(cards(list).get(0) == before.firstCard(),
                    "Returning without changes must retain the existing card instances");
        });
    }

    private static void openCard(CaptureListPanel list, int index) throws Exception {
        onEdt(() -> button(cards(list).get(index), "Abrir").doClick());
    }

    private static void awaitDetail(MainFrame frame, CaptureDetailPanel detail) throws Exception {
        await(() -> route(frame, MainFrame.CAPTURE_DETAIL)
                        && find(detail, "editSelectedEvent", JButton.class) != null,
                "The actual event detail should finish loading");
    }

    private static void awaitEditor(MainFrame frame, CapturePanel editor) throws Exception {
        await(() -> route(frame, MainFrame.NEW_CAPTURE)
                        && find(editor, "speciesField", JComboBox.class).getSelectedItem() != null
                        && !editor.hasUnsavedChanges(),
                "The actual edit form should finish loading without a dirty draft");
    }

    private static boolean archiveReady(CaptureListPanel list) {
        return !field(list, "loading", Boolean.class)
                && !Boolean.TRUE.equals(list.getClientProperty("RingLog.filterPending"));
    }

    private static boolean route(MainFrame frame, String expected) {
        return expected.equals(field(frame, "currentScreen", String.class));
    }

    private static List<BirdEventTimelineItem> renderedItems(CaptureListPanel list) {
        @SuppressWarnings("unchecked") List<BirdEventTimelineItem> items = field(list, "renderedItems", List.class);
        return items;
    }

    private static void select(JComboBox<?> combo, String text) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (text.equals(String.valueOf(combo.getItemAt(index)))) { combo.setSelectedIndex(index); return; }
        }
        throw new AssertionError("Missing filter option: " + text);
    }

    private static JButton backButton(CaptureDetailPanel detail) {
        JButton named = find(detail, "backCaptureDetailButton", JButton.class);
        if (named != null) return named;
        for (JButton candidate : descendants(detail, JButton.class)) {
            if (candidate.getText() != null && candidate.getText().startsWith("← Volver")) return candidate;
        }
        throw new AssertionError("Missing detail back action");
    }

    private static JButton button(Component root, String text) {
        for (JButton candidate : descendants(root, JButton.class)) if (text.equals(candidate.getText())) return candidate;
        throw new AssertionError("Missing action: " + text);
    }

    private static boolean labelsContain(Component root, String text) {
        return descendants(root, JLabel.class).stream().anyMatch(label -> text.equals(label.getText()))
                || descendants(root, JTextArea.class).stream().anyMatch(area -> text.equals(area.getText()));
    }

    private static List<BirdEventCard> cards(Component root) { return descendants(root, BirdEventCard.class); }
    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        collect(root, type, result);
        return result;
    }
    private static <T extends Component> void collect(Component root, Class<T> type, List<T> result) {
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) collect(child, type, result);
    }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        return descendants(root, type).stream().filter(component -> name == null || name.equals(component.getName()))
                .findFirst().orElse(null);
    }
    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static Object invoke(Object owner, String name) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void flushLayouts(MainFrame frame) throws Exception {
        onEdt(frame::validate);
        onEdt(() -> { });
        onEdt(() -> { });
    }
    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            boolean[] complete = {false};
            onEdt(() -> complete[0] = condition.getAsBoolean());
            if (complete[0]) return;
            Thread.sleep(30);
        }
        throw new AssertionError(message);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private record Snapshot(Object selection, int limit, Point position, BirdEventCard firstCard) { }

    private static void seed(AppPaths paths) throws SQLException {
        try (Connection connection = Database.getConnection(paths.databasePath().toString())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO species(id,stable_key,scientific_name,common_name) VALUES(1,'navigation-s1','Species prima','Ave objetivo'),(2,'navigation-s2','Species altera','Otra ave')");
                statement.execute("INSERT INTO place(id,stable_key,name) VALUES(1,'navigation-p1','Zona prueba'),(2,'navigation-p2','Otra zona')");
                statement.execute("INSERT INTO bird(id,stable_key,ring_number,species_id) VALUES(1,'navigation-b1','NAV-OBJ',1),(2,'navigation-b2','NAV-OTRO',2)");
            }
            try (var event = connection.prepareStatement("INSERT INTO bird_event(id,stable_key,bird_id,event_type,event_date,place_id,observations) VALUES(?,?,?,'RINGING',?,?,?)")) {
                for (int index = 1; index <= 200; index++) {
                    boolean match = index <= 180;
                    event.setLong(1, index);
                    event.setString(2, "navigation-event-" + index);
                    event.setLong(3, match ? 1 : 2);
                    event.setString(4, LocalDate.of(2026, 1, 1).plusDays(index - 1).toString());
                    event.setLong(5, match ? 1 : 2);
                    event.setString(6, (match ? "Objetivo entrada " : "Otra entrada ") + index);
                    event.addBatch();
                }
                event.executeBatch();
            }
            connection.commit();
        }
    }

    private static ApplicationContext withCountingAccess(ApplicationContext base, DatabaseAccess access) throws Exception {
        Constructor<ApplicationContext> constructor = ApplicationContext.class.getDeclaredConstructor(
                ApplicationMode.class, ApplicationCapabilities.class, DataPaths.class, DatabaseAccess.class,
                ApplicationPreferences.class, DataMutationCoordinator.class, ApplicationMutationServices.class);
        constructor.setAccessible(true);
        ApplicationCapabilities capabilities = new ApplicationCapabilities(true, true, true, true, true, false, true, true);
        return constructor.newInstance(base.mode(), capabilities, base.paths(), access, base.preferences(),
                base.mutationCoordinator(), base.mutationServices().orElseThrow());
    }

    private static final class CountingAccess implements DatabaseAccess {
        private final DatabaseAccess delegate;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger archiveReads = new AtomicInteger();
        final AtomicInteger homeReads = new AtomicInteger();
        CountingAccess(DatabaseAccess delegate) { this.delegate = delegate; }
        private void opened() {
            opens.incrementAndGet();
            StackWalker.getInstance().forEach(frame -> {
                if (!BirdEventRepository.class.getName().equals(frame.getClassName())) return;
                if ("findTimeline".equals(frame.getMethodName())) archiveReads.incrementAndGet();
                if ("findDashboardStats".equals(frame.getMethodName()) || "findRecentTimeline".equals(frame.getMethodName())) {
                    homeReads.incrementAndGet();
                }
            });
        }
        @Override public Connection openConnection() throws SQLException { opened(); return delegate.openConnection(); }
        @Override public ConnectionSource openConnectionSource() throws SQLException { opened(); return delegate.openConnectionSource(); }
        @Override public boolean readOnly() { return delegate.readOnly(); }
    }
}

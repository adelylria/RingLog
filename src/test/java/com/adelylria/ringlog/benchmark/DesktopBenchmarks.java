package com.adelylria.ringlog.benchmark;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.preferences.ApplicationPreferences;
import com.adelylria.ringlog.ui.MainFrame;
import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.theme.ThemeManager;

/** Optional real-window measurements. Never invoked by the default benchmark groups. */
public final class DesktopBenchmarks {
    private static final String TITLE = "RingLog · Benchmark sintético";
    private static final long TIMEOUT = 120_000_000_000L;
    private static final List<Route> ROUTES = List.of(
            new Route(MainFrame.HOME, "homePanel"),
            new Route(MainFrame.CAPTURES, "captureListPanel"),
            new Route(MainFrame.SPECIES, "speciesPanel"),
            new Route(MainFrame.PLACES, "placePanel"),
            new Route(MainFrame.CAPTURE_METHODS, "captureMethodsPanel"),
            new Route(MainFrame.REPORTS, "reportsPanel"),
            new Route(MainFrame.CONFLICTS, "conflictsPanel"),
            new Route(MainFrame.SETTINGS, "settingsPanel"),
            new Route(MainFrame.NEW_CAPTURE, "capturePanel"),
            new Route(MainFrame.CAPTURE_DETAIL, "captureDetailPanel")
    );

    private DesktopBenchmarks() { }

    public static void run(BenchmarkContext ctx) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            ctx.skip("desktop", "visible-window", "Requires explicit desktop group and -Djava.awt.headless=false");
            return;
        }
        ctx.require(!SwingUtilities.isEventDispatchThread(), "Desktop benchmark must orchestrate outside EDT");
        // MainFrame creates the normal application graph, but every storage boundary remains synthetic.
        System.setProperty("ringlog.test.mode", "true");
        System.setProperty(ApplicationPreferences.PATH_PROPERTY,
                ctx.workDirectory().resolve("desktop-preferences.properties").toAbsolutePath().toString());
        BirdEventDetail detail = ctx.events().findDetail(ctx.eventId()).orElseThrow();
        ctx.require(detail.latitude() == null || detail.longitude() == null,
                "Desktop fixture coordinates must be absent so navigation never requests map tiles");
        onEdt(() -> { ThemeManager.setupDarkTheme(); return null; });
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<MainFrame> current = new AtomicReference<>();
        int samples = Math.min(3, ctx.samples());
        try {
            startup(ctx, current, cancelled, samples);
            MainFrame frame = current.get();
            if (frame == null || cancelled.get() || !onEdt(frame::isDisplayable)) {
                skipNavigation(ctx, "Synthetic window was closed or could not be opened");
                return;
            }
            for (Route route : ROUTES) {
                if (cancelled.get()) {
                    ctx.skip("desktop-navigation", route.destination(), "Cancelled by closing the synthetic window");
                } else {
                    navigate(ctx, frame, route, cancelled, samples);
                }
            }
            if (cancelled.get()) {
                ctx.skip("desktop-calendar", "open-popup-cycle", "Cancelled by closing the synthetic window");
                ctx.skip("desktop-calendar", "next-month-cycle", "Cancelled by closing the synthetic window");
            } else {
                calendarPopups(ctx, frame, cancelled, samples);
            }
        } finally {
            if (current.get() != null) onEdt(() -> { dispose(current.get()); return null; });
        }
    }

    private static void startup(BenchmarkContext ctx, AtomicReference<MainFrame> current,
                                AtomicBoolean cancelled, int samples) {
        List<Long> contexts = new ArrayList<>();
        List<Long> constructors = new ArrayList<>();
        List<Long> visible = new ArrayList<>();
        List<Long> firstPaint = new ArrayList<>();
        List<Long> lags = new ArrayList<>();
        ctx.measure("desktop", "startup-settled", 0, samples, () -> {
            requireOpen(cancelled);
            if (current.get() != null) onEdt(() -> { dispose(current.get()); return null; });
            try (EdtProbe probe = new EdtProbe()) {
                long start = System.nanoTime();
                long[] actions = onEdt(() -> {
                    long contextStart = System.nanoTime();
                    ApplicationContext context = ApplicationContext.normal(ctx.paths());
                    long contextElapsed = System.nanoTime() - contextStart;
                    ctx.require(context.paths().databasePath().toAbsolutePath().normalize()
                                    .equals(ctx.paths().databasePath().toAbsolutePath().normalize()),
                            "MainFrame must use the benchmark database");
                    ctx.require(context.preferences().path().startsWith(ctx.workDirectory().toAbsolutePath().normalize()),
                            "MainFrame must use isolated benchmark preferences");
                    long constructorStart = System.nanoTime();
                    MainFrame frame = new MainFrame(context);
                    long constructorElapsed = System.nanoTime() - constructorStart;
                    current.set(frame);
                    guardClose(frame, cancelled);
                    frame.setTitle(TITLE);
                    Rectangle screen = frame.getGraphicsConfiguration().getBounds();
                    frame.setSize(Math.min(1280, Math.max(1040, screen.width - 60)),
                            Math.min(820, Math.max(680, screen.height - 90)));
                    frame.setLocationRelativeTo(null);
                    long showStart = System.nanoTime();
                    frame.setVisible(true);
                    long showElapsed = System.nanoTime() - showStart;
                    System.out.printf(java.util.Locale.ROOT,
                            "DESKTOP synthetic window=%dx%d screen=%dx%d scale=%.2f%n",
                            frame.getWidth(), frame.getHeight(), screen.width, screen.height,
                            frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
                    return new long[]{contextElapsed, constructorElapsed, showElapsed};
                });
                MainFrame frame = current.get();
                await(frame, cancelled, () -> ready(frame, MainFrame.HOME));
                onEdt(() -> { paint(frame); return null; });
                long first = System.nanoTime() - start;
                // The real constructor preloads the hidden capture form. Drain it before the next sample.
                await(frame, cancelled, () -> catalogsReady(field(frame, "capturePanel", JPanel.class)));
                await(frame, cancelled, () -> visiblePhotosReady(field(frame, "homePanel", JPanel.class)));
                onEdt(() -> { paint(frame); return null; });
                long lag = probe.finish();
                contexts.add(actions[0]);
                constructors.add(actions[1]);
                visible.add(actions[2]);
                firstPaint.add(first);
                lags.add(lag);
                return onEdt(() -> count(frame));
            }
        });
        record(ctx, "desktop-context-edt", "startup", contexts);
        record(ctx, "desktop-constructor-edt", "startup", constructors);
        record(ctx, "desktop-show-edt", "startup", visible);
        record(ctx, "desktop-first-data-paint", "startup", firstPaint);
        record(ctx, "desktop-max-edt-lag", "startup", lags);
    }

    private static void navigate(BenchmarkContext ctx, MainFrame frame, Route route,
                                 AtomicBoolean cancelled, int samples) {
        List<Long> handlers = new ArrayList<>();
        List<Long> dataPaints = new ArrayList<>();
        List<Long> mediaPaints = new ArrayList<>();
        List<Long> paints = new ArrayList<>();
        List<Long> lags = new ArrayList<>();
        ctx.measure("desktop-navigation", route.destination(), 0, samples, () -> {
            requireOpen(cancelled);
            AtomicBoolean newCatalogApplied = new AtomicBoolean(!MainFrame.NEW_CAPTURE.equals(route.destination()));
            JComboBox<?> species = onEdt(() -> find(field(frame, "capturePanel", JPanel.class), "speciesField", JComboBox.class));
            ListDataListener reloadListener = new ListDataListener() {
                @Override public void intervalAdded(ListDataEvent event) { newCatalogApplied.set(true); }
                @Override public void intervalRemoved(ListDataEvent event) { }
                @Override public void contentsChanged(ListDataEvent event) { }
            };
            if (MainFrame.NEW_CAPTURE.equals(route.destination())) {
                onEdt(() -> { species.getModel().addListDataListener(reloadListener); return null; });
            }
            try (EdtProbe probe = new EdtProbe()) {
                long start = System.nanoTime();
                long handler = onEdt(() -> {
                    CapturePanel capture = field(frame, "capturePanel", CapturePanel.class);
                    ctx.require(!capture.hasUnsavedChanges(), "Benchmarks must never discard a modified capture form");
                    long actionStart = System.nanoTime();
                    if (MainFrame.CAPTURE_DETAIL.equals(route.destination())) frame.showEvent(ctx.eventId());
                    else frame.navigate(route.destination());
                    return System.nanoTime() - actionStart;
                });
                await(frame, cancelled, () -> newCatalogApplied.get() && ready(frame, route.destination()));
                long paint = onEdt(() -> {
                    long paintStart = System.nanoTime();
                    paint(frame);
                    return System.nanoTime() - paintStart;
                });
                long data = System.nanoTime() - start;
                JPanel page = onEdt(() -> field(frame, route.panelField(), JPanel.class));
                await(frame, cancelled, () -> visiblePhotosReady(page));
                onEdt(() -> { paint(frame); return null; });
                long media = System.nanoTime() - start;
                handlers.add(handler);
                dataPaints.add(data);
                mediaPaints.add(media);
                paints.add(paint);
                lags.add(probe.finish());
                return onEdt(() -> count(page));
            } finally {
                if (MainFrame.NEW_CAPTURE.equals(route.destination())) {
                    onEdt(() -> { species.getModel().removeListDataListener(reloadListener); return null; });
                }
            }
        });
        record(ctx, "desktop-action-edt", route.destination(), handlers);
        record(ctx, "desktop-first-data-paint", route.destination(), dataPaints);
        record(ctx, "desktop-visible-media-paint", route.destination(), mediaPaints);
        record(ctx, "desktop-paint-sync-edt", route.destination(), paints);
        record(ctx, "desktop-max-edt-lag", route.destination(), lags);
    }

    private static void calendarPopups(BenchmarkContext ctx, MainFrame frame,
                                       AtomicBoolean cancelled, int samples) throws Exception {
        onEdt(() -> { frame.navigate(MainFrame.CAPTURES); return null; });
        await(frame, cancelled, () -> ready(frame, MainFrame.CAPTURES));
        JPanel records = onEdt(() -> field(frame, "captureListPanel", JPanel.class));
        onEdt(() -> {
            find(records, "dateFilterMode", JComboBox.class).setSelectedItem("Día");
            return null;
        });
        await(frame, cancelled, () -> ready(frame, MainFrame.CAPTURES));
        CalendarDatePicker picker = onEdt(() -> find(records, "dateRangeStartDay", CalendarDatePicker.class));
        onEdt(() -> {
            paint(frame);
            picker.scrollRectToVisible(new Rectangle(0, 0, picker.getWidth(), picker.getHeight()));
            paint(frame);
            ctx.require(picker.isShowing(), "The real day filter must be visible before measuring its popup");
            ctx.require(picker.getSelectedDate() != null, "The real day filter needs a selected fixture date");
            return null;
        });
        // Drain image requests from the navigation/filter preparation, outside popup timings.
        await(frame, cancelled, () -> visiblePhotosReady(records));
        popupCase(ctx, frame, picker, cancelled, samples, false);
        popupCase(ctx, frame, picker, cancelled, samples, true);
    }

    private static void popupCase(BenchmarkContext ctx, MainFrame frame, CalendarDatePicker picker,
                                   AtomicBoolean cancelled, int samples, boolean nextMonth) throws Exception {
        String name = nextMonth ? "next-month" : "open-popup";
        List<Long> handlers = new ArrayList<>();
        List<Long> paints = new ArrayList<>();
        List<Long> completed = new ArrayList<>();
        LocalDate selected = onEdt(picker::getSelectedDate);
        JPopupMenu popup = onEdt(picker::getComponentPopupMenu);
        ctx.measure("desktop-calendar", name + "-cycle", 0, samples, () -> {
            requireOpen(cancelled);
            try {
                onEdt(() -> {
                    popup.setVisible(false);
                    picker.setSelectedDate(selected);
                    if (nextMonth) {
                        openCalendar(ctx, picker, popup);
                        paintPopup(popup);
                    }
                    return null;
                });
                long started = System.nanoTime();
                long handler = onEdt(() -> {
                    long actionStart = System.nanoTime();
                    if (nextMonth) {
                        AbstractButton next = find(popup, "calendarNextMonth", AbstractButton.class);
                        ctx.require(next != null, "The visible calendar needs a next-month control");
                        next.doClick(0);
                    } else {
                        openCalendar(ctx, picker, popup);
                    }
                    return System.nanoTime() - actionStart;
                });
                long paint = onEdt(() -> {
                    ctx.require(popup.isVisible() && popup.isShowing(), "The native calendar popup did not remain visible");
                    ctx.require(calendarCellCount(popup) == 42, "The native calendar must retain all 42 reusable cells");
                    if (nextMonth) {
                        String firstDay = "calendarDay-" + YearMonth.from(selected).plusMonths(1).atDay(1);
                        AbstractButton day = find(popup, firstDay, AbstractButton.class);
                        ctx.require(day != null && day.isVisible(), "Month navigation did not show the expected dates");
                    }
                    ctx.require(selected.equals(picker.getSelectedDate()), "Opening/navigating must not change the date filter");
                    long paintStart = System.nanoTime();
                    paintPopup(popup);
                    return System.nanoTime() - paintStart;
                });
                long elapsed = System.nanoTime() - started;
                handlers.add(handler);
                paints.add(paint);
                completed.add(elapsed);
                return onEdt(() -> count(popup) + popup.getWidth() * 31L + popup.getHeight());
            } finally {
                onEdt(() -> {
                    popup.setVisible(false);
                    Toolkit.getDefaultToolkit().sync();
                    ctx.require(!popup.isVisible(), "The calendar popup must close between samples");
                    return null;
                });
            }
        });
        record(ctx, "desktop-calendar-action-edt", name, handlers);
        record(ctx, "desktop-calendar-paint-sync-edt", name, paints);
        record(ctx, "desktop-calendar-action-to-paint", name, completed);
    }

    private static void openCalendar(BenchmarkContext ctx, CalendarDatePicker picker, JPopupMenu popup) {
        AbstractButton trigger = find(picker, "calendarPickerButton", AbstractButton.class);
        ctx.require(trigger != null && trigger.isShowing(), "The real calendar trigger must be showing");
        trigger.doClick(0);
        ctx.require(popup.isVisible() && popup.isShowing(), "The real calendar action did not open a native popup");
    }

    private static void paintPopup(JPopupMenu popup) {
        popup.validate();
        popup.paintImmediately(0, 0, popup.getWidth(), popup.getHeight());
        Toolkit.getDefaultToolkit().sync();
    }

    private static int calendarCellCount(Component root) {
        int count = root instanceof JToggleButton ? 1 : 0;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) count += calendarCellCount(child);
        return count;
    }

    private static boolean ready(MainFrame frame, String route) {
        if (!route.equals(field(frame, "currentScreen", String.class))) return false;
        return switch (route) {
            case MainFrame.HOME -> "5 entradas guardadas".equals(
                    field(field(frame, "homePanel", JPanel.class), "statusLabel", JLabel.class).getText());
            case MainFrame.CAPTURES -> {
                JPanel panel = field(frame, "captureListPanel", JPanel.class);
                JLabel status = find(panel, "recordFilterStatus", JLabel.class);
                failOnLoadError(status);
                yield Boolean.FALSE.equals(panel.getClientProperty("RingLog.filterPending"))
                        && status != null && !status.getText().startsWith("Cargando");
            }
            case MainFrame.SPECIES -> prefix(field(frame, "speciesPanel", JPanel.class), "editSpecies-");
            case MainFrame.PLACES -> prefix(field(frame, "placePanel", JPanel.class), "editPlace-");
            case MainFrame.CAPTURE_METHODS -> prefix(field(frame, "captureMethodsPanel", JPanel.class), "captureMethodCard-");
            case MainFrame.REPORTS -> text(field(frame, "reportsPanel", JPanel.class), "ESPECIE MÁS FRECUENTE");
            case MainFrame.CONFLICTS -> {
                JLabel status = field(field(frame, "conflictsPanel", JPanel.class), "status", JLabel.class);
                failOnLoadError(status);
                yield !status.getText().startsWith("Cargando");
            }
            case MainFrame.SETTINGS -> text(field(frame, "settingsPanel", JPanel.class), "Acerca de RingLog");
            case MainFrame.NEW_CAPTURE -> catalogsReady(field(frame, "capturePanel", JPanel.class));
            case MainFrame.CAPTURE_DETAIL -> find(field(frame, "captureDetailPanel", JPanel.class),
                    "selectedEventTitle", JLabel.class) != null;
            default -> false;
        };
    }

    private static void failOnLoadError(JLabel status) {
        if (status != null && (status.getText().startsWith("No se ha podido")
                || status.getText().startsWith("No se han podido"))) {
            throw new AssertionError("Desktop page could not load: " + status.getText());
        }
    }

    private static boolean catalogsReady(JPanel panel) {
        JComboBox<?> species = find(panel, "speciesField", JComboBox.class);
        JComboBox<?> methods = find(panel, "captureMethodField", JComboBox.class);
        return species != null && species.getItemCount() > 0 && methods != null && methods.getItemCount() >= 3;
    }

    private static boolean visiblePhotosReady(Component component) {
        if (!component.isShowing()) return true;
        if (component instanceof JComponent swing && !swing.getVisibleRect().isEmpty()) {
            Object state = swing.getClientProperty("photoState");
            if ("loading".equals(state) || "idle".equals(state)) return false;
            if ("missing".equals(state)) throw new AssertionError("Synthetic visible photo is unavailable");
        }
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            if (!visiblePhotosReady(child)) return false;
        }
        return true;
    }

    private static void guardClose(MainFrame frame, AtomicBoolean cancelled) {
        for (WindowListener listener : frame.getWindowListeners()) frame.removeWindowListener(listener);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                cancelled.set(true);
                dispose(frame);
            }
        });
    }

    private static void paint(MainFrame frame) {
        frame.validate();
        JComponent content = (JComponent) frame.getContentPane();
        content.paintImmediately(0, 0, content.getWidth(), content.getHeight());
        Toolkit.getDefaultToolkit().sync();
    }

    private static void dispose(Component component) {
        if (component instanceof EventPhotoGallery gallery) gallery.dispose();
        if (component instanceof CapturePanel capture) field(capture, "ringLookupTimer", Timer.class).stop();
        if (component instanceof com.adelylria.ringlog.ui.panels.CaptureListPanel list)
            field(list, "filterDelay", Timer.class).stop();
        if (component instanceof Container parent) for (Component child : parent.getComponents()) dispose(child);
        if (component instanceof MainFrame frame) frame.dispose();
    }

    private static void await(MainFrame frame, AtomicBoolean cancelled, Ready ready) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT;
        do {
            requireOpen(cancelled);
            if (!onEdt(frame::isDisplayable)) throw new Cancelled("Synthetic benchmark window was disposed");
            if (onEdt(ready::get)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Desktop benchmark timed out on " + onEdt(() -> field(frame, "currentScreen", String.class)));
    }

    private static void requireOpen(AtomicBoolean cancelled) throws Cancelled {
        if (cancelled.get()) throw new Cancelled("Cancelled by closing the synthetic benchmark window");
    }

    private static void skipNavigation(BenchmarkContext ctx, String reason) {
        for (Route route : ROUTES) ctx.skip("desktop-navigation", route.destination(), reason);
    }

    private static void record(BenchmarkContext ctx, String group, String name, List<Long> nanos) {
        if (!nanos.isEmpty()) ctx.recordNanos(group, name, nanos, nanos.stream().mapToLong(Long::longValue).sum());
    }

    private static boolean text(Component root, String expected) {
        if (root instanceof JLabel label && expected.equals(label.getText())) return true;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) if (text(child, expected)) return true;
        return false;
    }

    private static boolean prefix(Component root, String expected) {
        if (root.getName() != null && root.getName().startsWith(expected)) return true;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) if (prefix(child, expected)) return true;
        return false;
    }

    private static <T> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            T found = find(child, name, type);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Missing desktop benchmark state: " + name, exception);
        }
    }

    private static long count(Component root) {
        long count = 1;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) count += count(child);
        return count;
    }

    private static <T> T onEdt(Action<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.get()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Exception failure) throw failure;
        if (error.get() instanceof Error failure) throw failure;
        return result.get();
    }

    private record Route(String destination, String panelField) { }
    @FunctionalInterface private interface Action<T> { T get() throws Exception; }
    @FunctionalInterface private interface Ready { boolean get() throws Exception; }
    private static final class Cancelled extends Exception {
        private Cancelled(String message) { super(message); }
    }

    private static final class EdtProbe implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicLong maximum = new AtomicLong();
        private final Thread ticker;
        private boolean finished;

        private EdtProbe() {
            ticker = new Thread(() -> {
                while (running.get()) {
                    if (queued.compareAndSet(false, true)) {
                        long posted = System.nanoTime();
                        SwingUtilities.invokeLater(() -> {
                            maximum.accumulateAndGet(System.nanoTime() - posted, Math::max);
                            queued.set(false);
                        });
                    }
                    try { Thread.sleep(8); } catch (InterruptedException ignored) { break; }
                }
            }, "ringlog-desktop-benchmark-edt-probe");
            ticker.setDaemon(true);
            ticker.start();
        }

        private long finish() throws Exception {
            if (!finished) {
                running.set(false);
                ticker.interrupt();
                ticker.join(500);
                onEdt(() -> null);
                finished = true;
            }
            return maximum.get();
        }

        @Override public void close() throws Exception { finish(); }
    }
}

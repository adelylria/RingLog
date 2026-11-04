package com.adelylria.ringlog.benchmark;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.media.EventPhotoService;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.CalendarDatePicker;
import com.adelylria.ringlog.ui.components.DatePeriodSelector;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;
import com.adelylria.ringlog.ui.panels.CaptureMethodsPanel;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.HomePanel;
import com.adelylria.ringlog.ui.panels.PlacePanel;
import com.adelylria.ringlog.ui.panels.ReportsPanel;
import com.adelylria.ringlog.ui.panels.SettingsPanel;
import com.adelylria.ringlog.ui.panels.SpeciesPanel;
import com.adelylria.ringlog.ui.theme.ThemeManager;

/**
 * Real fixture reads and offscreen Swing rendering, without frames, user-data writes or map access.
 * Screen and interaction records separate EDT handlers from completion and from final layout/paint.
 */
public final class UiBenchmarks {
    private static final int WIDTH = 1040;
    private static final int HEIGHT = 760;
    private static final long SETTLE_TIMEOUT_NANOS = 120_000_000_000L;

    private UiBenchmarks() { }

    public static void run(BenchmarkContext ctx) throws Exception {
        ctx.require(!SwingUtilities.isEventDispatchThread(), "UI benchmarks must orchestrate outside the EDT");
        onEdt(() -> { ThemeManager.setupDarkTheme(); return null; });
        BirdEventDetail detail = ctx.events().findDetail(ctx.eventId()).orElseThrow();
        BirdEventDetail photoDetail = ctx.events().findDetail(ctx.photoEventId()).orElseThrow();
        ctx.require(!photoDetail.photoPaths().isEmpty(), "The UI photo fixture must contain a photo");

        List<Screen> screens = List.of(
                new Screen("diary", () -> new HomePanel(ctx.events(), id -> { }, () -> { }),
                        panel -> ((HomePanel) panel).refresh(), panel -> hasText(panel, "5 entradas guardadas"), false),
                new Screen("records", () -> new CaptureListPanel(ctx.events(), id -> { }, () -> { }),
                        panel -> ((CaptureListPanel) panel).refresh(), UiBenchmarks::filtersSettled, false),
                detailScreen(ctx, "detail-no-photo", false),
                detailScreen(ctx, "detail-photo", true),
                new Screen("new-record", () -> new CapturePanel(ctx.events(), ctx.catalog(), id -> { }, () -> { },
                        new EventPhotoService(ctx.paths(), new DataMutationCoordinator())),
                        panel -> { }, UiBenchmarks::catalogsLoaded, false),
                new Screen("edit-record-no-photo", () -> new CapturePanel(ctx.events(), ctx.catalog(), id -> { }, () -> { },
                        new EventPhotoService(ctx.paths(), new DataMutationCoordinator())),
                        panel -> ((CapturePanel) panel).edit(withoutMap(detail, false)),
                        UiBenchmarks::catalogsLoaded, false),
                new Screen("edit-record-photo", () -> new CapturePanel(ctx.events(), ctx.catalog(), id -> { }, () -> { },
                        new EventPhotoService(ctx.paths(), new DataMutationCoordinator())),
                        panel -> ((CapturePanel) panel).edit(withoutMap(photoDetail, true)),
                        UiBenchmarks::catalogsLoaded, true),
                new Screen("species", () -> new SpeciesPanel(ctx.catalog()),
                        panel -> ((SpeciesPanel) panel).refresh(), panel -> hasNamedPrefix(panel, "editSpecies-"), false),
                new Screen("places", () -> new PlacePanel(ctx.catalog()),
                        panel -> ((PlacePanel) panel).refresh(), panel -> hasNamedPrefix(panel, "editPlace-"), false),
                new Screen("capture-methods", () -> new CaptureMethodsPanel(ctx.catalog()),
                        panel -> ((CaptureMethodsPanel) panel).refresh(), panel -> hasNamedPrefix(panel, "captureMethodCard-"), false),
                new Screen("reports", () -> new ReportsPanel(ctx.events(), ctx.catalog()),
                        panel -> ((ReportsPanel) panel).refresh(), panel -> hasText(panel, "ESPECIE MÁS FRECUENTE"), false),
                new Screen("settings", () -> new SettingsPanel(null, ctx.paths(), null, () -> { }, () -> { }),
                        panel -> { }, panel -> hasText(panel, "Acerca de RingLog"), false)
        );
        for (Screen screen : screens) screen(ctx, screen);
        filters(ctx, detail);
        calendar(ctx);
    }

    private static Screen detailScreen(BenchmarkContext ctx, String name, boolean photos) {
        BirdEventRepository safeRepository = new BirdEventRepository(ctx.databaseFile()) {
            @Override public Optional<BirdEventDetail> findDetail(long eventId) {
                return ctx.events().findDetail(eventId).map(detail -> uncheckedWithoutMap(detail, photos));
            }
            @Override public List<BirdEventTimelineItem> findEventsByBird(long birdId) {
                return ctx.events().findEventsByBird(birdId);
            }
        };
        return new Screen(name, () -> new CaptureDetailPanel(safeRepository, () -> { }, detail -> { },
                true, ctx.workDirectory().resolve("unused-map-cache")),
                panel -> ((CaptureDetailPanel) panel).setEventId(ctx.photoEventId()),
                panel -> find(panel, "selectedEventTitle", JLabel.class) != null, photos);
    }

    private static void screen(BenchmarkContext ctx, Screen screen) {
        boolean heavy = "records".equals(screen.name()) && ctx.eventCount() >= 50_000;
        int warmups = heavy ? 0 : ctx.warmups();
        int samples = heavy ? Math.min(3, ctx.samples()) : ctx.samples();
        List<Long> constructors = new ArrayList<>();
        List<Long> paint = new ArrayList<>();
        List<Long> firstPaint = new ArrayList<>();
        List<Long> stalls = new ArrayList<>();
        int[] round = {0};
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        ctx.measure("ui-screen", screen.name(), warmups, samples, () -> {
            boolean measured = round[0]++ >= warmups;
            AtomicReference<JPanel> panel = new AtomicReference<>();
            try (EdtProbe probe = new EdtProbe()) {
                long started = System.nanoTime();
                long constructorNanos = onEdt(() -> {
                    long constructorStart = System.nanoTime();
                    JPanel created = screen.create().get();
                    panel.set(created);
                    long elapsed = System.nanoTime() - constructorStart;
                    screen.load().run(created);
                    return elapsed;
                });
                await(screen.name(), () -> screen.ready().test(panel.get()));
                if (screen.photos()) await(screen.name() + " photo", () -> photosReady(panel.get()));
                long paintNanos = onEdt(() -> {
                    long paintStart = System.nanoTime();
                    paint(panel.get(), image);
                    return System.nanoTime() - paintStart;
                });
                long complete = System.nanoTime() - started;
                // Offscreen painting can start timeline thumbnail work. Keep first-paint timing
                // separate, but finish that real decode/cache work before the next sample begins.
                await(screen.name() + " painted thumbnails", () -> photosReady(panel.get()));
                long checksum = onEdt(() -> checksum(panel.get(), image));
                long maximumLag = probe.finish();
                if (measured) {
                    constructors.add(constructorNanos);
                    paint.add(paintNanos);
                    firstPaint.add(complete);
                    stalls.add(maximumLag);
                }
                return checksum;
            } finally {
                if (panel.get() != null) onEdt(() -> { dispose(panel.get()); return null; });
            }
        });
        record(ctx, "ui-constructor-edt", screen.name(), constructors);
        record(ctx, "ui-layout-paint-edt", screen.name(), paint);
        record(ctx, "ui-first-paint", screen.name(), firstPaint);
        record(ctx, "ui-max-edt-lag", screen.name(), stalls);
    }

    private static void filters(BenchmarkContext ctx, BirdEventDetail detail) throws Exception {
        CaptureListPanel panel = onEdt(() -> new CaptureListPanel(ctx.events(), id -> { }, () -> { }));
        onEdt(() -> { panel.refresh(); return null; });
        await("initial records for filters", () -> filtersSettled(panel));
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        onEdt(() -> { paint(panel, image); return null; });
        LocalDate latest = LocalDate.parse(detail.eventDate());
        CheckedRunnable reset = () -> {
            onEdt(() -> { buttonWithText(panel, "Limpiar filtros").doClick(0); return null; });
            await("clear filters", () -> filtersSettled(panel));
        };
        try {
            interaction(ctx, panel, image, "text-exact-ring", reset,
                    () -> field(panel, "searchField", JTextField.class).setText(detail.ringNumber()));
            interaction(ctx, panel, image, "text-broad", reset, () -> {
                String ring = detail.ringNumber();
                field(panel, "searchField", JTextField.class).setText(ring.substring(0, Math.min(3, ring.length())));
            });
            interaction(ctx, panel, image, "event-type", reset,
                    () -> field(panel, "typeCombo", JComboBox.class).setSelectedItem(EventType.CONTROL));
            interaction(ctx, panel, image, "sort-oldest-first", reset,
                    () -> named(panel, "dateSortOrder", JComboBox.class).setSelectedIndex(1));
            interaction(ctx, panel, image, "date-day", reset, () -> {
                named(panel, "dateFilterMode", JComboBox.class).setSelectedItem("Día");
                named(panel, "dateRangeStartDay", CalendarDatePicker.class).setSelectedDate(latest);
            });
            interaction(ctx, panel, image, "date-month-range", reset, () -> {
                named(panel, "dateFilterMode", JComboBox.class).setSelectedItem("Rango de meses");
                field(panel, "rangeStart", DatePeriodSelector.class).setPeriod(YearMonth.from(latest.minusMonths(6)));
                field(panel, "rangeEnd", DatePeriodSelector.class).setPeriod(YearMonth.from(latest));
            });
            interaction(ctx, panel, image, "date-year-range", reset, () -> {
                named(panel, "dateFilterMode", JComboBox.class).setSelectedItem("Rango de años");
                field(panel, "rangeStart", DatePeriodSelector.class).setPeriod(YearMonth.of(latest.getYear() - 3, 1));
                field(panel, "rangeEnd", DatePeriodSelector.class).setPeriod(YearMonth.of(latest.getYear(), 12));
            });
            interaction(ctx, panel, image, "next-30-records", reset,
                    () -> field(panel, "showMoreButton", AbstractButton.class).doClick(0));
        } finally {
            onEdt(() -> { dispose(panel); return null; });
        }
    }

    private static void interaction(BenchmarkContext ctx, CaptureListPanel panel, BufferedImage image,
                                    String name, CheckedRunnable prepare, CheckedRunnable action) {
        List<Long> handlers = new ArrayList<>();
        List<Long> complete = new ArrayList<>();
        List<Long> paints = new ArrayList<>();
        List<Long> stalls = new ArrayList<>();
        int[] round = {0};
        ctx.measure("ui-scenario", "prepare-and-" + name, () -> {
            prepare.run();
            boolean measured = round[0]++ >= ctx.warmups();
            try (EdtProbe probe = new EdtProbe()) {
                long started = System.nanoTime();
                long handler = onEdt(() -> {
                    long handlerStart = System.nanoTime();
                    action.run();
                    return System.nanoTime() - handlerStart;
                });
                await(name, () -> filtersSettled(panel));
                long paintNanos = onEdt(() -> {
                    long paintStart = System.nanoTime();
                    paint(panel, image);
                    return System.nanoTime() - paintStart;
                });
                long elapsed = System.nanoTime() - started;
                onEdt(() -> {
                    JLabel validation = named(panel, "dateRangeValidation", JLabel.class);
                    ctx.require(!validation.isVisible(), "Unexpected filter error in " + name + ": " + validation.getText());
                    boolean expectsMatches = !"event-type".equals(name)
                            || Integer.getInteger("ringlog.benchmark.historyLength", 10) > 2;
                    if (expectsMatches) ctx.require(!field(panel, "filterResult", List.class).isEmpty(),
                            "Fixture filter unexpectedly returned no events: " + name);
                    return null;
                });
                await(name + " painted thumbnails", () -> photosReady(panel));
                long checksum = onEdt(() -> checksum(panel, image));
                long maximumLag = probe.finish();
                if (measured) {
                    handlers.add(handler);
                    complete.add(elapsed);
                    paints.add(paintNanos);
                    stalls.add(maximumLag);
                }
                return checksum;
            }
        });
        record(ctx, "ui-action-edt", name, handlers);
        record(ctx, "ui-action-to-paint", name, complete);
        record(ctx, "ui-action-paint-edt", name, paints);
        record(ctx, "ui-action-max-edt-lag", name, stalls);
    }

    private static void calendar(BenchmarkContext ctx) throws Exception {
        CalendarDatePicker picker = onEdt(() -> new CalendarDatePicker(LocalDate.of(2026, 2, 9)));
        var render = CalendarDatePicker.class.getDeclaredMethod("renderMonth");
        render.setAccessible(true);
        benchmarkEdtBatch(ctx, "calendar-reopen-content", 300, () -> render.invoke(picker));
        benchmarkEdtBatch(ctx, "calendar-next-month", 36,
                () -> named(picker.getComponentPopupMenu(), "calendarNextMonth", AbstractButton.class).doClick(0));
        benchmarkEdtBatch(ctx, "calendar-select-day", 60,
                new CheckedRunnable() {
                    private int day;
                    @Override public void run() { picker.setSelectedDate(LocalDate.of(2026, 2, ++day % 28 + 1)); }
                });
    }

    private static void benchmarkEdtBatch(BenchmarkContext ctx, String name, int actions,
                                         CheckedRunnable action) {
        List<Long> perAction = new ArrayList<>();
        int[] round = {0};
        ctx.measure("ui-calendar-batch", name, () -> {
            long elapsed = onEdt(() -> {
                long started = System.nanoTime();
                for (int actionIndex = 0; actionIndex < actions; actionIndex++) action.run();
                return System.nanoTime() - started;
            });
            if (round[0]++ >= ctx.warmups()) perAction.add(elapsed / actions);
            return elapsed;
        });
        record(ctx, "ui-calendar-action-edt", name, perAction);
    }

    private static void record(BenchmarkContext ctx, String group, String name, List<Long> nanos) {
        if (!nanos.isEmpty()) ctx.recordNanos(group, name, nanos, nanos.stream().mapToLong(Long::longValue).sum());
    }

    private static boolean filtersSettled(JPanel panel) {
        if (!Boolean.FALSE.equals(panel.getClientProperty("RingLog.filterPending"))) return false;
        JLabel status = find(panel, "recordFilterStatus", JLabel.class);
        if (status == null) return false;
        String text = status.getText();
        if (text.startsWith("No se han podido") || text.startsWith("Se ha interrumpido")
                || "Rango de fechas no válido".equals(text)) {
            throw new AssertionError("Records failed to load: " + text);
        }
        return !text.startsWith("Cargando") && !text.startsWith("Filtrando");
    }

    private static boolean catalogsLoaded(JPanel panel) {
        JComboBox<?> species = find(panel, "speciesField", JComboBox.class);
        JComboBox<?> methods = find(panel, "captureMethodField", JComboBox.class);
        return species != null && species.getItemCount() > 0
                && methods != null && methods.getItemCount() >= 3;
    }

    private static boolean photosReady(Component root) {
        if (root instanceof JComponent component) {
            Object state = component.getClientProperty("photoState");
            if ("loading".equals(state)) return false;
            if ("missing".equals(state)) throw new AssertionError("A fixture photo could not be decoded");
        }
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            if (!photosReady(child)) return false;
        }
        return true;
    }

    private static void paint(JPanel panel, BufferedImage image) {
        panel.setSize(image.getWidth(), image.getHeight());
        for (int pass = 0; pass < 3; pass++) layout(panel);
        Graphics2D graphics = image.createGraphics();
        try { panel.paint(graphics); } finally { graphics.dispose(); }
    }

    private static void layout(Component component) {
        if (component instanceof Container parent) {
            parent.doLayout();
            if (parent instanceof JScrollPane scroll && scroll.getViewport().getView() instanceof JPanel view) {
                view.setSize(scroll.getViewport().getWidth(),
                        Math.max(scroll.getViewport().getHeight(), view.getPreferredSize().height));
            }
            for (Component child : parent.getComponents()) layout(child);
        }
    }

    private static long checksum(Component component, BufferedImage image) {
        long result = componentCount(component);
        for (int y = 0; y < image.getHeight(); y += 91) {
            for (int x = 0; x < image.getWidth(); x += 97) result = result * 31 + image.getRGB(x, y);
        }
        return result;
    }

    private static long componentCount(Component component) {
        long count = 1;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) count += componentCount(child);
        return count;
    }

    private static void dispose(Component component) {
        if (component instanceof EventPhotoGallery gallery) gallery.dispose();
        if (component instanceof BirdEventCard card) {
            try {
                var cancel = BirdEventCard.class.getDeclaredMethod("cancelPhotoLoading");
                cancel.setAccessible(true);
                cancel.invoke(card);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not stop a benchmark thumbnail", exception);
            }
        }
        if (component instanceof CapturePanel capture) {
            field(capture, "ringLookupTimer", Timer.class).stop();
        }
        if (component instanceof CaptureListPanel records) {
            field(records, "filterDelay", Timer.class).stop();
        }
        if (component instanceof Container parent) for (Component child : parent.getComponents()) dispose(child);
    }

    private static void await(String description, CheckedBoolean ready) throws Exception {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS;
        do {
            if (onEdt(ready::get)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("UI benchmark timed out: " + description);
    }

    private static <T> T onEdt(CheckedSupplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.get()); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Exception exception) throw exception;
        if (error.get() instanceof Error failure) throw failure;
        return result.get();
    }

    private static AbstractButton buttonWithText(Component component, String text) {
        if (component instanceof AbstractButton button && text.equals(button.getText())) return button;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            AbstractButton found = buttonWithText(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasText(Component component, String text) {
        if (component instanceof JLabel label && text.equals(label.getText())) return true;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) if (hasText(child, text)) return true;
        return false;
    }

    private static boolean hasNamedPrefix(Component component, String prefix) {
        if (component.getName() != null && component.getName().startsWith(prefix)) return true;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) if (hasNamedPrefix(child, prefix)) return true;
        return false;
    }

    private static <T> T named(Component root, String name, Class<T> type) {
        T found = find(root, name, type);
        if (found == null) throw new IllegalArgumentException("Missing UI control: " + name);
        return found;
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
            throw new IllegalArgumentException("Missing benchmark control: " + name, exception);
        }
    }

    private static BirdEventDetail uncheckedWithoutMap(BirdEventDetail detail, boolean photos) {
        try { return withoutMap(detail, photos); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static BirdEventDetail withoutMap(BirdEventDetail detail, boolean photos) throws Exception {
        RecordComponent[] components = BirdEventDetail.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            types[index] = components[index].getType();
            values[index] = switch (components[index].getName()) {
                case "latitude", "longitude" -> null;
                case "photoPaths" -> photos ? detail.photoPaths() : List.of();
                default -> components[index].getAccessor().invoke(detail);
            };
        }
        return BirdEventDetail.class.getDeclaredConstructor(types).newInstance(values);
    }

    private record Screen(String name, CheckedSupplier<JPanel> create, PanelAction load,
                          PanelReady ready, boolean photos) { }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    @FunctionalInterface private interface CheckedBoolean { boolean get() throws Exception; }
    @FunctionalInterface private interface PanelAction { void run(JPanel panel) throws Exception; }
    @FunctionalInterface private interface PanelReady { boolean test(JPanel panel) throws Exception; }

    /** At most one queued probe: measuring a stall must not flood the event queue. */
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
            }, "ringlog-benchmark-edt-probe");
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

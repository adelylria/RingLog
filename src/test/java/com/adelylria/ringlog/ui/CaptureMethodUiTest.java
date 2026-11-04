package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdLookup;
import com.adelylria.ringlog.model.view.CaptureMethodOption;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.CaptureMethodsPanel;

/** Exercises catalog editing and the event draft without opening modal windows or a database. */
public final class CaptureMethodUiTest {

    private CaptureMethodUiTest() {
    }

    public static void managerAddsRenamesArchivesAndRestoresMethods() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        AtomicInteger changed = new AtomicInteger();
        CaptureMethodsPanel manager = onEdt(() -> new CaptureMethodsPanel(
                catalog, true, changed::incrementAndGet));
        onEdt(manager::refresh);
        await(() -> hasCard(manager, 1L) && hasCard(manager, 2L), "Initial capture methods should load");

        onEdt(() -> {
            button(manager, "newCaptureMethodButton").doClick();
            require(field(manager, "captureMethodEditorView", JPanel.class).isVisible(),
                    "Adding a capture method must open an inline editor");
            require(field(manager, "captureMethodName", JTextField.class).getText().isEmpty(),
                    "New method must clear the previous editor");
            field(manager, "captureMethodName", JTextField.class).setText("RECLAMO");
            button(manager, "saveCaptureMethodButton").doClick();
        });
        await(() -> changed.get() == 1 && hasCard(manager, catalog.idFor("RECLAMO")),
                "Adding a method should refresh the cards and notify its caller");
        long id = catalog.idFor("RECLAMO");

        onEdt(() -> {
            button(manager, "editCaptureMethod-" + id).doClick();
            require("RECLAMO".equals(field(manager, "captureMethodName", JTextField.class).getText()),
                    "Editing a catalog card must load its name");
            field(manager, "captureMethodName", JTextField.class).setText("RECLAMO MANUAL");
            button(manager, "saveCaptureMethodButton").doClick();
        });
        await(() -> changed.get() == 2 && cardHasText(manager, id, "RECLAMO MANUAL"),
                "Renaming a method should refresh its card");
        require(catalog.idFor("RECLAMO MANUAL") == id && catalog.findCaptureMethods().size() == 3,
                "Renaming must retain method identity rather than add a duplicate");

        onEdt(() -> button(manager, "toggleCaptureMethodActive-" + id).doClick());
        await(() -> changed.get() == 3 && cardHasText(manager, id, "Inactivo"),
                "Deactivation must retain a visible catalog card marked Inactivo");
        require(catalog.findCaptureMethodOptions().stream().noneMatch(method -> method.id() == id),
                "Archived methods must leave the active choices");

        onEdt(() -> button(manager, "toggleCaptureMethodActive-" + id).doClick());
        await(() -> changed.get() == 4 && cardHasText(manager, id, "Activo"),
                "An archived method should be reactivatable");
        require(catalog.findCaptureMethodOptions().stream().anyMatch(method -> method.id() == id),
                "Reactivation must restore the same method to active choices");
    }

    public static void managerPreservesFailedDraftAndRespectsMutationLock() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        AtomicInteger changed = new AtomicInteger();
        CaptureMethodsPanel manager = onEdt(() -> new CaptureMethodsPanel(
                catalog, true, changed::incrementAndGet));
        onEdt(manager::refresh);
        await(() -> hasCard(manager, 1L) && hasCard(manager, 2L), "Catalog should load before editing");

        onEdt(() -> {
            button(manager, "newCaptureMethodButton").doClick();
            field(manager, "captureMethodName", JTextField.class).setText("FILATS");
            button(manager, "saveCaptureMethodButton").doClick();
        });
        await(() -> button(manager, "saveCaptureMethodButton").isEnabled()
                        && field(manager, "captureMethodStatus", JLabel.class).getText().contains("duplicado"),
                "Duplicate names should explain the error and restore editing");
        onEdt(() -> {
            require("FILATS".equals(field(manager, "captureMethodName", JTextField.class).getText()),
                    "A failed save must retain the attempted name");
            require(changed.get() == 0 && catalog.findCaptureMethods().size() == 2,
                    "A failed save must not notify a persisted catalog change");
            manager.setDiscardConfirmation(pane -> false);
            require(!manager.confirmLeave(), "Unsaved manager edits need discard protection");
            button(manager, "cancelCaptureMethodEditorButton").doClick();
            manager.refresh();
            require(field(manager, "captureMethodEditorView", JPanel.class).isVisible()
                            && manager.hasUnsavedChanges(),
                    "Declining discard must preserve the inline draft when cancelling or refreshing");
            manager.setMutationActionsEnabled(false);
            require(!button(manager, "newCaptureMethodButton").isEnabled()
                            && !button(manager, "saveCaptureMethodButton").isEnabled()
                            && !button(manager, "editCaptureMethod-1").isEnabled()
                            && !button(manager, "toggleCaptureMethodActive-1").isEnabled(),
                    "Every catalog mutation must follow the global mutation lock");
            button(manager, "saveCaptureMethodButton").doClick();
            manager.setMutationActionsEnabled(true);
            require(button(manager, "newCaptureMethodButton").isEnabled()
                            && button(manager, "saveCaptureMethodButton").isEnabled(),
                    "Removing the mutation lock must restore available editor actions");
            manager.setDiscardConfirmation(pane -> true);
            require(manager.confirmLeave(), "Confirmed discard should permit closing the manager");
            button(manager, "cancelCaptureMethodEditorButton").doClick();
            require(!field(manager, "captureMethodEditorView", JPanel.class).isVisible(),
                    "Cancelling must return from the inline editor to the catalog cards");
        });
        require(catalog.findCaptureMethods().size() == 2 && changed.get() == 0,
                "Disabled or rejected actions must not write catalog data");

        CaptureMethodsPanel readOnly = onEdt(() -> new CaptureMethodsPanel(catalog, false, changed::incrementAndGet));
        onEdt(readOnly::refresh);
        await(() -> hasCard(readOnly, 1L) && hasCard(readOnly, 2L),
                "Read-only users should still see the method catalog");
        onEdt(() -> require(find(readOnly, "newCaptureMethodButton", JButton.class) == null
                        && find(readOnly, "editCaptureMethod-1", JButton.class) == null
                        && find(readOnly, "toggleCaptureMethodActive-1", JButton.class) == null,
                "A read-only catalog must not expose create, edit or archive actions"));
    }

    public static void newEntriesOfferActiveMethodsAndSaveTheSelection() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        catalog.put(new CaptureMethodOption(7L, "MÉTODO ANTIGUO", false));
        SavedEvents events = new SavedEvents();
        AtomicLong saved = new AtomicLong();
        CapturePanel panel = onEdt(() -> new CapturePanel(events, catalog, saved::set, () -> { }));
        JComboBox<?> methods = field(panel, "captureMethodField", JComboBox.class);
        await(() -> contains(methods, 1L) && contains(methods, 2L)
                        && field(panel, "speciesField", JComboBox.class).getItemCount() == 1,
                "A new event should load the active method catalog");
        onEdt(() -> {
            require(!(methods.getSelectedItem() instanceof CaptureMethodOption),
                    "A capture method must be optional and unselected for a new entry");
            require(!contains(methods, 7L), "Archived methods must not be offered for new entries");
            require(!panel.hasUnsavedChanges(), "Loading choices must leave a new draft clean");
            require(find(panel, "manageCaptureMethodsButton", JButton.class) == null,
                    "Capture forms must select methods without offering catalog management");
            select(methods, 2L);
            field(panel, "ringNumberField", JTextField.class).setText("TEST-METHOD");
            button(panel, "saveEventButton").doClick();
        });
        await(() -> saved.get() == 101L, "Saving an event should complete");
        require(events.inserted.get() != null && Long.valueOf(2L).equals(events.inserted.get().captureMethodId()),
                "The chosen catalog identity must reach the saved event");
    }

    public static void editingPreservesAnArchivedMethodAndNewEntryClearsIt() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        catalog.put(new CaptureMethodOption(7L, "MÉTODO ANTIGUO", false));
        SavedEvents events = new SavedEvents();
        AtomicLong saved = new AtomicLong();
        CapturePanel panel = onEdt(() -> new CapturePanel(events, catalog, saved::set, () -> { }));
        onEdt(() -> panel.edit(detail(7L, "MÉTODO ANTIGUO")));
        JComboBox<?> methods = field(panel, "captureMethodField", JComboBox.class);
        await(() -> selectedId(methods) == 7L && contains(methods, 1L),
                "Editing must retain the event's archived method alongside active choices");
        onEdt(() -> {
            require(!panel.hasUnsavedChanges(), "An archived fallback must not mark the loaded event as edited");
            require(find(panel, "manageCaptureMethodsButton", JButton.class) == null,
                    "Editing an event must not expose capture-method catalog management");
            select(methods, 2L);
        });
        catalog.put(new CaptureMethodOption(9L, "MÉTODO NUEVO", true));
        onEdt(panel::refreshCaptureMethods);
        await(() -> contains(methods, 9L), "The method catalog should refresh while another method is selected");
        onEdt(() -> {
            require(selectedId(methods) == 2L && contains(methods, 7L),
                    "A refresh must retain the original archived choice without undoing the active selection");
            select(methods, 7L);
            button(panel, "saveEventButton").doClick();
        });
        await(() -> saved.get() == 31L, "Saving the historical event should finish");
        require(events.updated.get() != null && Long.valueOf(7L).equals(events.updated.get().captureMethodId()),
                "Saving an unrelated edit must preserve the archived method identity");
        onEdt(panel::prepareNew);
        await(() -> contains(methods, 1L) && !contains(methods, 7L),
                "Starting a new event must remove the historical fallback");
        onEdt(() -> require(!(methods.getSelectedItem() instanceof CaptureMethodOption),
                "Starting a new event must not inherit its predecessor's method"));
    }

    public static void refreshPreservesSelectionAndUnrelatedDraftChanges() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        CapturePanel panel = onEdt(() -> new CapturePanel(new SavedEvents(), catalog, ignored -> { }, () -> { }));
        onEdt(() -> panel.edit(detail(1L, "FILATS")));
        JComboBox<?> methods = field(panel, "captureMethodField", JComboBox.class);
        await(() -> selectedId(methods) == 1L && contains(methods, 2L),
                "The event's active method should load");
        catalog.saveCaptureMethod(1L, "FILATS RENOMBRADO");
        onEdt(panel::refreshCaptureMethods);
        await(() -> selectedName(methods).equals("FILATS RENOMBRADO"),
                "A catalog rename should update the selected label without losing identity");
        onEdt(() -> require(!panel.hasUnsavedChanges(),
                "Refreshing a renamed option must not create a false event edit"));

        onEdt(() -> field(panel, "observationsField", JTextArea.class).setText("Notas sin guardar"));
        catalog.saveCaptureMethod(1L, "FILATS REVISADO");
        onEdt(panel::refreshCaptureMethods);
        await(() -> selectedName(methods).equals("FILATS REVISADO"),
                "Method options should refresh while the event contains a draft");
        onEdt(() -> require(panel.hasUnsavedChanges()
                        && "Notas sin guardar".equals(field(panel, "observationsField", JTextArea.class).getText()),
                "Refreshing method choices must preserve unrelated unsaved notes"));

        LoadGate gate = new LoadGate();
        catalog.nextLoad.set(gate);
        catalog.put(new CaptureMethodOption(9L, "MÉTODO NUEVO", true));
        try {
            onEdt(panel::refreshCaptureMethods);
            require(gate.entered.await(5, TimeUnit.SECONDS), "The delayed method refresh should start");
            onEdt(() -> select(methods, 2L));
        } finally {
            gate.release.countDown();
        }
        await(() -> contains(methods, 9L), "The delayed catalog result should reach the form");
        onEdt(() -> require(selectedId(methods) == 2L && panel.hasUnsavedChanges(),
                "A delayed refresh must preserve a method chosen while it was loading"));
    }

    private static BirdEventDetail detail(Long methodId, String method) {
        return new BirdEventDetail(
                31L, 11L, "V6494", "Petirrojo europeo", EventType.RECOVERY,
                "2026-03-01", null, "Bosque antiguo", "Pollença", null,
                "RECAPTURE", "F", "6", null, null, "AR", "PENDING",
                null, null, null, "DEAD", null, null, null, null, 18.2,
                null, null, null, null, 39.85, 2.98, "Nota original", false,
                "REVIEW", "Pendiente de comprobar", List.of(), 1L, null,
                methodId, method
        );
    }

    private static JButton button(Component root, String name) {
        return field(root, name, JButton.class);
    }

    private static <T extends Component> T field(Component root, String name, Class<T> type) {
        T result = find(root, name, type);
        require(result != null, "Missing UI control: " + name);
        return result;
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                T result = find(child, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static boolean hasCard(Component root, long id) {
        return find(root, "captureMethodCard-" + id, JPanel.class) != null;
    }

    private static boolean cardHasText(Component root, long id, String text) {
        JPanel card = find(root, "captureMethodCard-" + id, JPanel.class);
        return card != null && containsText(card, text);
    }

    private static boolean containsText(Component root, String text) {
        if (root instanceof JLabel label && text.equals(label.getText())) return true;
        if (root instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                if (containsText(child, text)) return true;
            }
        }
        return false;
    }

    private static boolean contains(JComboBox<?> combo, long id) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index) instanceof CaptureMethodOption method && method.id() == id) return true;
        }
        return false;
    }

    private static void select(JComboBox<?> combo, long id) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index) instanceof CaptureMethodOption method && method.id() == id) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        throw new AssertionError("Missing capture method: " + id);
    }

    private static long selectedId(JComboBox<?> combo) {
        return combo.getSelectedItem() instanceof CaptureMethodOption method ? method.id() : -1L;
    }

    private static String selectedName(JComboBox<?> combo) {
        return combo.getSelectedItem() instanceof CaptureMethodOption method ? method.name() : "";
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(message);
    }

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static void onEdt(Runnable action) throws Exception {
        onEdt(() -> { action.run(); return null; });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class LoadGate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class MemoryCatalog extends CatalogRepository {
        private final Map<Long, CaptureMethodOption> methods = new LinkedHashMap<>();
        private final AtomicReference<LoadGate> nextLoad = new AtomicReference<>();
        private long nextId = 3L;

        private MemoryCatalog() {
            super("unused.db");
            put(new CaptureMethodOption(1L, "FILATS", true));
            put(new CaptureMethodOption(2L, "RED JAPONESA", true));
        }

        private synchronized void put(CaptureMethodOption method) {
            methods.put(method.id(), method);
            nextId = Math.max(nextId, method.id() + 1L);
        }

        private synchronized long idFor(String name) {
            return methods.values().stream().filter(method -> method.name().equals(name))
                    .findFirst().orElseThrow().id();
        }

        @Override
        public List<SpeciesOption> findSpeciesOptions() {
            return List.of(new SpeciesOption(1L, "Petirrojo europeo"));
        }

        @Override
        public List<PlaceSummary> findPlaceSummaries() {
            return List.of();
        }

        @Override
        public List<CaptureMethodOption> findCaptureMethods() {
            List<CaptureMethodOption> options;
            synchronized (this) {
                options = List.copyOf(methods.values());
            }
            LoadGate gate = nextLoad.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Catalog refresh timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return options;
        }

        @Override
        public List<CaptureMethodOption> findCaptureMethodOptions() {
            return findCaptureMethods().stream().filter(CaptureMethodOption::active).toList();
        }

        @Override
        public synchronized long saveCaptureMethod(Long id, String name) {
            String normalized = name == null ? "" : name.trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("Indica un nombre.");
            boolean duplicate = methods.values().stream().anyMatch(method ->
                    !Long.valueOf(method.id()).equals(id)
                            && method.name().toUpperCase(Locale.ROOT).equals(normalized.toUpperCase(Locale.ROOT)));
            if (duplicate) throw new IllegalArgumentException("Nombre duplicado.");
            long savedId = id == null ? nextId++ : id;
            CaptureMethodOption original = methods.get(savedId);
            methods.put(savedId, new CaptureMethodOption(savedId, normalized, original == null || original.active()));
            return savedId;
        }

        @Override
        public synchronized void setCaptureMethodActive(long id, boolean active) {
            CaptureMethodOption original = methods.get(id);
            require(original != null, "The method must exist before its active state changes");
            methods.put(id, new CaptureMethodOption(id, original.name(), active));
        }
    }

    private static final class SavedEvents extends BirdEventRepository {
        private final AtomicReference<BirdEventInput> inserted = new AtomicReference<>();
        private final AtomicReference<BirdEventInput> updated = new AtomicReference<>();

        private SavedEvents() {
            super("unused.db");
        }

        @Override
        public Optional<BirdLookup> findBirdByRingNumber(String ring) {
            return Optional.empty();
        }

        @Override
        public long insert(BirdEventInput input) {
            inserted.set(input);
            return 101L;
        }

        @Override
        public void update(long id, BirdEventInput input) {
            require(id == 31L, "Editing must update the original event");
            updated.set(input);
        }
    }

    public static void main(String[] args) throws Exception {
        managerAddsRenamesArchivesAndRestoresMethods();
        managerPreservesFailedDraftAndRespectsMutationLock();
        newEntriesOfferActiveMethodsAndSaveTheSelection();
        editingPreservesAnArchivedMethodAndNewEntryClearsIt();
        refreshPreservesSelectionAndUnrelatedDraftChanges();
        System.out.println("CaptureMethodUiTest: PASS");
    }
}

package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.CaptureMethodOption;
import com.adelylria.ringlog.model.view.EnvironmentOption;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.EnvironmentSelector;
import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.panels.CapturePanel;

public final class EnvironmentDropdownTest {
    private EnvironmentDropdownTest() { }

    private static void selectorsKeepHistoricalValuesAndUseOnlyActiveOrderedOptions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnvironmentSelector selector = new EnvironmentSelector(EnvironmentCategory.CLOUDS, "testClouds");
            selector.setOptions(options());
            require(!selector.isEditable() && selector.value().isEmpty(),
                    "New events must start unspecified and choose from the catalog");
            require(selector.getItemCount() == 5
                            && "0%".equals(selector.getItemAt(1).value())
                            && "75-100%".equals(selector.getItemAt(4).value()),
                    "Only cloud options in catalog order must be listed");
            selector.setValue("SI");
            require("SI".equals(selector.value()), "An old value must remain unchanged");
            JLabel rendered = (JLabel) selector.getRenderer().getListCellRendererComponent(
                    new JList<>(), (EnvironmentSelector.Choice) selector.getSelectedItem(), -1, false, false);
            require("SI (anterior)".equals(rendered.getText()),
                    "The previous value must be distinguishable from current catalog options");
            require(selector.selectValue("25-75%") && "25-75%".equals(selector.value()),
                    "One selection must replace old text with the exact current option");
            require(selector.selectValue("SI"), "The original value must be available to undo a draft choice");
            require(!selector.selectValue("A made-up value"), "The form must not create free-text catalog values");

            selector.setValue("0%");
            List<EnvironmentOption> revised = new ArrayList<>(options());
            revised.set(0, option(1, EnvironmentCategory.CLOUDS, "0%", false, 0));
            selector.setOptions(revised);
            require("0%".equals(selector.value())
                            && ((EnvironmentSelector.Choice) selector.getSelectedItem()).historical(),
                    "Deactivation must retain the current event's text");
            selector.setValue("");
            require(selector.value().isEmpty() && !selector.selectValue("0%") && !selector.selectValue("SI"),
                    "New events must not inherit old values or offer inactive options");
            selector.setOptions(options());
            require(selector.selectValue("0%"), "Reactivated options must become selectable again");
        });
    }

    private static void catalogRefreshPreservesDirtyDraftAndDoesNotCreateEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnvironmentSelector selector = new EnvironmentSelector(EnvironmentCategory.CLOUDS, "testClouds");
            JPanel form = new JPanel();
            form.add(selector);
            FormChanges changes = new FormChanges(form);
            selector.setValue("0%");
            changes.markClean();
            selector.setOptions(options());
            require(!changes.hasChanges(), "Resolving an old value into the catalog must not dirty the event");
            selector.selectValue("25-75%");
            require(changes.hasChanges(), "A user-selected replacement must be an unsaved edit");
            List<EnvironmentOption> renamed = new ArrayList<>(options());
            renamed.set(2, option(3, EnvironmentCategory.CLOUDS, "Bastantes nubes", true, 2));
            selector.setOptions(renamed);
            require("25-75%".equals(selector.value()) && changes.hasChanges(),
                    "Renaming an option must not overwrite an event or its unsaved draft");
        });
    }

    private static void unchangedCatalogRefreshDoesNotGenerateEventsOrRepaints() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel form = new JPanel();
            List<EnvironmentSelector> selectors = new ArrayList<>();
            List<ComboBoxModel<EnvironmentSelector.Choice>> models = new ArrayList<>();
            String[] historical = {"SI", "Fluixa", "M. Fred", "Fluix"};
            String[] replacements = {"25-75%", "Débil", "Templado", "Moderado"};
            for (EnvironmentCategory category : EnvironmentCategory.values()) {
                EnvironmentSelector selector = new EnvironmentSelector(category, "refresh-" + category.code());
                selector.setOptions(options());
                selector.setValue(historical[category.ordinal()]);
                selectors.add(selector);
                form.add(selector);
            }
            FormChanges changes = new FormChanges(form);
            changes.markClean();
            for (EnvironmentSelector selector : selectors) {
                int index = selectors.indexOf(selector);
                require(selector.selectValue(replacements[index]), "Choose a current Spanish replacement");
                models.add(selector.getModel());
            }
            require(changes.hasChanges(), "The replacement choices must be a dirty draft");
            AtomicInteger actionEvents = new AtomicInteger();
            AtomicInteger itemEvents = new AtomicInteger();
            AtomicInteger modelEvents = new AtomicInteger();
            AtomicInteger propertyEvents = new AtomicInteger();
            for (EnvironmentSelector selector : selectors) {
                selector.addActionListener(event -> actionEvents.incrementAndGet());
                selector.addItemListener(event -> itemEvents.incrementAndGet());
                selector.addPropertyChangeListener(event -> propertyEvents.incrementAndGet());
                selector.getModel().addListDataListener(new ListDataListener() {
                    public void contentsChanged(ListDataEvent event) { modelEvents.incrementAndGet(); }
                    public void intervalAdded(ListDataEvent event) { modelEvents.incrementAndGet(); }
                    public void intervalRemoved(ListDataEvent event) { modelEvents.incrementAndGet(); }
                });
            }
            AtomicInteger repaintRequests = new AtomicInteger();
            AtomicInteger layoutRequests = new AtomicInteger();
            RepaintManager previous = RepaintManager.currentManager(form);
            RepaintManager tracking = new RepaintManager() {
                @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
                    if (component == form || SwingUtilities.isDescendingFrom(component, form)) {
                        repaintRequests.incrementAndGet();
                    }
                    super.addDirtyRegion(component, x, y, width, height);
                }
                @Override public void addInvalidComponent(JComponent component) {
                    if (component == form || SwingUtilities.isDescendingFrom(component, form)) {
                        layoutRequests.incrementAndGet();
                    }
                    super.addInvalidComponent(component);
                }
            };
            RepaintManager.setCurrentManager(tracking);
            try {
                for (int repeat = 0; repeat < 100; repeat++) {
                    // A fresh list models an unchanged catalog returned by another async refresh.
                    List<EnvironmentOption> unchanged = new ArrayList<>(options());
                    for (EnvironmentSelector selector : selectors) selector.setOptions(unchanged);
                }
                require(actionEvents.get() == 0 && itemEvents.get() == 0 && modelEvents.get() == 0
                                && propertyEvents.get() == 0 && repaintRequests.get() == 0 && layoutRequests.get() == 0,
                        "100 unchanged refreshes must not rebuild models or trigger events, layout or repaint work");
                require(changes.hasChanges(), "Unchanged refreshes must preserve the unsaved draft");
                for (int index = 0; index < selectors.size(); index++) {
                    require(models.get(index) == selectors.get(index).getModel()
                                    && replacements[index].equals(selectors.get(index).value()),
                            "Unchanged refreshes must keep the selected value and its existing Swing model");
                }
            } finally { RepaintManager.setCurrentManager(previous); }
        });
    }

    private static void captureFormLoadsOnceOffEdtAndCanReplaceAllHistoricalWeather() throws Exception {
        Fixture fixture = new Fixture();
        CapturePanel panel = fixture.createPanel();
        waitFor(() -> selector(panel, "cloudsField").getItemCount() == 5);
        onEdt(() -> {
            require(fixture.loads.get() == 1, "A single query must load all four environment categories");
            require(!panel.hasUnsavedChanges(), "Loading a new form's catalog must not create edits");
            panel.edit(detail("SI"));
        });
        waitFor(() -> fixture.loads.get() == 2 && !panel.hasUnsavedChanges()
                && ((JComboBox<?>) find(panel, "speciesField")).getSelectedItem() != null);
        onEdt(() -> {
            require("SI".equals(selector(panel, "cloudsField").value()), "Old clouds must remain visible");
            require("Fluixa".equals(selector(panel, "rainField").value()), "Old Mallorcan rain must remain visible");
            require("Templat".equals(selector(panel, "thermalField").value()),
                    "An old Mallorcan thermal sensation must not be translated automatically");
            findButton(panel, "Guardar cambios").doClick();
        });
        waitFor(() -> fixture.saves.get() == 1);
        require("SI".equals(fixture.saved.get().clouds()) && "Fluixa".equals(fixture.saved.get().rain())
                        && "Templat".equals(fixture.saved.get().thermalSensation())
                        && "Moderat".equals(fixture.saved.get().wind()),
                "Saving unrelated changes must preserve every historical weather value exactly");
        onEdt(() -> {
            panel.edit(detail("SI"));
            selector(panel, "cloudsField").selectValue("25-75%");
            selector(panel, "rainField").selectValue("Débil");
            selector(panel, "thermalField").selectValue("Templado");
            selector(panel, "windField").selectValue("Moderado");
            require(panel.hasUnsavedChanges(), "Replacing old weather must count as an edit");
            findButton(panel, "Guardar cambios").doClick();
        });
        waitFor(() -> fixture.saves.get() == 2);
        require("25-75%".equals(fixture.saved.get().clouds()) && "Débil".equals(fixture.saved.get().rain())
                        && "Templado".equals(fixture.saved.get().thermalSensation())
                        && "Moderado".equals(fixture.saved.get().wind()),
                "Editing an old event must save all four new catalog values without presentation suffixes");
    }

    private static void slowOrFailedRefreshNeverOverwritesNewerEventsOrDrafts() throws Exception {
        Fixture fixture = new Fixture();
        CapturePanel panel = fixture.createPanel();
        waitFor(() -> selector(panel, "cloudsField").getItemCount() == 5);
        BlockingLoad slowLoad = new BlockingLoad();
        fixture.blocking.set(slowLoad);
        onEdt(panel::refreshEnvironmentOptions);
        require(slowLoad.started.await(3, TimeUnit.SECONDS), "The delayed refresh should have started");
        List<EnvironmentOption> updated = new ArrayList<>(options());
        updated.add(option(30, EnvironmentCategory.CLOUDS, "Nubes altas", true, 4));
        fixture.options.set(updated);
        onEdt(() -> panel.edit(detail("Medio")));
        waitFor(() -> selector(panel, "cloudsField").selectValue("Nubes altas"));
        onEdt(() -> selector(panel, "cloudsField").selectValue("Medio"));
        slowLoad.release.countDown();
        require(slowLoad.finished.await(3, TimeUnit.SECONDS), "The delayed refresh should finish");
        Thread.sleep(80); // Allow SwingWorker.done to run after the stale result was released.
        onEdt(() -> {
            require(selector(panel, "cloudsField").selectValue("Nubes altas"),
                    "A stale response must not replace the newer event's catalog");
            selector(panel, "cloudsField").selectValue("Medio");
            selector(panel, "rainField").selectValue("Fuerte");
        });
        fixture.fail.set(true);
        onEdt(panel::refreshEnvironmentOptions);
        waitFor(() -> selector(panel, "cloudsField").getToolTipText().contains("no se ha podido")
                || selector(panel, "cloudsField").getToolTipText().contains("No se ha podido"));
        onEdt(() -> require("Medio".equals(selector(panel, "cloudsField").value())
                        && "Fuerte".equals(selector(panel, "rainField").value()) && panel.hasUnsavedChanges(),
                "A failed query must preserve both historical values and unsaved replacement choices"));
    }

    private static List<EnvironmentOption> options() {
        List<EnvironmentOption> result = new ArrayList<>();
        String[][] names = {{"0%", "1-25%", "25-75%", "75-100%"},
                {"Nula", "Débil", "Moderada", "Fuerte"},
                {"Mucho frío", "Frío", "Templado", "Calor", "Mucho calor"},
                {"Nulo", "Débil", "Moderado", "Fuerte", "Muy fuerte"}};
        long id = 1;
        for (EnvironmentCategory category : EnvironmentCategory.values()) {
            for (int index = 0; index < names[category.ordinal()].length; index++) {
                result.add(option(id++, category, names[category.ordinal()][index], true, index));
            }
        }
        return result;
    }

    private static EnvironmentOption option(long id, EnvironmentCategory category, String name,
            boolean active, int sortOrder) {
        return new EnvironmentOption(id, "environment-" + id, category, name, active, sortOrder);
    }

    private static BirdEventDetail detail(String clouds) {
        return new BirdEventDetail(31L, 11L, "V6494", "Petirrojo europeo", EventType.RECOVERY,
                "2026-03-01", null, "Bosque antiguo", "Pollença", null,
                "RECAPTURE", "F", "6", null, null, "AR", "PENDING",
                null, null, null, "DEAD", null, null, null, null, 18.2,
                clouds, "Fluixa", "Templat", "Moderat", 39.85, 2.98, "Nota original", false,
                "REVIEW", "Pendiente de comprobar", List.of(), 91L, 92L);
    }

    private static final class BlockingLoad {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
    }

    private static final class Fixture {
        final AtomicReference<List<EnvironmentOption>> options = new AtomicReference<>(options());
        final AtomicReference<BlockingLoad> blocking = new AtomicReference<>();
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicReference<BirdEventInput> saved = new AtomicReference<>();
        CapturePanel createPanel() throws Exception {
            CatalogRepository catalog = new CatalogRepository("unused.db") {
                @Override public List<SpeciesOption> findSpeciesOptions() { return List.of(); }
                @Override public List<PlaceSummary> findPlaceSummaries() { return List.of(); }
                @Override public List<CaptureMethodOption> findCaptureMethods() { return List.of(); }
                @Override public List<EnvironmentOption> findEnvironmentOptions() {
                    require(!SwingUtilities.isEventDispatchThread(), "Environment queries must run outside Swing's EDT");
                    loads.incrementAndGet();
                    List<EnvironmentOption> result = options.get();
                    BlockingLoad slow = blocking.getAndSet(null);
                    if (slow != null) {
                        slow.started.countDown();
                        try {
                            require(slow.release.await(3, TimeUnit.SECONDS), "Release the delayed environment query");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        } finally { slow.finished.countDown(); }
                    }
                    if (fail.get()) throw new IllegalStateException("Synthetic catalog failure");
                    return result;
                }
            };
            BirdEventRepository events = new BirdEventRepository("unused.db") {
                @Override public void update(long id, BirdEventInput input) {
                    require(id == 31L, "The old event must be updated in place");
                    saved.set(input);
                }
                @Override public long insert(BirdEventInput input) {
                    throw new AssertionError("Editing old weather must not duplicate the event");
                }
            };
            AtomicReference<CapturePanel> holder = new AtomicReference<>();
            onEdt(() -> holder.set(new CapturePanel(events, catalog, ignored -> saves.incrementAndGet(), () -> { })));
            return holder.get();
        }
    }

    private static EnvironmentSelector selector(CapturePanel panel, String name) {
        return (EnvironmentSelector) find(panel, name);
    }

    private static Component find(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            Component found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) return button;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            JButton found = findButton(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            AtomicBoolean complete = new AtomicBoolean();
            onEdt(() -> complete.set(condition.getAsBoolean()));
            if (complete.get()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for the environment form");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        selectorsKeepHistoricalValuesAndUseOnlyActiveOrderedOptions();
        catalogRefreshPreservesDirtyDraftAndDoesNotCreateEdits();
        unchangedCatalogRefreshDoesNotGenerateEventsOrRepaints();
        captureFormLoadsOnceOffEdtAndCanReplaceAllHistoricalWeather();
        slowOrFailedRefreshNeverOverwritesNewerEventsOrDrafts();
        System.out.println("EnvironmentDropdownTest: PASS");
    }
}

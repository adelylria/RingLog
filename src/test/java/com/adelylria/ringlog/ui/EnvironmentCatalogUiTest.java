package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.view.EnvironmentOption;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.panels.EnvironmentPanel;

public final class EnvironmentCatalogUiTest {
    private EnvironmentCatalogUiTest() { }

    private static void managesCategoriesAndOptionsWithoutChangingHistory() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        AtomicInteger changed = new AtomicInteger();
        EnvironmentPanel panel = edt(() -> new EnvironmentPanel(catalog, true, changed::incrementAndGet));
        edt(() -> { panel.refresh(); return null; });
        await(() -> find(panel, "environmentOptionCard-1", JPanel.class) != null);
        require(edt(() -> find(panel, "environmentOptionCard-3", JPanel.class)) == null,
                "Categories must show only their own options");
        edt(() -> {
            require(!button(panel, "moveEnvironmentOptionUp-1").isEnabled(), "First row cannot move up");
            require(!button(panel, "moveEnvironmentOptionDown-2").isEnabled(), "Last row cannot move down");
            button(panel, "moveEnvironmentOptionUp-2").doClick();
            return null;
        });
        await(() -> changed.get() == 1 && button(panel, "newEnvironmentOptionButton").isEnabled()
                && !button(panel, "moveEnvironmentOptionUp-2").isEnabled());
        require(catalog.snapshot().get(0).id() == 2, "Ordering must persist through the repository");
        edt(() -> {
            find(panel, "environmentCategorySelector", JComboBox.class).setSelectedItem(EnvironmentCategory.RAIN);
            button(panel, "newEnvironmentOptionButton").doClick();
            find(panel, "environmentOptionName", JTextField.class).setText("Torrencial");
            button(panel, "saveEnvironmentOptionButton").doClick();
            return null;
        });
        await(() -> changed.get() == 2 && find(panel, "environmentOptionCard-4", JPanel.class) != null);
        require(catalog.snapshot().stream().anyMatch(option -> option.id() == 4
                        && option.category() == EnvironmentCategory.RAIN && option.name().equals("Torrencial")),
                "New options must belong to the selected category");
        edt(() -> {
            button(panel, "editEnvironmentOption-4").doClick();
            find(panel, "environmentOptionName", JTextField.class).setText("Intensa");
            button(panel, "saveEnvironmentOptionButton").doClick();
            return null;
        });
        await(() -> changed.get() == 3 && find(panel, "environmentOptionCard-4", JPanel.class) != null
                && !find(panel, "environmentOptionEditorView", JPanel.class).isVisible()
                && button(panel, "newEnvironmentOptionButton").isEnabled());
        require(catalog.snapshot().stream().anyMatch(option -> option.id() == 4 && option.name().equals("Intensa")),
                "Editing must update the same option");
        edt(() -> { button(panel, "toggleEnvironmentOptionActive-4").doClick(); return null; });
        await(() -> changed.get() == 4 && button(panel, "toggleEnvironmentOptionActive-4").getText().equals("Activar"));
        require(catalog.snapshot().stream().anyMatch(option -> option.id() == 4 && !option.active()),
                "Removing a choice must deactivate it instead of deleting its identity");
        edt(() -> { button(panel, "toggleEnvironmentOptionActive-4").doClick(); return null; });
        await(() -> changed.get() == 5 && button(panel, "toggleEnvironmentOptionActive-4").getText().equals("Desactivar"));
    }

    private static void protectsDraftsAndReadOnlyCatalogs() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        EnvironmentPanel panel = edt(() -> new EnvironmentPanel(catalog, true, () -> { }));
        edt(() -> { panel.refresh(); return null; });
        await(() -> find(panel, "environmentOptionCard-1", JPanel.class) != null);
        edt(() -> {
            button(panel, "newEnvironmentOptionButton").doClick();
            find(panel, "environmentOptionName", JTextField.class).setText("0%");
            button(panel, "saveEnvironmentOptionButton").doClick();
            return null;
        });
        await(() -> button(panel, "saveEnvironmentOptionButton").isEnabled()
                && find(panel, "environmentOptionStatus", JLabel.class).getText().contains("duplicado"));
        edt(() -> {
            require(find(panel, "environmentOptionName", JTextField.class).getText().equals("0%"),
                    "Failed saves must retain the draft");
            panel.setDiscardConfirmation(pane -> false);
            require(!panel.confirmLeave(), "Unsaved changes need discard protection");
            panel.refresh();
            require(find(panel, "environmentOptionEditorView", JPanel.class).isVisible(),
                    "Declining discard must preserve the editor");
            panel.setMutationActionsEnabled(false);
            require(!button(panel, "saveEnvironmentOptionButton").isEnabled()
                    && !button(panel, "newEnvironmentOptionButton").isEnabled(),
                    "Snapshots must lock catalog mutations");
            panel.setMutationActionsEnabled(true);
            panel.setDiscardConfirmation(pane -> true);
            button(panel, "cancelEnvironmentOptionEditorButton").doClick();
            require(!panel.hasUnsavedChanges(), "Cancelling confirmed edits must clear the draft");
            return null;
        });
        EnvironmentPanel readOnly = edt(() -> new EnvironmentPanel(catalog, false, () -> { }));
        edt(() -> { readOnly.refresh(); return null; });
        await(() -> find(readOnly, "environmentOptionCard-1", JPanel.class) != null);
        edt(() -> {
            require(find(readOnly, "newEnvironmentOptionButton", JButton.class) == null
                    && find(readOnly, "editEnvironmentOption-1", JButton.class) == null
                    && find(readOnly, "toggleEnvironmentOptionActive-1", JButton.class) == null,
                    "Portable catalogs must expose no mutation controls");
            return null;
        });
    }

    private static void slowOrFailedReloadCannotOfferStaleMutations() throws Exception {
        MemoryCatalog catalog = new MemoryCatalog();
        AtomicInteger changed = new AtomicInteger();
        EnvironmentPanel panel = edt(() -> new EnvironmentPanel(catalog, true, changed::incrementAndGet));
        edt(() -> { panel.refresh(); return null; });
        await(() -> find(panel, "environmentOptionCard-1", JPanel.class) != null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        catalog.readEntered = entered;
        catalog.readRelease = release;
        try {
            edt(() -> { button(panel, "toggleEnvironmentOptionActive-1").doClick(); return null; });
            require(entered.await(5, TimeUnit.SECONDS), "Post-save catalog read must start in the background");
            edt(() -> {
                require(!button(panel, "newEnvironmentOptionButton").isEnabled()
                        && !button(panel, "toggleEnvironmentOptionActive-1").isEnabled()
                        && !button(panel, "editEnvironmentOption-1").isEnabled()
                        && !button(panel, "moveEnvironmentOptionDown-1").isEnabled(),
                        "A slow reload must block mutations on outdated cards while the EDT remains responsive");
                button(panel, "toggleEnvironmentOptionActive-1").doClick();
                return null;
            });
        } finally { release.countDown(); }
        await(() -> button(panel, "toggleEnvironmentOptionActive-1").isEnabled()
                && button(panel, "toggleEnvironmentOptionActive-1").getText().equals("Activar"));
        require(changed.get() == 1, "A second click during reload must not submit an outdated mutation");
        catalog.failNextRead = true;
        edt(() -> { button(panel, "toggleEnvironmentOptionActive-1").doClick(); return null; });
        await(() -> find(panel, "environmentCatalogStatus", JLabel.class).getText().contains("No se han podido"));
        edt(() -> {
            require(find(panel, "editEnvironmentOption-1", JButton.class) == null,
                    "A failed post-save read must clear obsolete cards instead of allowing stale edits");
            panel.refresh();
            return null;
        });
        await(() -> find(panel, "environmentOptionCard-1", JPanel.class) != null);
    }

    private static final class MemoryCatalog extends CatalogRepository {
        volatile CountDownLatch readEntered;
        volatile CountDownLatch readRelease;
        volatile boolean failNextRead;
        private final List<EnvironmentOption> options = new ArrayList<>(List.of(
                option(1, EnvironmentCategory.CLOUDS, "0%", true, 0),
                option(2, EnvironmentCategory.CLOUDS, "1-25%", true, 1),
                option(3, EnvironmentCategory.RAIN, "Nula", true, 0)));
        synchronized List<EnvironmentOption> snapshot() {
            return options.stream().sorted(Comparator.comparing(EnvironmentOption::category)
                    .thenComparingInt(EnvironmentOption::sortOrder)).toList();
        }
        @Override public synchronized List<EnvironmentOption> findEnvironmentOptions() {
            require(!SwingUtilities.isEventDispatchThread(), "Catalog loading must run outside the EDT");
            CountDownLatch entered = readEntered, release = readRelease;
            if (entered != null && release != null) {
                readEntered = null;
                readRelease = null;
                entered.countDown();
                try {
                    require(release.await(5, TimeUnit.SECONDS), "Slow read must eventually be released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            if (failNextRead) {
                failNextRead = false;
                throw new IllegalStateException("Synthetic read failure");
            }
            return snapshot();
        }
        @Override public synchronized long saveEnvironmentOption(Long id, EnvironmentCategory category, String name) {
            require(!SwingUtilities.isEventDispatchThread(), "Saving must run outside the EDT");
            if (options.stream().anyMatch(option -> option.category() == category
                    && option.name().equalsIgnoreCase(name) && (id == null || option.id() != id))) {
                throw new IllegalArgumentException("Nombre duplicado.");
            }
            long actualId = id == null ? options.size() + 1L : id;
            EnvironmentOption previous = options.stream().filter(option -> option.id() == actualId).findFirst().orElse(null);
            options.removeIf(option -> option.id() == actualId);
            options.add(option(actualId, category, name, previous == null || previous.active(),
                    previous == null ? 99 : previous.sortOrder()));
            return actualId;
        }
        @Override public synchronized void setEnvironmentOptionActive(long id, boolean active) {
            require(!SwingUtilities.isEventDispatchThread(), "Activation must run outside the EDT");
            options.replaceAll(option -> option.id() == id
                    ? option(id, option.category(), option.name(), active, option.sortOrder()) : option);
        }
        @Override public synchronized void moveEnvironmentOption(long id, int direction) {
            require(!SwingUtilities.isEventDispatchThread(), "Ordering must run outside the EDT");
            EnvironmentOption current = options.stream().filter(option -> option.id() == id).findFirst().orElseThrow();
            List<EnvironmentOption> group = snapshot().stream().filter(option -> option.category() == current.category()).toList();
            int destination = group.indexOf(current) + direction;
            if (destination < 0 || destination >= group.size()) return;
            EnvironmentOption other = group.get(destination);
            options.replaceAll(option -> option.id() == id
                    ? option(id, option.category(), option.name(), option.active(), other.sortOrder())
                    : option.id() == other.id()
                    ? option(other.id(), option.category(), option.name(), option.active(), current.sortOrder()) : option);
        }
    }

    private static EnvironmentOption option(long id, EnvironmentCategory category, String name, boolean active, int order) {
        return new EnvironmentOption(id, "test-environment-" + id, category, name, active, order);
    }
    private static JButton button(Component root, String name) {
        JButton result = find(root, name, JButton.class);
        if (result == null) throw new AssertionError("Missing button " + name);
        return result;
    }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            T result = find(child, name, type);
            if (result != null) return result;
        }
        return null;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (edt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for environment catalog UI");
    }
    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(action.get()));
        return value.get();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        managesCategoriesAndOptionsWithoutChangingHistory();
        protectsDraftsAndReadOnlyCatalogs();
        slowOrFailedReloadCannotOfferStaleMutations();
        System.out.println("EnvironmentCatalogUiTest: PASS");
    }
}

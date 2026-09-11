package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.panels.CapturePanel;
import com.adelylria.ringlog.ui.panels.PlacePanel;
import com.adelylria.ringlog.ui.panels.SpeciesPanel;

public final class CatalogRefreshTest {

    private CatalogRefreshTest() {
    }

    public static void refreshingCatalogsAddsOptionsWithoutDuplicates()
            throws Exception {
        SpeciesOption originalSpecies = new SpeciesOption(1L, "Mirlo común");
        SpeciesOption newSpecies = new SpeciesOption(2L, "Petirrojo europeo");
        PlaceSummary originalPlace = place(1L, "ELS RAFALS");
        PlaceSummary newPlace = place(2L, "CUIXAC");
        AtomicReference<List<SpeciesOption>> species = new AtomicReference<>(
                List.of(originalSpecies)
        );
        AtomicReference<List<PlaceSummary>> places = new AtomicReference<>(
                List.of(originalPlace)
        );
        CatalogRepository repository = new CatalogRepository("unused.db") {
            @Override
            public List<SpeciesOption> findSpeciesOptions() {
                return species.get();
            }

            @Override
            public List<PlaceSummary> findPlaceSummaries() {
                return places.get();
            }
        };
        CapturePanel panel = onEdt(() -> new CapturePanel(
                new BirdEventRepository("unused.db"),
                repository,
                ignored -> { },
                () -> { }
        ));
        waitForOption(panel, originalSpecies);
        waitForOption(panel, originalPlace);

        species.set(List.of(originalSpecies, newSpecies));
        places.set(List.of(originalPlace, newPlace));
        Method refresh;
        try {
            refresh = CapturePanel.class.getMethod("refreshCatalogs");
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                    "Nuevo registro debe poder recargar especies y lugares",
                    exception
            );
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                refresh.invoke(panel);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        });

        waitForOption(panel, newSpecies);
        waitForOption(panel, newPlace);
        require(countOption(panel, originalSpecies) == 1,
                "La recarga no debe duplicar especies existentes");
        require(countOption(panel, originalPlace) == 1,
                "La recarga no debe duplicar lugares existentes");
    }

    public static void placeCatalogOffersEditingOnlyWhenDataCanBeChanged()
            throws Exception {
        PlaceSummary place = new PlaceSummary(
                9L, "ELS RAFALS", "POLLENÇA", "Illes Balears", "España",
                39.85, 2.98, null, false, false, 4L
        );
        CatalogRepository repository = new CatalogRepository("unused.db") {
            @Override
            public List<PlaceSummary> findPlaceSummaries() {
                return List.of(place);
            }
        };

        PlacePanel editable = onEdt(() -> new PlacePanel(repository, true));
        SwingUtilities.invokeAndWait(editable::refresh);
        JButton edit = waitForButton(editable, "editPlace-9");
        require(edit.isEnabled(), "A normal place catalog must offer an edit action");
        require(edit.getText() == null || edit.getText().isBlank(),
                "Place cards should use a discreet icon instead of a large edit pill");
        SwingUtilities.invokeAndWait(edit::doClick);
        waitForVisibleComponent(editable, "placeEditorView");
        JTextField placeName = (JTextField) find(editable, "placeName");
        require(placeName != null && "ELS RAFALS".equals(placeName.getText()),
                "Place editing must open inline and preload the selected place");
        JButton cancel = waitForButton(editable, "cancelPlaceEditorButton");
        SwingUtilities.invokeAndWait(cancel::doClick);
        waitForHiddenComponent(editable, "placeEditorView");
        SwingUtilities.invokeAndWait(() -> editable.setMutationActionsEnabled(false));
        require(!edit.isEnabled(), "Place editing must follow the global mutation lock");

        PlacePanel readOnly = onEdt(() -> new PlacePanel(repository, false));
        SwingUtilities.invokeAndWait(readOnly::refresh);
        waitForText(readOnly, "ELS RAFALS");
        require(find(readOnly, "editPlace-9") == null,
                "A portable read-only catalog must not expose place editing");
    }

    public static void speciesCatalogEditsInlineOnlyWhenDataCanBeChanged()
            throws Exception {
        SpeciesSummary species = new SpeciesSummary(
                12L,
                "Zorzal común",
                "TUR-PHI",
                "Turdus philomelos",
                "Zorzal común",
                18L
        );
        CatalogRepository repository = new CatalogRepository("unused.db") {
            @Override
            public List<SpeciesSummary> findSpeciesSummaries() {
                return List.of(species);
            }
        };

        SpeciesPanel editable = onEdt(() -> new SpeciesPanel(repository, true));
        SwingUtilities.invokeAndWait(editable::refresh);
        JButton edit = waitForButton(editable, "editSpecies-12");
        require(edit.getText() == null || edit.getText().isBlank(),
                "Species cards should use a discreet icon instead of a large edit pill");
        SwingUtilities.invokeAndWait(edit::doClick);
        waitForVisibleComponent(editable, "speciesEditorView");
        JTextField scientificName = (JTextField) find(
                editable, "speciesScientificName"
        );
        require(scientificName != null
                        && "Turdus philomelos".equals(scientificName.getText()),
                "Species editing must open inline and preload the selected species");
        JButton cancel = waitForButton(editable, "cancelSpeciesEditorButton");
        SwingUtilities.invokeAndWait(cancel::doClick);
        waitForHiddenComponent(editable, "speciesEditorView");

        SpeciesPanel readOnly = onEdt(() -> new SpeciesPanel(repository, false));
        SwingUtilities.invokeAndWait(readOnly::refresh);
        waitForText(readOnly, "Zorzal común");
        require(find(readOnly, "editSpecies-12") == null,
                "A portable read-only catalog must not expose species editing");
    }

    private static void waitForVisibleComponent(Component root, String name)
            throws Exception {
        waitForComponentVisibility(root, name, true);
    }

    private static void waitForHiddenComponent(Component root, String name)
            throws Exception {
        waitForComponentVisibility(root, name, false);
    }

    private static void waitForComponentVisibility(
            Component root,
            String name,
            boolean visible
    ) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            Component found = onEdt(() -> find(root, name));
            if (found != null && found.isVisible() == visible) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "El componente " + name + " no cambió su visibilidad a " + visible
        );
    }

    private static void waitForText(Component root, String text) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            if (onEdt(() -> containsText(root, text))) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No apareció el texto " + text);
    }

    private static boolean containsText(Component component, String text) {
        if (component instanceof javax.swing.JLabel label && text.equals(label.getText())) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JButton waitForButton(Component root, String name) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            Component found = onEdt(() -> find(root, name));
            if (found instanceof JButton button) {
                return button;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No apareció el botón " + name);
    }

    private static Component find(Component component, String name) {
        if (component instanceof javax.swing.JComponent swing
                && name.equals(swing.getName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = find(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static PlaceSummary place(long id, String name) {
        return new PlaceSummary(
                id,
                name,
                "POLLENSA",
                null,
                null,
                null,
                false,
                false,
                0L
        );
    }

    private static void waitForOption(Component panel, Object expected)
            throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            if (onEdt(() -> countOption(panel, expected)) > 0) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No apareció la opción de catálogo: " + expected);
    }

    private static int countOption(Component root, Object expected) {
        int count = 0;
        for (JComboBox<?> combo : descendants(root, JComboBox.class)) {
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (expected.equals(combo.getItemAt(index))) {
                    count++;
                }
            }
        }
        return count;
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

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        refreshingCatalogsAddsOptionsWithoutDuplicates();
        placeCatalogOffersEditingOnlyWhenDataCanBeChanged();
        speciesCatalogEditsInlineOnlyWhenDataCanBeChanged();
        System.out.println("CatalogRefreshTest: PASS");
    }
}

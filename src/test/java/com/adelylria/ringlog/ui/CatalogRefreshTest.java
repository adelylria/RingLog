package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.panels.CapturePanel;

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
        System.out.println("CatalogRefreshTest: PASS");
    }
}

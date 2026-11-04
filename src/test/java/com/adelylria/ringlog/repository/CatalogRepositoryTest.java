package com.adelylria.ringlog.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesSummary;

public final class CatalogRepositoryTest {

    private CatalogRepositoryTest() {
    }

    public static void placesCanBeCreatedAndEditedWithoutChangingTheirIdentity()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-place-edit-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long placeId = repository.insertPlace(new PlaceInput(
                    "Els Rafals", "Pollença", "Illes Balears", "España",
                    39.8502, 2.9850, "Acceso por el camino principal", true, true
            ));
            String stableKey = stableKey(database, placeId);

            repository.updatePlace(placeId, new PlaceInput(
                    "Els Rafals", "Pollença", "Illes Balears", "España",
                    39.8510, 2.9860, "Acceso actualizado", false, true
            ));

            PlaceSummary place = repository.findPlaceSummaries().stream()
                    .filter(item -> item.id() == placeId)
                    .findFirst()
                    .orElseThrow();
            require("Illes Balears".equals(place.autonomousCommunity()),
                    "The autonomous community must be persisted");
            require("España".equals(place.country()), "The country must be persisted");
            require(Double.valueOf(39.8510).equals(place.latitude())
                            && "Acceso actualizado".equals(place.notes()),
                    "Editing must persist all mutable place fields");
            require(!place.favorite() && place.isDefault(),
                    "Editing must preserve the chosen flags");
            require(stableKey.equals(stableKey(database, placeId)),
                    "Editing a place must never change its stable identity");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void changingTheDefaultPlaceKeepsOnlyOneDefault() throws Exception {
        Path root = Files.createTempDirectory("ringlog-place-default-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long first = repository.insertPlace(new PlaceInput(
                    "Primer lugar", null, null, null, null, false, true
            ));
            repository.insertPlace(new PlaceInput(
                    "Segundo lugar", null, null, null, null, false, true
            ));
            repository.updatePlace(first, new PlaceInput(
                    "Primer lugar", null, null, null, null, false, true
            ));

            List<PlaceSummary> places = repository.findPlaceSummaries();
            require(places.stream().filter(PlaceSummary::isDefault).count() == 1,
                    "There must never be more than one default place");
            require(places.stream().anyMatch(item -> item.id() == first && item.isDefault()),
                    "The edited place must become the new default");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void speciesCanBeEditedWithoutChangingTheirIdentity()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-species-edit-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long speciesId = repository.insertSpecies(new SpeciesInput(
                    "TUR-PHI", "Turdus philomelos", "Zorzal común"
            ));
            String stableKey = stableKey(database, "species", speciesId);

            repository.updateSpecies(speciesId, new SpeciesInput(
                    "TUR-PHI", "Turdus philomelos", "Zorzal común actualizado"
            ));

            SpeciesSummary species = repository.findSpeciesSummaries().stream()
                    .filter(item -> item.id() == speciesId)
                    .findFirst()
                    .orElseThrow();
            require("TUR-PHI".equals(species.code())
                            && "Turdus philomelos".equals(species.scientificName())
                            && "Zorzal común actualizado".equals(species.commonName()),
                    "Editing must persist every mutable species field");
            require("Zorzal común actualizado".equals(species.name()),
                    "The catalog must immediately use the updated display name");
            require(stableKey.equals(stableKey(database, "species", speciesId)),
                    "Editing a species must never change its stable identity");
        } finally {
            deleteDirectory(root);
        }
    }

    private static String stableKey(Path database, long placeId) throws Exception {
        return stableKey(database, "place", placeId);
    }

    public static void captureMethodsAreReusableAndPreserveHistoricalEvents() throws Exception {
        Path root = Files.createTempDirectory("ringlog-capture-method-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository catalog = new CatalogRepository(database.toString());
            require(catalog.findCaptureMethodOptions().stream().map(item -> item.name()).toList()
                            .equals(List.of("FILATS", "RED JAPONESA")),
                    "The capture method catalog must start with the requested methods");
            long method = catalog.saveCaptureMethod(null, "  Lazo   manual  ");
            String identity = stableKey(database, "capture_method", method);
            require(catalog.findCaptureMethods().stream().anyMatch(item -> item.id() == method
                            && "Lazo manual".equals(item.name())),
                    "Custom methods must be stored and whitespace normalized");
            requireInvalid(() -> catalog.saveCaptureMethod(null, "lazo manual"),
                    "Method names must not be duplicated with different capitalization");
            requireInvalid(() -> catalog.saveCaptureMethod(null, " \t "),
                    "Empty method names must not be saved");
            catalog.saveCaptureMethod(method, "Lazo actualizado");
            require(identity.equals(stableKey(database, "capture_method", method)),
                    "Renaming must keep the method identity");
            long species = catalog.insertSpecies(new SpeciesInput(null, "Turdus philomelos", null));
            BirdEventRepository events = new BirdEventRepository(database.toString());
            long event = events.insert(captureMethodInput("CAP-1", species, method));
            require(events.findDetail(event).orElseThrow().captureMethodId().equals(method)
                            && "Lazo actualizado".equals(events.findDetail(event).orElseThrow().captureMethod()),
                    "Events must save the selected method and expose its catalog name");
            catalog.setCaptureMethodActive(method, false);
            require(catalog.findCaptureMethodOptions().stream().noneMatch(item -> item.id() == method),
                    "Archived methods must disappear from the options for new events");
            require(catalog.findCaptureMethods().stream().anyMatch(item -> item.id() == method && !item.active()),
                    "Archived methods must remain available in catalog management");
            events.update(event, captureMethodInput("CAP-1", species, method));
            require(events.findDetail(event).orElseThrow().captureMethodId().equals(method),
                    "Editing an event must preserve its archived capture method");
            require("Lazo actualizado".equals(events.findReportRows(List.of(event)).get(0).detail().captureMethod()),
                    "Report projections must retain the capture method, even when archived");
            requireInvalid(() -> events.insert(captureMethodInput("CAP-2", species, method)),
                    "An archived method must not be assigned to another event");
            requireInvalid(() -> events.insert(captureMethodInput("CAP-3", species, 99999L)),
                    "A missing capture method must fail with domain validation");
            events.update(event, captureMethodInput("CAP-1", species, null));
            require(events.findDetail(event).orElseThrow().captureMethodId() == null,
                    "The capture method must remain optional and clearable");
            catalog.setCaptureMethodActive(method, true);
            require(catalog.findCaptureMethodOptions().stream().anyMatch(item -> item.id() == method),
                    "An archived method must be restorable without changing its identity");
        } finally {
            deleteDirectory(root);
        }
    }

    private static BirdEventInput captureMethodInput(String ring, long species, Long method) {
        return new BirdEventInput(
                ring, species, EventType.RINGING, "2026-11-04", null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, false, method
        );
    }

    private static void requireInvalid(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected validation.
        }
    }

    private static String stableKey(Path database, String table, long entityId)
            throws Exception {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT stable_key FROM " + table + " WHERE id = " + entityId
             )) {
            require(result.next(), "Expected the persisted catalog entity");
            return result.getString(1);
        }
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        placesCanBeCreatedAndEditedWithoutChangingTheirIdentity();
        changingTheDefaultPlaceKeepsOnlyOneDefault();
        speciesCanBeEditedWithoutChangingTheirIdentity();
        captureMethodsAreReusableAndPreserveHistoricalEvents();
        System.out.println("CatalogRepositoryTest: PASS");
    }
}

package com.adelylria.ringlog.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.ReadOnlyDatabaseAccess;
import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.EnvironmentDefaults;
import com.adelylria.ringlog.model.view.EnvironmentOption;

public final class EnvironmentCatalogRepositoryTest {
    private EnvironmentCatalogRepositoryTest() { }

    public static void photoValuesAndSeedIdentitiesAreLoadedInOrder() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-seeds-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CatalogRepository catalog = new CatalogRepository(database.toString());
            List<EnvironmentOption> options = catalog.findEnvironmentOptions();
            require(options.size() == 18 && options.stream().allMatch(EnvironmentOption::active),
                    "A fresh catalog must contain exactly eighteen active photo values");
            require(names(options, EnvironmentCategory.CLOUDS).equals(List.of("0%", "1-25%", "25-75%", "75-100%")),
                    "Cloud choices must match the photo in ordinal order");
            require(names(options, EnvironmentCategory.RAIN).equals(List.of("Nula", "Débil", "Moderada", "Fuerte")),
                    "Rain choices must match the photo in ordinal order");
            require(names(options, EnvironmentCategory.THERMAL_SENSATION).equals(List.of("Mucho frío", "Frío", "Templado", "Calor", "Mucho calor")),
                    "Thermal choices must match the photo independently of numeric temperature");
            require(names(options, EnvironmentCategory.WIND).equals(List.of("Nulo", "Débil", "Moderado", "Fuerte", "Muy fuerte")),
                    "Wind choices must match the photo in ordinal order");
            require(options.stream().map(option -> new EnvironmentOption(0, option.stableKey(), option.category(),
                            option.name(), option.active(), option.sortOrder())).toList().equals(EnvironmentDefaults.options()),
                    "Fresh SQL and migration defaults must use identical stable identities and ordering");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void catalogEditsNeverRewriteHistoricalWeather() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-edit-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CatalogRepository catalog = new CatalogRepository(database.toString());
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO species(stable_key, scientific_name) VALUES ('seed-species', 'Turdus merula')");
                statement.executeUpdate("INSERT INTO bird(stable_key, ring_number, species_id) VALUES ('seed-bird', 'ENV-1', 1)");
                statement.executeUpdate("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date, clouds, rain, thermal_sensation, wind, temperature_celsius)
                        VALUES ('seed-event', 1, 'RINGING', '2026-11-16', 'Sí antiguo', 'No antiguo', 'Templat', 'Viento variable antiguo', 0)
                        """);
            }
            EnvironmentOption original = catalog.findEnvironmentOptions().stream()
                    .filter(option -> option.category() == EnvironmentCategory.THERMAL_SENSATION && option.name().equals("Templado"))
                    .findFirst().orElseThrow();
            catalog.saveEnvironmentOption(original.id(), original.category(), "  Sensación   templada  ");
            catalog.setEnvironmentOptionActive(original.id(), false);
            EnvironmentOption edited = option(catalog, original.id());
            require(edited.name().equals("Sensación templada") && !edited.active()
                            && edited.stableKey().equals(original.stableKey()),
                    "Renaming and deactivating must preserve the option identity");
            var historical = new BirdEventRepository(database.toString()).findDetail(1).orElseThrow();
            require("Sí antiguo".equals(historical.clouds()) && "No antiguo".equals(historical.rain())
                            && "Templat".equals(historical.thermalSensation())
                            && "Viento variable antiguo".equals(historical.wind())
                            && Integer.valueOf(0).equals(historical.temperatureCelsius()),
                    "Editing a choice must never rewrite any historical text or the independent numeric temperature");
            catalog.setEnvironmentOptionActive(original.id(), true);
            require(option(catalog, original.id()).active(), "Hidden options must be restorable");
            long added = catalog.saveEnvironmentOption(null, EnvironmentCategory.WIND, "  Rachas   fuertes  ");
            require(option(catalog, added).name().equals("Rachas fuertes")
                            && names(catalog.findEnvironmentOptions(), EnvironmentCategory.WIND).get(5).equals("Rachas fuertes"),
                    "New normalized choices must be appended to their category");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE environment_option SET name = 'Rachas  fuertes' WHERE id = " + added);
            }
            requireInvalid(() -> catalog.saveEnvironmentOption(null, EnvironmentCategory.WIND, "rachas fuertes"),
                    "Duplicate imported names must be rejected without depending on case or repeated whitespace");
            catalog.setEnvironmentOptionActive(added, false);
            requireInvalid(() -> catalog.saveEnvironmentOption(null, EnvironmentCategory.WIND, "Rachas fuertes"),
                    "Inactive duplicates must be reactivated rather than duplicated");
            catalog.saveEnvironmentOption(null, EnvironmentCategory.RAIN, "Rachas fuertes");
            requireInvalid(() -> catalog.saveEnvironmentOption(added, EnvironmentCategory.RAIN, "Otra opción"),
                    "Changing category must not silently repurpose an existing option");
            requireInvalid(() -> catalog.saveEnvironmentOption(null, null, "Otra opción"), "Category is required");
            requireInvalid(() -> catalog.saveEnvironmentOption(null, EnvironmentCategory.WIND, " "), "Name is required");
            requireInvalid(() -> catalog.setEnvironmentOptionActive(99999, true), "Missing options cannot be reactivated");
            Database.initialize(database.toString());
            require(!option(catalog, added).active(), "Reopening must not reactivate user-hidden choices");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void orderingIsScopedAndHandlesImportedTies() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-order-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CatalogRepository catalog = new CatalogRepository(database.toString());
            EnvironmentOption wind = catalog.findEnvironmentOptions().stream()
                    .filter(option -> option.category() == EnvironmentCategory.WIND && option.name().equals("Débil"))
                    .findFirst().orElseThrow();
            List<String> clouds = names(catalog.findEnvironmentOptions(), EnvironmentCategory.CLOUDS);
            catalog.moveEnvironmentOption(wind.id(), -1);
            require(names(catalog.findEnvironmentOptions(), EnvironmentCategory.WIND)
                            .equals(List.of("Débil", "Nulo", "Moderado", "Fuerte", "Muy fuerte")),
                    "Moving up must change the selected neighbors only");
            catalog.moveEnvironmentOption(wind.id(), -1);
            require(names(catalog.findEnvironmentOptions(), EnvironmentCategory.WIND).get(0).equals("Débil"),
                    "Moving the first choice up must be a harmless no-op");
            catalog.moveEnvironmentOption(wind.id(), 1);
            require(names(catalog.findEnvironmentOptions(), EnvironmentCategory.WIND)
                            .equals(List.of("Nulo", "Débil", "Moderado", "Fuerte", "Muy fuerte"))
                            && names(catalog.findEnvironmentOptions(), EnvironmentCategory.CLOUDS).equals(clouds),
                    "Moving down must restore order without affecting another field");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE environment_option SET sort_order = 4 WHERE category = 'WIND'");
            }
            catalog.invalidateEnvironmentOptions();
            List<EnvironmentOption> before = catalog.findEnvironmentOptions().stream()
                    .filter(option -> option.category() == EnvironmentCategory.WIND).toList();
            catalog.moveEnvironmentOption(before.get(1).id(), -1);
            List<EnvironmentOption> after = catalog.findEnvironmentOptions().stream()
                    .filter(option -> option.category() == EnvironmentCategory.WIND).toList();
            require(after.get(0).id() == before.get(1).id() && after.get(1).id() == before.get(0).id()
                            && after.stream().map(EnvironmentOption::sortOrder).toList().equals(List.of(0, 1, 2, 3, 4)),
                    "Imported equal positions must be normalized consistently when reordered");
            requireInvalid(() -> catalog.moveEnvironmentOption(wind.id(), 0), "Unsupported directions must be rejected");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void portableCatalogsRemainReadOnly() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-readonly-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            CatalogRepository catalog = new CatalogRepository(new ReadOnlyDatabaseAccess(database));
            List<EnvironmentOption> before = catalog.findEnvironmentOptions();
            long id = before.get(0).id();
            requireFailure(() -> catalog.saveEnvironmentOption(null, EnvironmentCategory.CLOUDS, "Otra"), "Portable catalog cannot add choices");
            requireFailure(() -> catalog.saveEnvironmentOption(id, EnvironmentCategory.CLOUDS, "Otra"), "Portable catalog cannot rename choices");
            requireFailure(() -> catalog.setEnvironmentOptionActive(id, false), "Portable catalog cannot hide choices");
            requireFailure(() -> catalog.moveEnvironmentOption(id, 1), "Portable catalog cannot reorder choices");
            require(catalog.findEnvironmentOptions().equals(before), "Rejected mutations must leave the entire portable catalog unchanged");
        } finally {
            deleteDirectory(root);
        }
    }

    private static List<String> names(List<EnvironmentOption> options, EnvironmentCategory category) {
        return options.stream().filter(option -> option.category() == category).map(EnvironmentOption::name).toList();
    }

    private static EnvironmentOption option(CatalogRepository catalog, long id) {
        return catalog.findEnvironmentOptions().stream().filter(option -> option.id() == id).findFirst().orElseThrow();
    }

    private static void requireInvalid(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void requireFailure(Runnable operation, String message) {
        try { operation.run(); } catch (RepositoryException expected) { return; }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public static void main(String[] args) throws Exception {
        photoValuesAndSeedIdentitiesAreLoadedInOrder();
        catalogEditsNeverRewriteHistoricalWeather();
        orderingIsScopedAndHandlesImportedTies();
        portableCatalogsRemainReadOnly();
        System.out.println("EnvironmentCatalogRepositoryTest: PASS");
    }
}

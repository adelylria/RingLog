package com.adelylria.ringlog.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.model.view.BirdEventReportRow;
import com.adelylria.ringlog.model.view.BirdLookup;
import com.adelylria.ringlog.model.view.DashboardStats;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.support.DatabaseConnection;

public final class BirdEventRepositoryTest {

    private static Path seededFixture;

    private BirdEventRepositoryTest() {
    }

    private static Path copySeededDatabase() throws Exception {
        Path copy = Files.createTempFile("ringlog-events-", ".db");
        Files.copy(seededFixture(), copy, StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    private static synchronized Path seededFixture() throws Exception {
        if (seededFixture != null && Files.isRegularFile(seededFixture)) {
            return seededFixture;
        }

        Path directory = Files.createTempDirectory("ringlog-repository-fixture-");
        Path database = directory.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            applySeed(database);
            seededFixture = database;
            return seededFixture;
        } catch (Exception exception) {
            Files.deleteIfExists(database);
            Files.deleteIfExists(directory.resolve(".ringlog.db.init.lock"));
            Files.deleteIfExists(directory);
            throw exception;
        }
    }

    private static void applySeed(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString())) {
            connection.setAutoCommit(false);
            SyntheticRepositoryFixture.apply(connection);
            connection.commit();
        }
    }

    private static void deleteSeededFixture() throws Exception {
        if (seededFixture == null) {
            return;
        }
        Path directory = seededFixture.getParent();
        Files.deleteIfExists(seededFixture);
        Files.deleteIfExists(directory.resolve(".ringlog.db.init.lock"));
        Files.deleteIfExists(directory);
        seededFixture = null;
    }

    public static void connectionsEnableForeignKeys() throws Exception {
        Path database = Files.createTempFile("ringlog-empty-", ".db");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            require(result.next() && result.getInt(1) == 1,
                    "SQLite foreign keys should be enabled per connection");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void missingDatabaseIsCreatedFromTheCanonicalSchema()
            throws Exception {
        Path directory = Files.createTempDirectory("ringlog-new-database-");
        Path database = directory.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());

            require(Files.isRegularFile(database),
                    "Initialization should create the missing SQLite file");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(databaseObjectExists(statement, "table", "species"),
                        "The species table should be created");
                require(databaseObjectExists(statement, "table", "bird"),
                        "The bird table should be created");
                require(databaseObjectExists(statement, "table", "place"),
                        "The place table should be created");
                require(databaseObjectExists(statement, "table", "bird_event"),
                        "The event table should be created");
                require(databaseObjectExists(statement, "table", "event_photo"),
                        "The photo table should be created");
                require(databaseObjectExists(
                                statement,
                                "index",
                                "idx_place_single_default"
                        ),
                        "The partial default-place index should be preserved");
                require(databaseObjectExists(
                                statement,
                                "trigger",
                                "trg_bird_event_updated_at"
                        ),
                        "The event update trigger should be preserved");
                requireDatabaseObjects(statement, "index", Set.of(
                        "idx_place_single_default",
                        "idx_bird_species",
                        "idx_event_bird",
                        "idx_event_date",
                        "idx_event_type",
                        "idx_event_place",
                        "idx_event_bird_date",
                        "idx_place_favorite",
                        "idx_photo_event"
                ));
                requireDatabaseObjects(statement, "trigger", Set.of(
                        "trg_species_updated_at",
                        "trg_bird_updated_at",
                        "trg_place_updated_at",
                        "trg_bird_event_updated_at"
                ));
            }
            try (var files = Files.list(directory)) {
                require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                        "Initialization must not leave a staged database behind");
            }
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(directory.resolve(".ringlog.db.init.lock"));
            Files.deleteIfExists(directory);
        }
    }

    public static void existingUnknownDatabaseIsRejectedWithoutRebuild()
            throws Exception {
        Path database = Files.createTempFile("ringlog-existing-", ".db");
        try {
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sentinel (value TEXT)");
            }

            requireSqlFailure(
                    () -> Database.initialize(database.toString()),
                    "An unrelated SQLite file should be rejected during initialization"
            );

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(databaseObjectExists(statement, "table", "sentinel"),
                        "An existing database should keep its original schema");
                require(!databaseObjectExists(statement, "table", "species"),
                        "Rejected initialization must not add RingLog tables");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void existingInvalidDatabaseFailsReadOnlyValidation()
            throws Exception {
        Path database = Files.createTempFile("ringlog-invalid-schema-", ".db");
        try {
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sentinel (value TEXT)");
            }
            byte[] beforeValidation = Files.readAllBytes(database);

            requireSqlFailure(
                    () -> Database.validate(database.toString()),
                    "An unrelated SQLite file should fail RingLog schema validation"
            );

            require(Arrays.equals(beforeValidation, Files.readAllBytes(database)),
                    "Read-only validation must not change an existing database");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void existingValidDatabaseRemainsByteIdenticalAtStartup()
            throws Exception {
        Path database = copySeededDatabase();
        try {
            byte[] beforeStartup = Files.readAllBytes(database);

            Database.initialize(database.toString());
            Database.validate(database.toString());

            require(Arrays.equals(beforeStartup, Files.readAllBytes(database)),
                    "Startup must not change an existing valid RingLog database");
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void ormConnectionsKeepSQLiteForeignKeysEnabled()
            throws Exception {
        Path database = Files.createTempFile("ringlog-orm-", ".db");
        try (ConnectionSource source = Database.getConnectionSource(database.toString())) {
            DatabaseConnection connection = source.getReadWriteConnection(null);
            try {
                require(connection.queryForLong("PRAGMA foreign_keys") == 1,
                        "ORMLite connections should enforce SQLite foreign keys");
            } finally {
                source.releaseConnection(connection);
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void freshlyInitializedDatabaseAcceptsOrmRepositories()
            throws Exception {
        Path directory = Files.createTempDirectory("ringlog-fresh-orm-");
        Path database = directory.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            Database.validate(database.toString());
            CatalogRepository catalog = new CatalogRepository(database.toString());
            BirdEventRepository events = new BirdEventRepository(database.toString());

            long speciesId = insertSpecies(
                    catalog,
                    "TUR-PHI",
                    "Turdus philomelos",
                    "Zorzal común"
            );
            long placeId = insertPlace(
                    catalog,
                    "Estación norte",
                    "Pollença",
                    39.87,
                    3.01,
                    null,
                    true,
                    true
            );
            long eventId = events.insert(eventInput(
                    "NEW-0001",
                    speciesId,
                    EventType.RINGING,
                    placeId,
                    null
            ));

            require(eventId > 0, "The ORM should insert into a freshly created database");
            require(catalog.findSpeciesOptions().size() == 1,
                    "The ORM should read the newly created species catalog");
            require(catalog.findBirdOptions().size() == 1,
                    "The ORM should read the bird created by the first event");
            DashboardStats stats = events.findDashboardStats();
            require(stats.eventCount() == 1 && stats.speciesCount() == 1,
                    "Dashboard aggregates should work on the new database");
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(directory.resolve(".ringlog.db.init.lock"));
            Files.deleteIfExists(directory);
        }
    }

    public static void timelineReadsAllImportedEventTypes() throws Exception {
        Path copy = copySeededDatabase();
        try {
            List<BirdEventTimelineItem> events =
                    new BirdEventRepository(copy.toString()).findTimeline();

            require(events.size() == 8, "Expected eight synthetic events");
            require("TEST-NEWEST".equals(events.get(0).ringNumber()),
                    "The newest imported event should be first");
            require(events.stream().anyMatch(e -> e.eventType() == EventType.CONTROL),
                    "Imported controls should be visible");
            require(events.stream().anyMatch(e -> e.eventType() == EventType.RECOVERY),
                    "Imported recoveries should be visible");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void recentTimelineLimitsWorkBeforeBuildingDiaryCards()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            List<BirdEventTimelineItem> events =
                    new BirdEventRepository(copy.toString()).findRecentTimeline(5);

            require(events.size() == 5,
                    "The home view should only fetch the entries it can display");
            require("TEST-NEWEST".equals(events.get(0).ringNumber()),
                    "The limited timeline should preserve newest-first order");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void detailUsesNormalizedBirdPlaceAndAgeFields() throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventDetail detail = new BirdEventRepository(copy.toString())
                    .findDetail(1L)
                    .orElseThrow();

            require("TEST-PRIMARY".equals(detail.ringNumber()), "Wrong ring number");
            require("AVIS TESTENSIS".equals(detail.species()),
                    "Species should be resolved through bird");
            require("ESTACION ALFA".equals(detail.place()),
                    "Place should be resolved through place_id");
            require("2".equals(detail.ageEuringCode()),
                    "The age EURING field should use the schema name");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void reportRowsUseOneReadOnlyCatalogSnapshot() throws Exception {
        Path copy = copySeededDatabase();
        try {
            byte[] before = Files.readAllBytes(copy);
            List<BirdEventReportRow> rows = new BirdEventRepository(copy.toString())
                    .findReportRows(List.of(2L, 1L));

            require(rows.size() == 2
                            && rows.get(0).id() == 2L
                            && rows.get(rows.size() - 1).id() == 1L,
                    "Report rows should preserve the filtered diary order");
            BirdEventReportRow first = rows.get(0);
            require("TESTSP".equals(first.speciesCode())
                            && "AVIS TESTENSIS".equals(first.speciesScientificName())
                            && first.speciesCommonName() == null,
                    "The report projection should retain every species identity field");
            require("fixture-sintetico.xlsx".equals(first.sourceName())
                            && "synthetic:2".equals(first.sourceReference())
                            && first.photoCount() == 0,
                    "The report projection should retain provenance and photo count");
            require(Arrays.equals(before, Files.readAllBytes(copy)),
                    "Loading a report snapshot must not modify its database");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void birdLookupExposesItsEventHistory() throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            BirdLookup bird = repository.findBirdByRingNumber("test-history").orElseThrow();

            require("TEST-HISTORY".equals(bird.ringNumber()),
                    "Ring lookup should ignore case");
            require(bird.eventCount() == 3,
                    "The imported bird should expose its three historical events");
            require(repository.findEventsByBird(bird.id()).size() == 3,
                    "Bird history should return all events for the same bird");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void catalogsUseEventCountsAndDefaultPlace() throws Exception {
        Path copy = copySeededDatabase();
        try {
            CatalogRepository repository = new CatalogRepository(copy.toString());
            List<SpeciesSummary> species = repository.findSpeciesSummaries();
            List<PlaceSummary> places = repository.findPlaceSummaries();

            require(species.size() == 1, "Expected the imported species catalog");
            require(species.get(0).eventCount() == 8,
                    "Species summary should count bird events");
            require(places.size() == 4, "Expected four imported places");
            require(places.get(0).isDefault(),
                    "The default place should be presented first");
            require("ESTACION ALFA".equals(places.get(0).name()),
                    "The synthetic default place should be ESTACION ALFA");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void speciesCatalogCreatesOptionalNamesAndExplainsDuplicates()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            CatalogRepository repository = new CatalogRepository(copy.toString());
            long id = insertSpecies(repository, " SYL-001 ", "Sylvia atricapilla", "Curruca capirotada");

            require(id > 0, "A new species should receive an identifier");
            require(repository.findSpeciesSummaries().stream().anyMatch(species ->
                            species.id() == id
                                    && "Curruca capirotada".equals(species.name())),
                    "The new species should be available in the catalog");
            requireThrows(
                    () -> insertSpecies(
                            repository,
                            "SYL-002",
                            "sylvia ATRICAPILLA",
                            "Otra"
                    ),
                    "Duplicate scientific names must be explained as validation errors"
            );
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void placeCatalogKeepsOnlyTheNewestDefaultPlace()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            CatalogRepository repository = new CatalogRepository(copy.toString());
            long first = insertPlace(
                    repository,
                    "Observatorio norte",
                    "Delta",
                    39.17,
                    -0.31,
                    "Primera estación",
                    true,
                    true
            );
            long second = insertPlace(
                    repository,
                    "Observatorio sur",
                    "Delta",
                    null,
                    null,
                    null,
                    false,
                    true
            );

            List<PlaceSummary> places = repository.findPlaceSummaries();
            require(places.stream().anyMatch(place -> place.id() == second && place.isDefault()),
                    "The newly saved default place should be marked as default");
            require(places.stream().noneMatch(place -> place.id() == first && place.isDefault()),
                    "Saving a new default place should clear the previous default mark");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void placeCatalogRejectsNonFiniteCoordinatesClearly()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            CatalogRepository repository = new CatalogRepository(copy.toString());
            requireThrowsContaining(
                    () -> insertPlace(
                            repository,
                            "Coordenada NaN",
                            null,
                            Double.NaN,
                            null,
                            null,
                            false,
                            false
                    ),
                    "latitud",
                    "NaN should be rejected as an invalid latitude"
            );
            requireThrowsContaining(
                    () -> insertPlace(
                            repository,
                            "Coordenada infinita",
                            null,
                            null,
                            Double.POSITIVE_INFINITY,
                            null,
                            false,
                            false
                    ),
                    "longitud",
                    "Infinite values should be rejected as invalid longitude"
            );
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void ringingAndControlReuseOneBirdInsideTransactions()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            long ringingId = repository.insert(eventInput(
                    "TEST-0001", 1L, EventType.RINGING, 1L, null
            ));
            BirdLookup bird = repository.findBirdByRingNumber("TEST-0001").orElseThrow();

            require(repository.findDetail(ringingId).orElseThrow().eventType()
                            == EventType.RINGING,
                    "A new ring should create its first ringing event");
            long controlId = repository.insert(eventInput(
                    "TEST-0001", null, EventType.CONTROL, 1L, null
            ));

            require(repository.findDetail(controlId).orElseThrow().birdId() == bird.id(),
                    "A control should reuse the existing bird");
            require(repository.findEventsByBird(bird.id()).size() == 2,
                    "The new bird should have ringing and control history");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void eventCreationRejectsInvalidBirdLifecycle() throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            requireThrows(
                    () -> repository.insert(eventInput(
                            "UNKNOWN-01", null, EventType.CONTROL, 1L, null
                    )),
                    "An unknown bird cannot receive a control"
            );
            requireThrows(
                    () -> repository.insert(eventInput(
                            "TEST-HISTORY", 1L, EventType.RINGING, 1L, null
                    )),
                    "An existing bird cannot receive a second ringing"
            );
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void eventCreationRejectsInvalidMeasurements() throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            requireThrowsContaining(
                    () -> repository.insert(eventInputWithMeasurements(
                            "MEASURE-01", Double.NaN, 52.0, 31.0, 22.0
                    )),
                    "ala",
                    "NaN should not be stored as a wing measurement"
            );
            requireThrowsContaining(
                    () -> repository.insert(eventInputWithMeasurements(
                            "MEASURE-02", 80.0, Double.POSITIVE_INFINITY, 31.0, 22.0
                    )),
                    "p3",
                    "Infinite P3 measurements should be rejected"
            );
            requireThrowsContaining(
                    () -> repository.insert(eventInputWithMeasurements(
                            "MEASURE-03", 80.0, 52.0, -1.0, 22.0
                    )),
                    "torso",
                    "Negative torso measurements should be rejected"
            );
            requireThrowsContaining(
                    () -> repository.insert(eventInputWithMeasurements(
                            "MEASURE-04", 80.0, 52.0, 31.0, -1.0
                    )),
                    "peso",
                    "Negative weights should be rejected"
            );
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void eventEditingUpdatesTheEntryWithoutLosingTraceability()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            CatalogRepository catalog = new CatalogRepository(copy.toString());
            BirdEventDetail before = repository.findDetail(1L).orElseThrow();
            List<String> technicalIdentity = eventTechnicalIdentity(copy, 1L);
            long speciesId = catalog.findSpeciesSummaries().stream()
                    .mapToLong(SpeciesSummary::id)
                    .filter(id -> id != before.speciesId())
                    .findFirst()
                    .orElse(before.speciesId());
            long placeId = catalog.findPlaceSummaries().stream()
                    .mapToLong(PlaceSummary::id)
                    .filter(id -> before.placeId() == null || id != before.placeId())
                    .findFirst()
                    .orElseThrow();

            repository.update(1L, new BirdEventInput(
                    "EDIT-33907",
                    speciesId,
                    EventType.CONTROL,
                    "2026-09-03",
                    "",
                    placeId,
                    "Ubicación corregida",
                    "F",
                    "6",
                    1,
                    2,
                    "ZZ",
                    "PENDING",
                    "BREEDING",
                    "LOW",
                    "PARTIAL",
                    "REGULAR",
                    "YES",
                    78.4,
                    50.2,
                    29.8,
                    24.6,
                    "Observación editada",
                    "MEDIUM",
                    "YES",
                    "18",
                    "MODERATE",
                    "RECAPTURE",
                    true
            ));

            BirdEventDetail after = repository.findDetail(1L).orElseThrow();
            require("EDIT-33907".equals(after.ringNumber()),
                    "The corrected ring number should be persisted");
            require(after.speciesId() == speciesId && after.placeId() == placeId,
                    "Edited catalog references should be persisted");
            require(after.eventType() == EventType.CONTROL
                            && "2026-09-03".equals(after.eventDate())
                            && after.eventTime() == null,
                    "The edited moment should be persisted, including an unknown time");
            require("Observación editada".equals(after.observations())
                            && after.dead()
                            && Double.valueOf(78.4).equals(after.wing()),
                    "Edited optional fields should be persisted");
            require(technicalIdentity.equals(eventTechnicalIdentity(copy, 1L)),
                    "Editing must preserve stable keys, provenance and review state");
            require(before.photoPaths().equals(after.photoPaths()),
                    "Editing fields must not replace or remove photographs");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void eventEditingRejectsAnotherBirdRingAndRollsBack()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            BirdEventDetail before = repository.findDetail(1L).orElseThrow();
            requireThrowsContaining(
                    () -> repository.update(1L, eventInput(
                            "TEST-HISTORY",
                            before.speciesId(),
                            before.eventType(),
                            before.placeId(),
                            before.locationText()
                    )),
                    "otra ave",
                    "An entry cannot be reassigned to another bird by reusing its ring"
            );
            BirdEventDetail after = repository.findDetail(1L).orElseThrow();
            require(before.ringNumber().equals(after.ringNumber())
                            && before.eventDate().equals(after.eventDate()),
                    "A rejected edit must leave the event and bird unchanged");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void failedEventInsertRollsBackNewBird() throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            requireRepositoryFailure(
                    () -> repository.insert(eventInput(
                            "ROLLBACK-0001",
                            1L,
                            EventType.RINGING,
                            999_999L,
                            null
                    )),
                    "An invalid place should fail the event insert"
            );
            require(repository.findBirdByRingNumber("ROLLBACK-0001").isEmpty(),
                    "A failed event insert must roll back the newly created bird");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void failedPlaceInsertRestoresPreviousDefault() throws Exception {
        Path copy = copySeededDatabase();
        try {
            CatalogRepository repository = new CatalogRepository(copy.toString());
            PlaceSummary original = repository.findPlaceSummaries().stream()
                    .filter(PlaceSummary::isDefault)
                    .findFirst()
                    .orElseThrow();

            requireThrows(
                    () -> insertPlace(
                            repository,
                            original.name(),
                            original.locality(),
                            original.latitude(),
                            original.longitude(),
                            original.notes(),
                            original.favorite(),
                            true
                    ),
                    "A duplicate default place should fail"
            );

            PlaceSummary afterFailure = repository.findPlaceSummaries().stream()
                    .filter(place -> place.id() == original.id())
                    .findFirst()
                    .orElseThrow();
            require(afterFailure.isDefault(),
                    "A failed insert must restore the previous default place");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    public static void recoveryCanUseTextInsteadOfDuplicatedPlaceData()
            throws Exception {
        Path copy = copySeededDatabase();
        try {
            BirdEventRepository repository = new BirdEventRepository(copy.toString());
            long eventId = repository.insert(eventInput(
                    "TEST-HISTORY",
                    null,
                    EventType.RECOVERY,
                    null,
                    "Mallorca, 26 km del lugar de anillamiento"
            ));
            BirdEventDetail detail = repository.findDetail(eventId).orElseThrow();

            require(detail.place() == null,
                    "An imprecise recovery should not invent a catalogued place");
            require("Mallorca, 26 km del lugar de anillamiento"
                            .equals(detail.locationText()),
                    "An imprecise recovery should preserve its location text");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    private static BirdEventInput eventInput(
            String ringNumber,
            Long speciesId,
            EventType type,
            Long placeId,
            String locationText
    ) {
        return new BirdEventInput(
                ringNumber,
                speciesId,
                type,
                "2026-08-23",
                "11:20:00",
                placeId,
                locationText,
                "M",
                "4",
                2,
                3,
                "AD",
                "OK",
                null,
                null,
                null,
                "GOOD",
                null,
                80.0,
                52.0,
                31.0,
                22.0,
                "Entrada creada desde la prueba",
                "LOW",
                "NO",
                "24",
                "LIGHT",
                null,
                false
        );
    }

    private static List<String> eventTechnicalIdentity(Path database, long eventId)
            throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT e.stable_key,
                            e.migration_key,
                            e.source_name,
                            e.source_reference,
                            e.review_status,
                            e.review_note,
                            b.stable_key
                     FROM bird_event e
                     JOIN bird b ON b.id = e.bird_id
                     WHERE e.id = %d
                     """.formatted(eventId))) {
            require(result.next(), "Missing event technical identity");
            return Arrays.asList(
                    result.getString(1),
                    result.getString(2),
                    result.getString(3),
                    result.getString(4),
                    result.getString(5),
                    result.getString(6),
                    result.getString(7)
            );
        }
    }

    private static BirdEventInput eventInputWithMeasurements(
            String ringNumber,
            Double wing,
            Double p3,
            Double torso,
            Double weight
    ) {
        return new BirdEventInput(
                ringNumber,
                1L,
                EventType.RINGING,
                "2026-08-23",
                "11:20:00",
                1L,
                null,
                "M",
                "4",
                2,
                3,
                "AD",
                "OK",
                null,
                null,
                null,
                "GOOD",
                null,
                wing,
                p3,
                torso,
                weight,
                "Entrada con medidas",
                "LOW",
                "NO",
                "24",
                "LIGHT",
                null,
                false
        );
    }

    private static long insertSpecies(
            CatalogRepository repository,
            String code,
            String scientificName,
            String commonName
    ) throws Exception {
        Object input = createInput(
                "com.adelylria.ringlog.model.input.SpeciesInput",
                new Class<?>[]{String.class, String.class, String.class},
                code,
                scientificName,
                commonName
        );
        return insertCatalogEntry(repository, "insertSpecies", input);
    }

    private static long insertPlace(
            CatalogRepository repository,
            String name,
            String locality,
            Double latitude,
            Double longitude,
            String notes,
            boolean favorite,
            boolean isDefault
    ) throws Exception {
        Object input = createInput(
                "com.adelylria.ringlog.model.input.PlaceInput",
                new Class<?>[]{
                        String.class,
                        String.class,
                        Double.class,
                        Double.class,
                        String.class,
                        boolean.class,
                        boolean.class
                },
                name,
                locality,
                latitude,
                longitude,
                notes,
                favorite,
                isDefault
        );
        return insertCatalogEntry(repository, "insertPlace", input);
    }

    private static Object createInput(
            String className,
            Class<?>[] parameterTypes,
            Object... values
    ) throws Exception {
        Class<?> inputType = Class.forName(className);
        return inputType.getConstructor(parameterTypes).newInstance(values);
    }

    private static long insertCatalogEntry(
            CatalogRepository repository,
            String methodName,
            Object input
    ) throws Exception {
        Method method = repository.getClass().getMethod(methodName, input.getClass());
        try {
            return (long) method.invoke(repository, input);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw exception;
        }
    }

    private static void requireThrows(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected domain validation.
        }
    }

    private static void requireThrowsContaining(
            ThrowingRunnable action,
            String expectedText,
            String message
    ) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null
                            && expected.getMessage().toLowerCase().contains(expectedText),
                    message + ": " + expected.getMessage());
        }
    }

    private static void requireSqlFailure(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SQLException expected) {
            // Expected schema validation failure.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static boolean databaseObjectExists(
            Statement statement,
            String type,
            String name
    ) throws Exception {
        String escapedType = type.replace("'", "''");
        String escapedName = name.replace("'", "''");
        try (ResultSet result = statement.executeQuery("""
                SELECT 1
                FROM sqlite_master
                WHERE type = '%s' AND name = '%s'
                """.formatted(escapedType, escapedName))) {
            return result.next();
        }
    }

    private static void requireDatabaseObjects(
            Statement statement,
            String type,
            Set<String> names
    ) throws Exception {
        for (String name : names) {
            require(databaseObjectExists(statement, type, name),
                    "Missing " + type + " from canonical schema: " + name);
        }
    }

    private static void requireRepositoryFailure(
            ThrowingRunnable action,
            String message
    ) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (RepositoryException expected) {
            // Expected persistence failure.
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            connectionsEnableForeignKeys();
            missingDatabaseIsCreatedFromTheCanonicalSchema();
            existingUnknownDatabaseIsRejectedWithoutRebuild();
            existingInvalidDatabaseFailsReadOnlyValidation();
            existingValidDatabaseRemainsByteIdenticalAtStartup();
            ormConnectionsKeepSQLiteForeignKeysEnabled();
            freshlyInitializedDatabaseAcceptsOrmRepositories();
            timelineReadsAllImportedEventTypes();
            recentTimelineLimitsWorkBeforeBuildingDiaryCards();
            detailUsesNormalizedBirdPlaceAndAgeFields();
            reportRowsUseOneReadOnlyCatalogSnapshot();
            birdLookupExposesItsEventHistory();
            catalogsUseEventCountsAndDefaultPlace();
            speciesCatalogCreatesOptionalNamesAndExplainsDuplicates();
            placeCatalogKeepsOnlyTheNewestDefaultPlace();
            placeCatalogRejectsNonFiniteCoordinatesClearly();
            ringingAndControlReuseOneBirdInsideTransactions();
            eventCreationRejectsInvalidBirdLifecycle();
            eventCreationRejectsInvalidMeasurements();
            eventEditingUpdatesTheEntryWithoutLosingTraceability();
            eventEditingRejectsAnotherBirdRingAndRollsBack();
            failedEventInsertRollsBackNewBird();
            failedPlaceInsertRestoresPreviousDefault();
            recoveryCanUseTextInsteadOfDuplicatedPlaceData();
            System.out.println("BirdEventRepositoryTest: PASS");
        } finally {
            deleteSeededFixture();
        }
    }
}

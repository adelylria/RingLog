package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import com.adelylria.ringlog.application.ApplicationCapabilities;
import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.application.ApplicationMode;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.ReadOnlyDatabaseAccess;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportService;
import com.adelylria.ringlog.storage.PortableAppPaths;

public final class PortableReadOnlyTest {

    private PortableReadOnlyTest() {
    }

    public static void sqliteRejectsEveryKindOfMutation() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-readonly-");
        try {
            PortableAppPaths paths = createDataset(root);
            ReadOnlyDatabaseAccess access = new ReadOnlyDatabaseAccess(paths.databasePath());

            try (Connection connection = access.openConnection()) {
                require(queryInt(connection, "PRAGMA query_only") == 1,
                        "Portable SQLite connections must enable query_only");
                requireSqlFailure(connection,
                        "INSERT INTO species(stable_key, scientific_name) "
                                + "VALUES ('forbidden', 'Forbidden bird')");
                requireSqlFailure(connection,
                        "UPDATE species SET common_name = 'Changed' WHERE id = 1");
                requireSqlFailure(connection, "DELETE FROM species WHERE id = 1");
                requireSqlFailure(connection, "CREATE TABLE forbidden_write(id INTEGER)");
            }

            require(queryCount(paths.databasePath(), "species") == 1,
                    "Rejected SQL must leave the portable database unchanged");
        } finally {
            deleteTree(root);
        }
    }

    public static void repositoriesCannotAcquireWritableOrmConnections() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-orm-");
        try {
            PortableAppPaths paths = createDataset(root);
            ApplicationContext context = ApplicationContext.portable(paths);
            CatalogRepository catalog = new CatalogRepository(
                    context.databaseAccess(), context.mutationCoordinator()
            );

            require(context.databaseAccess().readOnly(),
                    "Portable repositories must receive read-only database access");
            require(catalog.findSpeciesSummaries().size() == 1,
                    "Read-only repositories must keep their query functionality");
            requireRuntimeFailure(() -> catalog.insertSpecies(
                    new SpeciesInput("FORB", "Forbidden bird", "Forbidden")
            ));
            require(queryCount(paths.databasePath(), "species") == 1,
                    "An ORMLite write attempt must not persist data");
        } finally {
            deleteTree(root);
        }
    }

    public static void portableContextContainsNoMutationServices() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-context-");
        String previousMode = System.getProperty(ApplicationMode.PROPERTY);
        try {
            PortableAppPaths paths = createDataset(root);
            ApplicationContext context = ApplicationContext.portable(paths);
            ApplicationCapabilities capabilities = context.capabilities();

            require(context.mode() == ApplicationMode.PORTABLE_READ_ONLY,
                    "Portable context must expose the portable application mode");
            require(context.mutationServices().isEmpty(),
                    "Portable context must not construct import or conflict mutation services");
            require(capabilities.readOnly()
                            && !capabilities.mutateDiary()
                            && !capabilities.manageCatalogs()
                            && !capabilities.importOrRestore()
                            && !capabilities.exportNativeBackup()
                            && !capabilities.resolveConflicts()
                            && !capabilities.checkUpdates()
                            && !capabilities.createPortableCopy()
                            && capabilities.exportReports(),
                    "Portable capabilities must allow only non-mutating report exports");
            require(context.preferences().path().startsWith(paths.portableRoot()),
                    "Portable preferences must stay inside the portable copy");
            require(paths.logsDirectory().startsWith(paths.portableRoot()),
                    "Portable logs must stay inside the portable copy");
            context.preferences().put("theme.dark", "true");
            require(Files.isRegularFile(paths.preferencesPath())
                            && paths.preferencesPath().startsWith(paths.portableRoot()),
                    "Portable preference changes must be written only inside the copy");

            System.setProperty(ApplicationMode.PROPERTY, "portable-readonly");
            require(ApplicationMode.detect() == ApplicationMode.PORTABLE_READ_ONLY,
                    "The technical launcher mode must select portable read-only startup");
        } finally {
            restoreProperty(ApplicationMode.PROPERTY, previousMode);
            deleteTree(root);
        }
    }

    public static void portableStartupRejectsOldSchemaWithoutMigratingIt() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-schema-");
        try {
            PortableAppPaths paths = createDataset(root);
            try (Connection connection = Database.getConnection(paths.databasePath().toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 3");
            }
            try {
                new PortableCopyValidator().validateDataset(paths);
                throw new AssertionError("Portable startup accepted an old database schema");
            } catch (PortableCopyException expected) {
                // Portable startup validates but never upgrades the snapshot.
            }
            try (Connection connection = Database.getConnection(paths.databasePath().toString())) {
                require(queryInt(connection, "PRAGMA user_version") == 3,
                        "Portable validation must never run DatabaseUpgradeService");
            }
        } finally {
            deleteTree(root);
        }
    }

    public static void pdfAndExcelExportsDoNotWritePortableState() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-reports-");
        Path output = Files.createTempDirectory("ringlog-portable-report-output-");
        try {
            PortableAppPaths paths = createDataset(root);
            ApplicationContext context = ApplicationContext.portable(paths);
            BirdEventRepository repository = new BirdEventRepository(
                    paths, context.databaseAccess(), context.mutationCoordinator()
            );
            List<Long> eventIds = repository.findAll().stream().map(item -> item.id()).toList();
            var rows = repository.findReportRows(eventIds);
            String before = PortableHashing.sha256(paths.databasePath());

            new RecordReportService().export(
                    RecordReportFormat.PDF, output.resolve("portable-report.pdf"),
                    rows, List.of("Copia portátil")
            );
            new RecordReportService().export(
                    RecordReportFormat.EXCEL, output.resolve("portable-report.xlsx"),
                    rows, List.of("Copia portátil")
            );

            require(Files.size(output.resolve("portable-report.pdf")) > 0,
                    "Portable PDF export must create a usable file");
            require(Files.size(output.resolve("portable-report.xlsx")) > 0,
                    "Portable Excel export must create a usable file");
            require(before.equals(PortableHashing.sha256(paths.databasePath())),
                    "PDF and Excel exports must not mutate the portable database");
        } finally {
            deleteTree(root);
            deleteTree(output);
        }
    }

    static PortableAppPaths createDataset(Path root) throws Exception {
        PortableAppPaths paths = new PortableAppPaths(root);
        Files.createDirectories(paths.eventPhotosDirectory());
        Files.createDirectories(paths.nativePhotosDirectory());
        Files.createDirectories(paths.unassignedPhotosDirectory());
        Files.createDirectories(paths.configDirectory());
        Files.createDirectories(paths.logsDirectory());
        Database.initialize(paths.databasePath().toString());
        try (Connection connection = Database.getConnection(paths.databasePath().toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species(id, stable_key, code, scientific_name, common_name)
                    VALUES (1, 'species-1', 'TURPHI', 'Turdus philomelos', 'Zorzal común')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(id, stable_key, ring_number, species_id)
                    VALUES (1, 'bird-1', 'V0001', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO place(id, stable_key, name, locality, latitude, longitude)
                    VALUES (1, 'place-1', 'Els Rafals', 'Pollença', 39.85, 2.98)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(
                        id, stable_key, bird_id, event_type, event_date, event_time,
                        place_id, observations, review_status
                    ) VALUES (
                        1, 'event-1', 1, 'RINGING', '2026-09-04', '18:30:00',
                        1, 'Primera línea.\nSegunda línea con á, ñ y €.', 'OK'
                    )
                    """);
        }
        return paths;
    }

    static long queryCount(Path database, String table) throws SQLException {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static void requireSqlFailure(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError("Portable SQLite accepted a write: " + sql);
    }

    private static void requireRuntimeFailure(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("A portable repository accepted a write");
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        sqliteRejectsEveryKindOfMutation();
        repositoriesCannotAcquireWritableOrmConnections();
        portableContextContainsNoMutationServices();
        portableStartupRejectsOldSchemaWithoutMigratingIt();
        pdfAndExcelExportsDoNotWritePortableState();
        System.out.println("PortableReadOnlyTest: PASS");
    }
}

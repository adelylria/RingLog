package com.adelylria.ringlog.importexport;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.backup.BackupExportService;
import com.adelylria.ringlog.importer.LegacyImportService;
import com.adelylria.ringlog.importexport.nativeformat.ExportResult;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

/** Lossless native-v1 ZIP/XLSX and stable-key round-trip contract. */
public final class NativeExportRoundTripTest {

    private static final Path LEGACY_FIXTURE = LegacyV52TestFixture.path();

    private NativeExportRoundTripTest() {
    }

    public static void repeatedLongTextRemainsSelfVerifyingAndLossless() throws Exception {
        Path root = Files.createTempDirectory("ringlog-native-repeated-text-");
        try {
            AppPaths source = AppPaths.forDataRoot(root.resolve("source"));
            Database.initialize(source.databasePath().toString());
            String observations = "A".repeat(20_000);
            String clouds = "\u0000".repeat(20_000);
            try (Connection connection = Database.getConnection(source.databasePath().toString())) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO species(stable_key, scientific_name)
                            VALUES('40404040-4040-4040-8040-404040404040', 'Species repeated text test')
                            """);
                    statement.execute("""
                            INSERT INTO bird(stable_key, ring_number, species_id)
                            VALUES('50505050-5050-4050-8050-505050505050', 'REPEAT-01', 1)
                            """);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date,
                                               observations, clouds)
                        VALUES(?, 1, ?, ?, ?, ?)
                        """)) {
                    for (int index = 1; index <= 513; index++) {
                        statement.setString(1, UUID.nameUUIDFromBytes(
                                ("repeated-text-event-" + index).getBytes(StandardCharsets.UTF_8)).toString());
                        statement.setString(2, index == 1 ? "RINGING" : "CONTROL");
                        statement.setString(3, java.time.LocalDate.of(2026, 1, 1).plusDays(index).toString());
                        statement.setString(4, observations);
                        statement.setString(5, clouds);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            }
            ExportResult exported = new RingLogExporter(source.databasePath().toString())
                    .exportTo(root.resolve("repeated-text"));
            AppPaths restored = AppPaths.forDataRoot(root.resolve("restored"));
            try (ImportPlan plan = new ImportCoordinator().analyze(exported.file())) {
                new ImportTransactionService(restored.databasePath().toString(), restored).execute(plan);
            }
            try (Connection connection = Database.getReadOnlyConnection(restored.databasePath().toString());
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT COUNT(*) FROM bird_event WHERE observations = ? AND clouds = ?
                         """)) {
                statement.setString(1, observations);
                statement.setString(2, clouds);
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next() && result.getInt(1) == 513,
                            "Highly repeated long text and NUL must self-validate and restore exactly");
                }
            }
        } finally {
            deleteDirectory(root);
        }
    }

    public static void largeStreamingExportPreservesFlushedRowsOverflowTextAndBinaries() throws Exception {
        Path root = Files.createTempDirectory("ringlog-native-streaming-");
        try {
            AppPaths source = AppPaths.forDataRoot(root.resolve("source"));
            Database.initialize(source.databasePath().toString());
            StringBuilder overflowText = new StringBuilder();
            for (int index = 0; index < 800; index++) {
                overflowText.append("  Café 中文 🐦 <>&\u0000\r\n")
                        .append(UUID.nameUUIDFromBytes(("overflow-" + index)
                                .getBytes(StandardCharsets.UTF_8)));
            }
            String overflow = overflowText.toString();
            try (Connection connection = Database.getConnection(source.databasePath().toString())) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO species(stable_key, scientific_name)
                            VALUES('10101010-1010-4010-8010-101010101010', 'Species streaming test')
                            """);
                    statement.execute("""
                            INSERT INTO bird(stable_key, ring_number, species_id)
                            VALUES('20202020-2020-4020-8020-202020202020', 'STREAM-01', 1)
                            """);
                    statement.execute("""
                            INSERT INTO capture_method(stable_key, name, active)
                            VALUES('30303030-3030-4030-8030-303030303030', 'Método histórico 🐦', 0)
                            """);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date,
                                               observations, clouds, rain, thermal_sensation,
                                               capture_type, capture_method_id)
                        VALUES(?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (int index = 1; index <= 513; index++) {
                        statement.setString(1, UUID.nameUUIDFromBytes(
                                ("streaming-event-" + index).getBytes(StandardCharsets.UTF_8)).toString());
                        statement.setString(2, index == 1 ? "RINGING" : "CONTROL");
                        statement.setString(3, java.time.LocalDate.of(2026, 1, 1).plusDays(index).toString());
                        statement.setString(4, index % 127 == 0 ? overflow
                                : index % 7 == 0 ? null : index % 7 == 1 ? ""
                                : "  Observación " + index + " — café 中文 🐦\r\n  ");
                        statement.setString(5, index % 3 == 0 ? null : index % 3 == 1 ? "" : "  ");
                        statement.setString(6, index % 2 == 0 ? "" : null);
                        statement.setString(7, "22°\u0000");
                        statement.setString(8, index % 2 == 0 ? "RECAPTURE" : null);
                        statement.setObject(9, index % 3 == 0 ? null : index % 3 == 1 ? 1L : 3L);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            }
            addEventPhoto(source.databasePath(), source);
            Map<String, List<String>> expected = streamingValues(source.databasePath());
            Set<Path> temporarySheets = streamingTemporarySheets();
            ExportResult result = new RingLogExporter(source.databasePath().toString(),
                    new MediaPathResolver(source)).exportTo(root.resolve("native-streaming"));
            require(result.binaryCount() == 1 && result.file().toString().endsWith(".zip"),
                    "A large native export must retain its physical photograph");
            require(temporarySheets.equals(streamingTemporarySheets()),
                    "A completed streaming export must dispose all temporary sheet XML");
            try (ImportSource archive = ImportSource.open(result.file());
                 InputStream input = Files.newInputStream(archive.workbookPath());
                 Workbook workbook = WorkbookFactory.create(input)) {
                Sheet events = workbook.getSheet("events");
                require(events.getLastRowNum() == 513,
                        "Flushing the writer window must not discard earlier or final event rows");
                require(events.getRow(1).getCell(0).getCellType()
                                == org.apache.poi.ss.usermodel.CellType.STRING,
                        "Native encoded cells must remain readable strings");
                require(events.getPaneInformation() != null && events.getPaneInformation().isFreezePane(),
                        "Streaming must retain the frozen native header row");
                require(workbook.getSheet("text_content").getLastRowNum() > 0
                                && workbook.getSheetVisibility(workbook.getSheetIndex("text_content"))
                                == org.apache.poi.ss.usermodel.SheetVisibility.VERY_HIDDEN,
                        "Overflow text chunks must remain available in the hidden text store");
            }
            AppPaths restored = AppPaths.forDataRoot(root.resolve("restored"));
            try (ImportPlan plan = new ImportCoordinator().analyze(result.file())) {
                new ImportTransactionService(restored.databasePath().toString(), restored).execute(plan);
            }
            require(expected.equals(streamingValues(restored.databasePath())),
                    "All flushed rows must preserve null, empty, whitespace, Unicode, NUL and overflow text");
            require(rows(source.databasePath(), "SELECT stable_key || '|' || name || '|' || active FROM capture_method")
                            .equals(rows(restored.databasePath(),
                                    "SELECT stable_key || '|' || name || '|' || active FROM capture_method")),
                    "The streaming export must retain custom and archived capture methods");
            assertStoredMedia(restored.databasePath(), restored, 1);
        } finally {
            deleteDirectory(root);
        }
    }

    private static Map<String, List<String>> streamingValues(Path database) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT e.stable_key, e.observations, e.clouds, e.rain,
                            e.thermal_sensation, e.capture_type, cm.stable_key AS method
                     FROM bird_event e LEFT JOIN capture_method cm ON cm.id = e.capture_method_id
                     ORDER BY e.stable_key
                     """)) {
            while (row.next()) {
                result.put(row.getString("stable_key"), java.util.Arrays.asList(
                        row.getString("observations"), row.getString("clouds"), row.getString("rain"),
                        row.getString("thermal_sensation"), row.getString("capture_type"), row.getString("method")));
            }
        }
        return result;
    }

    private static Set<Path> streamingTemporarySheets() throws Exception {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "poifiles");
        if (!Files.isDirectory(directory)) return Set.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("poi-sxssf-sheet"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    public static void captureMethodsSurviveExportsAndOldExportsRemainCompatible() throws Exception {
        Path root = Files.createTempDirectory("ringlog-capture-method-roundtrip-");
        try {
            AppPaths sourcePaths = AppPaths.forDataRoot(root.resolve("source"));
            Path source = sourcePaths.databasePath();
            Database.initialize(source.toString());
            try (Connection connection = Database.getConnection(source.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO capture_method(stable_key, name, active)
                        VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'CEPO MANUAL', 0)
                        """);
                statement.execute("""
                        UPDATE capture_method SET name = 'FILATS ESPECIALES', active = 0
                        WHERE stable_key = '39d6ec44-2b19-4b49-a69c-d2af0aa31f8d'
                        """);
                statement.execute("""
                        INSERT INTO species(stable_key, scientific_name)
                        VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'Turdus philomelos')
                        """);
                statement.execute("""
                        INSERT INTO bird(stable_key, ring_number, species_id)
                        VALUES('cccccccc-cccc-4ccc-8ccc-cccccccccccc', 'METHOD-01', 1)
                        """);
                statement.execute("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date,
                                               capture_type, capture_method_id)
                        VALUES('dddddddd-dddd-4ddd-8ddd-dddddddddddd', 1, 'RINGING', '2026-11-01',
                               'RECAPTURE', (SELECT id FROM capture_method WHERE name='CEPO MANUAL')),
                              ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 1, 'CONTROL', '2026-11-02',
                               NULL, (SELECT id FROM capture_method WHERE name='FILATS ESPECIALES')),
                              ('ffffffff-ffff-4fff-8fff-ffffffffffff', 1, 'CONTROL', '2026-11-03',
                               NULL, NULL)
                        """);
            }
            Path nativeFile = new RingLogExporter(source.toString()).exportTo(root.resolve("native")).file();
            Path backupFile = new BackupExportService(source.toString()).exportTo(root.resolve("backup")).file();
            String methods = "SELECT stable_key || '|' || name || '|' || active FROM capture_method";
            String assignments = """
                    SELECT e.event_date || '|' || COALESCE(cm.stable_key, '<null>') || '|'
                           || COALESCE(cm.name, '<null>') || '|' || COALESCE(e.capture_type, '<null>')
                    FROM bird_event e LEFT JOIN capture_method cm ON cm.id = e.capture_method_id
                    """;
            for (Path file : List.of(nativeFile, backupFile)) {
                AppPaths target = AppPaths.forDataRoot(root.resolve("restore-" + file.getFileName()));
                try (ImportPlan plan = new ImportCoordinator().analyze(file)) {
                    new ImportTransactionService(target.databasePath().toString(), target).execute(plan);
                }
                require(rows(source, methods).equals(rows(target.databasePath(), methods)),
                        "Custom, renamed and archived methods must survive export/restore");
                require(rows(source, assignments).equals(rows(target.databasePath(), assignments)),
                        "Method selection and independent historical classification must survive restoration");
                try (Connection connection = Database.getConnection(target.databasePath().toString());
                     Statement statement = connection.createStatement()) {
                    statement.execute("UPDATE bird_event SET capture_method_id = NULL WHERE event_type='RINGING'");
                }
                Path repeated = distinctImportIdentity(file,
                        root.resolve("conflict-" + file.getFileName()));
                boolean rejected = false;
                try (ImportPlan plan = new ImportCoordinator().analyze(repeated)) {
                    new ImportTransactionService(target.databasePath().toString(), target).execute(plan);
                } catch (ImportExecutionException expected) {
                    rejected = true;
                }
                require(rejected && scalarLong(target.databasePath(), """
                                SELECT COUNT(*) FROM bird_event
                                WHERE event_type='RINGING' AND capture_method_id IS NULL
                                """) == 1,
                        "A conflicting capture method must abort restoration without silently replacing data: "
                                + file.getFileName() + ", rejected=" + rejected);
                Path oldFile = withoutCaptureMethods(file,
                        root.resolve("old-" + file.getFileName()));
                AppPaths oldTarget = AppPaths.forDataRoot(root.resolve("old-restore-" + file.getFileName()));
                try (ImportPlan plan = new ImportCoordinator().analyze(oldFile)) {
                    new ImportTransactionService(oldTarget.databasePath().toString(), oldTarget).execute(plan);
                }
                require(scalarLong(oldTarget.databasePath(),
                                "SELECT COUNT(*) FROM bird_event WHERE capture_method_id IS NOT NULL") == 0,
                        "Files predating capture methods must not invent assignments");
            }
            AppPaths legacyTarget = AppPaths.forDataRoot(root.resolve("legacy-service"));
            new LegacyImportService(legacyTarget.databasePath().toString(), legacyTarget)
                    .importWorkbook(backupFile);
            require(rows(source, methods).equals(rows(legacyTarget.databasePath(), methods))
                            && rows(source, assignments).equals(rows(legacyTarget.databasePath(), assignments)),
                    "The public legacy backup service must preserve the catalogue and assignments too");
        } finally {
            deleteDirectory(root);
        }
    }

    private static Path withoutCaptureMethods(Path source, Path destination) throws Exception {
        try (InputStream input = Files.newInputStream(source);
             Workbook workbook = WorkbookFactory.create(input)) {
            workbook.removeSheetAt(workbook.getSheetIndex("capture_methods"));
            Sheet events = workbook.getSheet("events");
            int column = events.getRow(0).getLastCellNum() - 1;
            require("capture_method_stable_key".equals(events.getRow(0).getCell(column).getStringCellValue()),
                    "The method reference must extend the old event contract");
            for (Row row : events) {
                Cell cell = row.getCell(column);
                if (cell != null) row.removeCell(cell);
            }
            Sheet metadata = workbook.getSheet("metadata");
            for (int index = 1; index <= metadata.getLastRowNum(); index++) {
                Row row = metadata.getRow(index);
                if (row == null) continue;
                String key = row.getCell(0).getStringCellValue();
                if ("schema_version".equals(key)) replaceStringCell(row, 1, "5");
                if ("capture_methods_count".equals(key)) metadata.removeRow(row);
            }
            try (OutputStream output = Files.newOutputStream(destination)) { workbook.write(output); }
        }
        return destination;
    }

    private static Path distinctImportIdentity(Path source, Path destination) throws Exception {
        String exportId = UUID.randomUUID().toString();
        try (InputStream input = Files.newInputStream(source);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet metadata = workbook.getSheet("metadata");
            Row marker = metadata.createRow(metadata.getLastRowNum() + 1);
            marker.createCell(0).setCellValue("test_import_identity");
            marker.createCell(1).setCellValue(java.util.UUID.randomUUID().toString());
            for (Row row : metadata) {
                if ("export_id".equals(row.getCell(0).getStringCellValue())) {
                    replaceStringCell(row, 1, exportId);
                }
            }
            try (OutputStream output = Files.newOutputStream(destination)) { workbook.write(output); }
        }
        try (InputStream input = Files.newInputStream(destination);
             Workbook workbook = WorkbookFactory.create(input)) {
            for (Row row : workbook.getSheet("metadata")) {
                if ("export_id".equals(row.getCell(0).getStringCellValue())) {
                    require(exportId.equals(row.getCell(1).getStringCellValue()),
                            "The rewritten fixture must have a distinct serialized export identity");
                }
            }
        }
        return destination;
    }

    private static void replaceStringCell(Row row, int column, String value) {
        // XSSF can retain an old inline-string element when setCellValue changes its value.
        Cell previous = row.getCell(column);
        if (previous != null) row.removeCell(previous);
        row.createCell(column).setCellValue(value);
    }

    public static void binaryStateUsesZipAndRestoresTraceabilityWithoutWizard()
            throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Native export legacy round-trip integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-native-roundtrip-");
        AppPaths sourcePaths = AppPaths.forDataRoot(root.resolve("source-data"));
        AppPaths targetPaths = AppPaths.forDataRoot(root.resolve("target-data"));
        Path source = sourcePaths.databasePath();
        Path target = targetPaths.databasePath();
        try {
            try (ImportPlan legacy = new ImportCoordinator().analyze(LEGACY_FIXTURE)) {
                new ImportTransactionService(source.toString(), sourcePaths)
                        .execute(legacy);
            }
            long conflict = scalarLong(source, """
                    SELECT id FROM migration_conflict
                    WHERE status = 'PENDING_REVIEW' ORDER BY id LIMIT 1
                    """);
            new ConflictResolutionService(source.toString()).resolve(
                    conflict, ResolutionRequest.canonical("Verificado para round-trip")
            );
            addEventPhoto(source, sourcePaths);

            ExportResult exported = new RingLogExporter(
                    source.toString(), new MediaPathResolver(sourcePaths)
            )
                    .exportTo(root.resolve("ringlog-native"));
            require(exported.file().toString().endsWith(".zip")
                            && Files.isRegularFile(exported.file()),
                    "Physical event/unassigned photos must force a native ZIP");
            require(exported.binaryCount() == 10,
                    "The ZIP must contain one event photo and nine unassigned photos");

            Set<String> sourceConflictEvidence = rows(source, """
                    SELECT stable_key || '|' || conflict_key || '|' || status || '|'
                           || COALESCE(resolution_type, '<null>') || '|'
                           || COALESCE(resolution_value, '<null>') || '|'
                           || COALESCE(canonical_value, '<null>') || '|'
                           || COALESCE(alternative_value, '<null>') || '|'
                           || COALESCE(canonical_snapshot, '<null>') || '|'
                           || COALESCE(alternative_snapshot, '<null>') || '|'
                           || COALESCE(original_note, '<null>')
                    FROM migration_conflict
                    """);

            try (ImportPlan nativePlan = new ImportCoordinator().analyze(exported.file())) {
                require(nativePlan.profile().format() == ImportFormat.RINGLOG_EXPORT_V1,
                        "The native ZIP must be detected exactly as RingLog Export v1");
                require(!nativePlan.requiresConflictReview(),
                        "Native restoration must never launch the conflict wizard");
                require(nativePlan.nativeModel().conflicts().size() == 8,
                        "Pending and resolved conflict state must be staged for restoration");

                long sourceEvents = count(source, "bird_event");
                long sourcePhotos = count(source, "event_photo");
                ImportTransactionService sourceMerge = new ImportTransactionService(
                        source.toString(), sourcePaths
                );
                require(sourceMerge.execute(nativePlan).status()
                                == ImportExecutionResult.Status.APPLIED,
                        "A native export must merge safely over its source database");
                require(count(source, "bird_event") == sourceEvents
                                && count(source, "event_photo") == sourcePhotos,
                        "Native merge must not duplicate existing stable entities");

                ImportTransactionService restore = new ImportTransactionService(
                        target.toString(), targetPaths
                );
                require(restore.execute(nativePlan).status()
                                == ImportExecutionResult.Status.APPLIED,
                        "The first native restore must be applied");
                assertCoreStableKeysEqual(source, target);
                for (String table : List.of(
                        "event_source_alias", "legacy_source_record", "legacy_unassigned_photo",
                        "migration_audit", "migration_conflict", "migration_warning"
                )) {
                    require(stableKeys(source, table).equals(stableKeys(target, table)),
                            "Native restore lost stable state from " + table);
                }
                require(sourceConflictEvidence.equals(rows(target, """
                        SELECT stable_key || '|' || conflict_key || '|' || status || '|'
                               || COALESCE(resolution_type, '<null>') || '|'
                               || COALESCE(resolution_value, '<null>') || '|'
                               || COALESCE(canonical_value, '<null>') || '|'
                               || COALESCE(alternative_value, '<null>') || '|'
                               || COALESCE(canonical_snapshot, '<null>') || '|'
                               || COALESCE(alternative_snapshot, '<null>') || '|'
                               || COALESCE(original_note, '<null>')
                        FROM migration_conflict
                        """)), "Conflict evidence/resolution changed during native restore");
                assertStoredMedia(target, targetPaths, 10);

                long events = count(target, "bird_event");
                long batches = count(target, "import_batch");
                require(restore.execute(nativePlan).status()
                                == ImportExecutionResult.Status.ALREADY_IMPORTED,
                        "The same export_id must be recognized on a second restore");
                require(count(target, "bird_event") == events
                                && count(target, "import_batch") == batches,
                        "A repeated native restore must create no duplicate state");
            }

            ExportResult second = new RingLogExporter(
                    target.toString(), new MediaPathResolver(targetPaths)
            )
                    .exportTo(root.resolve("ringlog-native-second"));
            require(second.file().toString().endsWith(".zip"),
                    "Restored physical state must remain losslessly exportable as ZIP");
            require(stableKeys(source, "bird_event").equals(stableKeys(target, "bird_event")),
                    "export -> import merge -> export must retain event identities");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void mediaFreeStateMayUsePlainXlsx() throws Exception {
        Path root = Files.createTempDirectory("ringlog-native-xlsx-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("empty-data"));
            Path database = paths.databasePath();
            Database.initialize(database.toString());
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO place(
                            stable_key, name, locality, autonomous_community, country
                        ) VALUES (
                            '88888888-8888-4888-8888-888888888888',
                            'Els Rafals', 'Pollença', 'Illes Balears', 'España'
                        )
                        """);
            }
            ExportResult exported = new RingLogExporter(
                    database.toString(), new MediaPathResolver(paths)
            )
                    .exportTo(root.resolve("empty-export"));
            require(exported.file().toString().endsWith(".xlsx")
                            && exported.binaryCount() == 0,
                    "A native export without required binaries may be a plain XLSX");
            require(new WorkbookFormatDetector().detect(exported.file()).format()
                            == ImportFormat.RINGLOG_EXPORT_V1,
                    "The media-free workbook must remain a valid native profile");

            AppPaths restoredPaths = AppPaths.forDataRoot(root.resolve("restored-data"));
            try (ImportPlan plan = new ImportCoordinator().analyze(exported.file())) {
                new ImportTransactionService(
                        restoredPaths.databasePath().toString(), restoredPaths
                ).execute(plan);
            }
            require(rows(restoredPaths.databasePath(), """
                    SELECT stable_key || '|' || autonomous_community || '|' || country
                    FROM place
                    """).equals(Set.of(
                            "88888888-8888-4888-8888-888888888888|Illes Balears|España"
                    )), "Native export/import must preserve the place region and country");

            Path legacyHeaderExport = withoutPlaceRegionColumns(
                    exported.file(), root.resolve("ringlog-export-v1-schema4.xlsx")
            );
            AppPaths legacyRestore = AppPaths.forDataRoot(root.resolve("legacy-restore"));
            try (ImportPlan plan = new ImportCoordinator().analyze(legacyHeaderExport)) {
                require(plan.profile().format() == ImportFormat.RINGLOG_EXPORT_V1,
                        "A previous schema-v4 RingLog Export v1 must remain recognized");
                new ImportTransactionService(
                        legacyRestore.databasePath().toString(), legacyRestore
                ).execute(plan);
            }
            require(count(legacyRestore.databasePath(), "place") == 1,
                    "A previous native export must still restore its places");
            require(rows(legacyRestore.databasePath(), """
                    SELECT COALESCE(autonomous_community, '<null>') || '|'
                           || COALESCE(country, '<null>')
                    FROM place
                    """).equals(Set.of("<null>|<null>")),
                    "Fields absent from an old export must restore safely as null");
        } finally {
            deleteDirectory(root);
        }
    }

    private static Path withoutPlaceRegionColumns(Path source, Path destination)
            throws Exception {
        try (InputStream input = Files.newInputStream(source);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet places = workbook.getSheet("places");
            for (int rowIndex = 0; rowIndex <= places.getLastRowNum(); rowIndex++) {
                Row row = places.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                for (int target = 3; target <= 10; target++) {
                    Cell sourceCell = row.getCell(target + 2);
                    replaceStringCell(row, target, sourceCell.getStringCellValue());
                }
                for (int column : new int[]{11, 12}) {
                    Cell cell = row.getCell(column);
                    if (cell != null) {
                        row.removeCell(cell);
                    }
                }
            }
            Sheet metadata = workbook.getSheet("metadata");
            for (int index = 1; index <= metadata.getLastRowNum(); index++) {
                Row row = metadata.getRow(index);
                if (row != null && "schema_version".equals(row.getCell(0).getStringCellValue())) {
                    replaceStringCell(row, 1, "4");
                    break;
                }
            }
            try (OutputStream output = Files.newOutputStream(destination)) {
                workbook.write(output);
            }
        }
        return destination;
    }

    private static void addEventPhoto(Path database, AppPaths paths) throws Exception {
        byte[] content = "native-event-photo".getBytes(StandardCharsets.UTF_8);
        Path file = paths.eventPhotosDirectory().resolve("fixture/event-photo.jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        try (Connection connection = Database.getConnection(database.toString());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO event_photo(
                         stable_key, event_id, file_name, file_path, mime_type,
                         source_name, source_reference, content_sha256, content_size,
                         created_at
                     ) VALUES (
                         '77777777-7777-4777-8777-777777777778', 1,
                         'event-photo.jpg', ?, 'image/jpeg', 'TEST', 'TEST:PHOTO:1',
                         ?, ?, '2026-08-26 18:00:00'
                     )
                     """)) {
            statement.setString(1, new MediaPathResolver(paths)
                    .toEventReference(PhotoArea.EVENTS, file));
            statement.setString(2, sha256(content));
            statement.setLong(3, content.length);
            statement.executeUpdate();
        }
    }

    private static void assertCoreStableKeysEqual(Path source, Path target) throws Exception {
        for (String table : List.of("species", "bird", "place", "bird_event", "event_photo")) {
            require(stableKeys(source, table).equals(stableKeys(target, table)),
                    "Stable keys changed in " + table);
        }
    }

    private static void assertStoredMedia(
            Path database,
            AppPaths paths,
            int expected
    ) throws Exception {
        int found = 0;
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            for (String table : List.of("event_photo", "legacy_unassigned_photo")) {
                try (ResultSet result = statement.executeQuery(
                        "SELECT file_path, content_sha256, content_size FROM " + table
                )) {
                    while (result.next()) {
                        String reference = result.getString(1);
                        require(!Path.of(reference).isAbsolute(),
                                "Native restoration must keep schema v4 references relative");
                        Path file = "event_photo".equals(table)
                                ? new MediaPathResolver(paths).resolveEventPhoto(reference)
                                : new MediaPathResolver(paths).resolveUnassignedPhoto(reference);
                        require(Files.isRegularFile(file)
                                        && sha256(Files.readAllBytes(file)).equals(result.getString(2))
                                        && Files.size(file) == result.getLong(3),
                                "Restored media bytes/hash/size are not lossless");
                        found++;
                    }
                }
            }
        }
        require(found == expected, "Expected every native binary to be restored");
    }

    private static Set<String> stableKeys(Path database, String table) throws Exception {
        return rows(database, "SELECT stable_key FROM " + table);
    }

    private static Set<String> rows(Path database, String sql) throws Exception {
        Set<String> values = new TreeSet<>();
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static long scalarLong(Path database, String sql) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Expected one scalar value");
            return result.getLong(1);
        }
    }

    private static long count(Path database, String table) throws Exception {
        return scalarLong(database, "SELECT COUNT(*) FROM " + table);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
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
        repeatedLongTextRemainsSelfVerifyingAndLossless();
        largeStreamingExportPreservesFlushedRowsOverflowTextAndBinaries();
        captureMethodsSurviveExportsAndOldExportsRemainCompatible();
        binaryStateUsesZipAndRestoresTraceabilityWithoutWizard();
        mediaFreeStateMayUsePlainXlsx();
        System.out.println("NativeExportRoundTripTest: PASS");
    }
}

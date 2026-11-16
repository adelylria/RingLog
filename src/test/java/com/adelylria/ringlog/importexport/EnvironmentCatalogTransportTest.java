package com.adelylria.ringlog.importexport;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.backup.BackupExportService;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importer.LegacyImportService;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.model.EnvironmentDefaults;
import com.adelylria.ringlog.storage.AppPaths;

/** Catalogue snapshots and historical weather survive every supported restore path. */
public final class EnvironmentCatalogTransportTest {
    private static final String CATALOG = "SELECT stable_key || '|' || category || '|' || name || '|' || active || '|' || sort_order FROM environment_option";
    private static final String WEATHER = "SELECT clouds || '|' || rain || '|' || thermal_sensation || '|' || wind || '|' || temperature_celsius FROM bird_event";

    private EnvironmentCatalogTransportTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-transports-");
        try {
            AppPaths source = AppPaths.forDataRoot(root.resolve("source"));
            Database.initialize(source.databasePath().toString());
            String firstSeed = EnvironmentDefaults.options().get(0).stableKey();
            execute(source, "UPDATE environment_option SET name='Despejado', active=0, sort_order=70 WHERE stable_key='" + firstSeed + "'",
                    "INSERT INTO environment_option(stable_key,category,name,active,sort_order) VALUES('11111111-1111-4111-8111-111111111111','CLOUDS','Boira local',1,8)",
                    "INSERT INTO species(stable_key,scientific_name) VALUES('22222222-2222-4222-8222-222222222222','Entorno fixture')",
                    "INSERT INTO bird(stable_key,ring_number,species_id) VALUES('33333333-3333-4333-8333-333333333333','ENV-01',1)",
                    "INSERT INTO bird_event(stable_key,bird_id,event_type,event_date,source_name,source_reference,clouds,rain,thermal_sensation,wind,temperature_celsius) VALUES('44444444-4444-4444-8444-444444444444',1,'RINGING','2026-11-16','Env fixture','ENV:1','Si','NO','18.2','4',22)",
                    // Old Mallorcan labels are historical data even when the current catalogue is Spanish.
                    "INSERT INTO bird_event(stable_key,bird_id,event_type,event_date,source_name,source_reference,clouds,rain,thermal_sensation,wind,temperature_celsius) VALUES('66666666-6666-4666-8666-666666666666',1,'CONTROL','2026-11-17','Env fixture','ENV:2','25-75%','Fluixa','M. Fred','Fluix',-5)");
            Path nativeFile = new RingLogExporter(source.databasePath().toString()).exportTo(root.resolve("native")).file();
            Path backup = new BackupExportService(source.databasePath().toString()).exportTo(root.resolve("backup")).file();
            Set<String> catalog = rows(source, CATALOG);
            Set<String> weather = rows(source, WEATHER);
            for (Path file : List.of(nativeFile, backup)) {
                boolean nativeFormat = file.equals(nativeFile);
                AppPaths target = AppPaths.forDataRoot(root.resolve("restore-" + file.getFileName()));
                try (ImportPlan plan = new ImportCoordinator().analyze(file)) {
                    ImportTransactionService restore = new ImportTransactionService(target.databasePath().toString(), target);
                    require(restore.execute(plan).status() == ImportExecutionResult.Status.APPLIED, "First restore applies");
                    require(restore.execute(plan).status() == ImportExecutionResult.Status.ALREADY_IMPORTED,
                            "Repeated restore must not duplicate or reapply catalogue preferences");
                    Path changed = rewrite(file, root.resolve("changed-" + file.getFileName()), workbook ->
                            put(workbook.getSheet("environment_options").getRow(1), 4, "777", nativeFormat));
                    try (ImportPlan changedPlan = new ImportCoordinator().analyze(changed)) {
                        require(!plan.fingerprint().equals(changedPlan.fingerprint()), "Catalogue-only changes affect content identity");
                    }
                }
                require(catalog.equals(rows(target, CATALOG)), "Custom names, archived defaults and order round-trip exactly");
                require(weather.equals(rows(target, WEATHER)), "Historical arbitrary texts, old Mallorcan labels and numeric temperatures remain unchanged with a Spanish catalogue");
                localChangesAreNotOverwritten(root, file, firstSeed);
                oldCopyDoesNotResetLocalCatalogue(root, file, nativeFormat, weather);
                rejectsInvalidCatalogues(root, file, nativeFormat);
            }
            AppPaths legacy = AppPaths.forDataRoot(root.resolve("legacy-service"));
            LegacyImportService oldService = new LegacyImportService(legacy.databasePath().toString(), legacy);
            oldService.importWorkbook(backup);
            oldService.importWorkbook(backup);
            require(catalog.equals(rows(legacy, CATALOG)), "Legacy service restores and repeats catalogue without duplicates");
            require(weather.equals(rows(legacy, WEATHER)), "Legacy service retains historical weather verbatim");
            System.out.println("EnvironmentCatalogTransportTest: PASS");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void localChangesAreNotOverwritten(Path root, Path file, String seed) throws Exception {
        AppPaths target = AppPaths.forDataRoot(root.resolve("local-" + file.getFileName()));
        Database.initialize(target.databasePath().toString());
        execute(target, "UPDATE environment_option SET active=0 WHERE stable_key='" + seed + "'");
        Set<String> before = rows(target, CATALOG);
        try (ImportPlan plan = new ImportCoordinator().analyze(file)) {
            try {
                new ImportTransactionService(target.databasePath().toString(), target).execute(plan);
                throw new AssertionError("A conflicting local catalogue preference must not be overwritten");
            } catch (ImportExecutionException expected) {
                require(before.equals(rows(target, CATALOG)), "Catalogue conflict must roll back every catalogue change");
                require(rows(target, "SELECT stable_key FROM bird_event").isEmpty(), "Catalogue conflict must roll back event restore too");
            }
        }
    }

    private static void oldCopyDoesNotResetLocalCatalogue(Path root, Path file, boolean nativeFormat,
            Set<String> weather) throws Exception {
        Path oldCopy = rewrite(file, root.resolve("old-" + file.getFileName()), workbook -> {
            workbook.removeSheetAt(workbook.getSheetIndex("environment_options"));
            Sheet metadata = workbook.getSheet("metadata");
            for (Row row : metadata) {
                String key = row.getCell(0).getStringCellValue();
                if ("environment_options_count".equals(key)) {
                    row.getCell(0).setCellValue("unused_old_copy_count");
                }
                if (nativeFormat && "schema_version".equals(key)) row.getCell(1).setCellValue("7");
            }
        });
        AppPaths target = AppPaths.forDataRoot(root.resolve("old-target-" + file.getFileName()));
        Database.initialize(target.databasePath().toString());
        execute(target, "UPDATE environment_option SET active=0 WHERE category='WIND'",
                "INSERT INTO environment_option(stable_key,category,name,active,sort_order) VALUES('55555555-5555-4555-8555-555555555555','RAIN','Local antigua',1,20)");
        Set<String> before = rows(target, CATALOG);
        try (ImportPlan plan = new ImportCoordinator().analyze(oldCopy)) {
            new ImportTransactionService(target.databasePath().toString(), target).execute(plan);
        }
        require(before.equals(rows(target, CATALOG)), "Old copies without a catalogue must retain all local preferences");
        require(weather.equals(rows(target, WEATHER)), "Old copies retain their historical weather");
    }

    private static void rejectsInvalidCatalogues(Path root, Path file, boolean nativeFormat) throws Exception {
        for (String invalid : List.of("category", "active", "negative", "fraction", "duplicate")) {
            Path changed = rewrite(file, root.resolve("invalid-" + invalid + '-' + file.getFileName()), workbook -> {
                Sheet sheet = workbook.getSheet("environment_options");
                Row row = sheet.getRow(1);
                switch (invalid) {
                    case "category" -> put(row, 1, "UNKNOWN", nativeFormat);
                    case "active" -> put(row, 3, "2", nativeFormat);
                    case "negative" -> put(row, 4, "-1", nativeFormat);
                    case "fraction" -> put(row, 4, "1.5", nativeFormat);
                    case "duplicate" -> {
                        row.getCell(1).setCellValue(sheet.getRow(2).getCell(1).getStringCellValue());
                        row.getCell(2).setCellValue(sheet.getRow(2).getCell(2).getStringCellValue());
                    }
                    default -> throw new AssertionError(invalid);
                }
            });
            try (ImportPlan ignored = new ImportCoordinator().analyze(changed)) {
                throw new AssertionError("Invalid environment catalogue must fail validation: " + invalid);
            } catch (ImportValidationException expected) {
                // Reject before opening a destination database or changing records.
            }
        }
    }

    private static Path rewrite(Path file, Path destination, Consumer<Workbook> edit) throws Exception {
        try (InputStream input = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(input)) {
            edit.accept(workbook);
            try (OutputStream output = Files.newOutputStream(destination)) { workbook.write(output); }
        }
        return destination;
    }

    private static void put(Row row, int index, String value, boolean encoded) {
        Cell previous = row.getCell(index);
        if (previous != null) row.removeCell(previous);
        Cell cell = row.createCell(index);
        cell.setCellValue(encoded ? "@V:" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) : value);
    }

    private static void execute(AppPaths paths, String... statements) throws Exception {
        try (Connection connection = Database.getConnection(paths.databasePath().toString()); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.executeUpdate(sql);
        }
    }

    private static Set<String> rows(AppPaths paths, String sql) throws Exception {
        Set<String> rows = new TreeSet<>();
        try (Connection connection = Database.getConnection(paths.databasePath().toString());
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

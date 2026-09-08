package com.adelylria.ringlog.importexport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.legacy.LegacyV5WorkbookReader;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

/** Integration tests against the authoritative on-disk v5.2 migrator output. */
public final class LegacyV5WorkbookReaderTest {

    private static final Path REAL_FIXTURE = LegacyV52TestFixture.path();

    private LegacyV5WorkbookReaderTest() {
    }

    public static void readsTheRealV52DatasetWithoutReconciliation() throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Legacy v5.2 workbook integration")) {
            return;
        }
        require(Files.isRegularFile(REAL_FIXTURE),
                "The approved real v5.2 fixture is required for this integration test");
        LegacyImportModel model = read(REAL_FIXTURE);

        require(model.species().size() == 4, "Expected 4 authoritative species rows");
        require(model.birds().size() == 271, "Expected 271 authoritative bird rows");
        require(model.places().size() == 4, "Expected 4 authoritative place rows");
        require(model.events().size() == 289,
                "Expected exactly 289 canonical events; alternatives must not become events");
        require(model.events().stream().filter(row -> "RINGING".equals(row.eventType())).count()
                        == 271,
                "Expected 271 ringing events");
        require(model.events().stream().filter(row -> "CONTROL".equals(row.eventType())).count()
                        == 8,
                "Expected 8 controls");
        require(model.events().stream().filter(row -> "RECOVERY".equals(row.eventType())).count()
                        == 10,
                "Expected 10 recoveries");
        require(model.conflicts().size() == 8, "Expected all 8 structured conflicts");
        require(model.conflicts().stream()
                        .filter(row -> "PENDING_REVIEW".equals(row.status())).count() == 7,
                "Expected 7 pending conflicts");
        require(model.conflicts().stream()
                        .filter(row -> "RESOLVED".equals(row.status())).count() == 1,
                "Expected the confirmed typo to remain resolved");
        require(model.audit().size() == 4275, "Expected all 4275 audit rows");
        require(model.audit().stream().noneMatch(row -> "LOST".equals(row.status())
                        || "UNMAPPED".equals(row.status())),
                "The accepted v5.2 dataset must contain no LOST/UNMAPPED audit rows");
        require(model.unassignedPhotos().size() == 9,
                "Expected all 9 unassigned photos without event association");
        require(model.unassignedPhotos().stream().allMatch(row ->
                        row.declaredSha256().equalsIgnoreCase(row.contentSha256())),
                "Every physical unassigned photo must match its declared SHA-256");

        try (InputStream input = Files.newInputStream(REAL_FIXTURE);
             Workbook workbook = WorkbookFactory.create(input)) {
            String raw = workbook.getSheet("source_records").getRow(1).getCell(4)
                    .getStringCellValue();
            require(raw.equals(model.sourceRecords().get(0).rawPayload()),
                    "Raw source payload must be preserved without trimming or rewriting");
            Row conflict = workbook.getSheet("migration_conflicts").getRow(1);
            require(cellText(conflict.getCell(10)).equals(
                            model.conflicts().get(0).canonicalEventSnapshot())
                            && cellText(conflict.getCell(11)).equals(
                            model.conflicts().get(0).alternativeEventSnapshot())
                            && cellText(conflict.getCell(14)).equals(
                            model.conflicts().get(0).note()),
                    "Conflict snapshots and original note must remain byte-for-byte text values");
        }
    }

    public static void rejectsLossDuplicateKeysBrokenReferencesAndChangedMedia()
            throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Legacy v5.2 validation integration")) {
            return;
        }
        Path root = copyRealFixture("ringlog-v5-invalid-");
        try {
            Path lostWorkbook = root.resolve("lost/ringlog-import.xlsx");
            copyFixtureTree(root.resolve("base"), root.resolve("lost"));
            editWorkbook(lostWorkbook, workbook -> {
                workbook.getSheet("migration_audit").getRow(1).getCell(8)
                        .setCellValue("LOST");
                setMetadata(workbook, "audit_lost_count", "1");
            });
            requireFailure(() -> read(lostWorkbook), "lost");

            Path duplicateWorkbook = root.resolve("duplicate/ringlog-import.xlsx");
            copyFixtureTree(root.resolve("base"), root.resolve("duplicate"));
            editWorkbook(duplicateWorkbook, workbook -> {
                Sheet events = workbook.getSheet("events");
                events.getRow(2).getCell(0).setCellValue(events.getRow(1).getCell(0)
                        .getStringCellValue());
            });
            requireFailure(() -> read(duplicateWorkbook), "event_key");

            Path referenceWorkbook = root.resolve("reference/ringlog-import.xlsx");
            copyFixtureTree(root.resolve("base"), root.resolve("reference"));
            editWorkbook(referenceWorkbook, workbook -> workbook.getSheet("events")
                    .getRow(1).getCell(1).setCellValue("ANILLA-INEXISTENTE"));
            requireFailure(() -> read(referenceWorkbook), "anilla");

            Path mediaWorkbook = root.resolve("media/ringlog-import.xlsx");
            copyFixtureTree(root.resolve("base"), root.resolve("media"));
            String relative;
            try (InputStream input = Files.newInputStream(mediaWorkbook);
                 Workbook workbook = WorkbookFactory.create(input)) {
                relative = workbook.getSheet("unassigned_photos").getRow(1).getCell(1)
                        .getStringCellValue();
            }
            Files.write(root.resolve("media").resolve(relative), new byte[]{9},
                    java.nio.file.StandardOpenOption.APPEND);
            requireFailure(() -> read(mediaWorkbook), "sha-256");
        } finally {
            deleteDirectory(root);
        }
    }

    private static LegacyImportModel read(Path workbook) throws Exception {
        try (ImportSource source = ImportSource.open(workbook)) {
            return new LegacyV5WorkbookReader().read(source);
        }
    }

    private static Path copyRealFixture(String prefix) throws IOException {
        Path root = Files.createTempDirectory(prefix);
        copyFixtureTree(REAL_FIXTURE.getParent(), root.resolve("base"));
        return root;
    }

    private static void copyFixtureTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void editWorkbook(Path path, WorkbookEdit edit) throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), "edited-", ".xlsx");
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            edit.apply(workbook);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                workbook.write(output);
            }
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void setMetadata(Workbook workbook, String key, String value) {
        for (Row row : workbook.getSheet("metadata")) {
            if (row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                row.getCell(1).setCellValue(value);
                return;
            }
        }
        throw new AssertionError("Missing metadata key in fixture: " + key);
    }

    private static String cellText(Cell cell) {
        return cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK
                ? null
                : cell.getStringCellValue();
    }

    private static void requireFailure(ThrowingAction action, String fragment) throws Exception {
        try {
            action.run();
        } catch (ImportValidationException expected) {
            String message = expected.getMessage() == null
                    ? ""
                    : expected.getMessage().toLowerCase(java.util.Locale.ROOT);
            require(message.contains(fragment.toLowerCase(java.util.Locale.ROOT)),
                    "Validation error must explain " + fragment + ": " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected legacy validation failure for " + fragment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface WorkbookEdit {
        void apply(Workbook workbook) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        readsTheRealV52DatasetWithoutReconciliation();
        rejectsLossDuplicateKeysBrokenReferencesAndChangedMedia();
        System.out.println("LegacyV5WorkbookReaderTest: PASS");
    }
}

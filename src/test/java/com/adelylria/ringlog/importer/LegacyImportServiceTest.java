package com.adelylria.ringlog.importer;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

public final class LegacyImportServiceTest {

    private static final String[] EVENT_HEADERS = {
            "event_key", "ring_number", "event_type", "event_date", "event_time",
            "place_key", "location_text", "sex_code", "age_euring_code",
            "fat_score", "muscle_score", "ringer_initials", "status",
            "reproductive_status", "moult_intensity", "moult_extension",
            "bird_condition", "return_status", "wing", "p3", "torso", "weight",
            "observations", "clouds", "rain", "thermal_sensation", "wind",
            "capture_type", "is_dead", "source_name", "source_reference",
            "source_aliases"
    };

    private LegacyImportServiceTest() {
    }

    public static void previewExcludesUnassignedPhotos() throws Exception {
        Fixture fixture = createFixture(false, "2");
        try {
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );

            ImportPreview preview = service.preview(fixture.workbook());

            require(preview.speciesCount() == 1, "Preview should count species rows");
            require(preview.eventsCount() == 1, "Preview should count event rows");
            require(preview.photosCount() == 1,
                    "Only event-linked photos should be counted");
            require(preview.warnings().size() == 2,
                    "Migration warnings should be available before importing");
            require(preview.reviewWarningCount() == 1,
                    "Review warnings should require an explicit UI confirmation");
        } finally {
            fixture.delete();
        }
    }

    public static void importsVersion4MigratorWorkbook() throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Database.initialize(fixture.database().toString());
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );

            ImportPreview preview = service.preview(fixture.workbook());
            require(preview.speciesCount() == 1, "Version 4 species should be readable");
            require(preview.eventsCount() == 1, "Version 4 events should be readable");
            require(preview.photosCount() == 1,
                    "Only version 4 photos linked to events should be counted");

            ImportResult result = service.importWorkbook(fixture.workbook());
            require(result.eventsCreated() == 1, "The version 4 event should be imported");
            require(result.photosCreated() == 1,
                    "The linked version 4 photo should be imported");

            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement();
                 ResultSet event = statement.executeQuery(
                         "SELECT observations FROM bird_event"
                 )) {
                require(event.next(), "The version 4 event should be stored");
                require("Primera línea\n  Segunda línea".equals(event.getString(1)),
                        "Version 4 field-note whitespace should be preserved");
                require(count(statement, "event_photo") == 1,
                        "Unassigned version 4 photos must remain ignored");
            }
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookWithLostAuditData() throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path unsafeWorkbook = fixture.root().resolve("ringlog-import-with-loss.xlsx");
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                setMetadataValue(workbook.getSheet("metadata"), "audit_lost_count", "1");
                setMetadataValue(workbook.getSheet("metadata"), "audit_conflict_count", "0");
                workbook.getSheet("migration_audit").getRow(1).getCell(8)
                        .setCellValue("LOST");
                try (OutputStream output = Files.newOutputStream(unsafeWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(unsafeWorkbook),
                    "Version 4 files with lost audit data must be rejected"
            );
            require(!Files.exists(fixture.database()),
                    "Rejecting unsafe audit data must not create a database");
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookDisguisedAsVersion2() throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path disguisedWorkbook = fixture.root().resolve("ringlog-import-disguised-v2.xlsx");
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                setMetadataValue(workbook.getSheet("metadata"), "format_version", "2");
                setMetadataValue(workbook.getSheet("metadata"), "audit_lost_count", "1");
                setMetadataValue(workbook.getSheet("metadata"), "audit_conflict_count", "0");
                workbook.getSheet("migration_audit").getRow(1).getCell(8)
                        .setCellValue("LOST");
                try (OutputStream output = Files.newOutputStream(disguisedWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(disguisedWorkbook),
                    "Version 4 audit data must not be bypassed by declaring version 2"
            );
            require(!Files.exists(fixture.database()),
                    "Rejecting a disguised version 4 workbook must not create a database");
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookWithMalformedSourceRecord() throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path malformedWorkbook = fixture.root().resolve(
                    "ringlog-import-malformed-source.xlsx"
            );
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                Row source = workbook.getSheet("source_records").getRow(1);
                for (int column = 1; column <= 4; column++) {
                    Cell cell = source.getCell(column);
                    if (cell != null) {
                        source.removeCell(cell);
                    }
                }
                try (OutputStream output = Files.newOutputStream(malformedWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(malformedWorkbook),
                    "Incomplete version 4 source records must be rejected"
            );
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookWithMalformedUnassignedPhoto()
            throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path malformedWorkbook = fixture.root().resolve(
                    "ringlog-import-malformed-unassigned-photo.xlsx"
            );
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                Row photo = workbook.getSheet("unassigned_photos").getRow(1);
                photo.removeCell(photo.getCell(6));
                try (OutputStream output = Files.newOutputStream(malformedWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(malformedWorkbook),
                    "Incomplete unassigned version 4 photos must be rejected"
            );
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookWithMalformedAuditRecord()
            throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path malformedWorkbook = fixture.root().resolve(
                    "ringlog-import-malformed-audit.xlsx"
            );
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                Row audit = workbook.getSheet("migration_audit").getRow(1);
                audit.removeCell(audit.getCell(2));
                audit.removeCell(audit.getCell(3));
                try (OutputStream output = Files.newOutputStream(malformedWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(malformedWorkbook),
                    "Incomplete version 4 audit records must be rejected"
            );
        } finally {
            fixture.delete();
        }
    }

    public static void rejectsVersion4WorkbookWithBackupTextContent()
            throws Exception {
        Fixture fixture = createFixture(false, "4");
        try {
            Path mixedWorkbook = fixture.root().resolve("ringlog-import-mixed-v4.xlsx");
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                writeSheet(
                        workbook,
                        "text_content",
                        new String[]{"text_key", "chunk_index", "chunk_count", "sha256",
                                "data"},
                        List.of()
                );
                try (OutputStream output = Files.newOutputStream(mixedWorkbook)) {
                    workbook.write(output);
                }
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(mixedWorkbook),
                    "Version 4 must not silently adopt version 3 text storage"
            );
        } finally {
            fixture.delete();
        }
    }

    public static void importsRowsAndOnlyEventLinkedPhotos() throws Exception {
        Fixture fixture = createFixture(false, "2");
        try {
            Database.initialize(fixture.database().toString());
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );

            ImportResult result = service.importWorkbook(fixture.workbook());

            require(result.speciesCreated() == 1, "One species should be created");
            require(result.birdsCreated() == 1, "One bird should be created");
            require(result.placesCreated() == 1, "One place should be created");
            require(result.eventsCreated() == 1, "One event should be created");
            require(result.photosCreated() == 1, "One linked photo should be created");

            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "species") == 1, "Species row was not imported");
                require(count(statement, "bird") == 1, "Bird row was not imported");
                require(count(statement, "place") == 1, "Place row was not imported");
                require(count(statement, "bird_event") == 1, "Event row was not imported");
                require(count(statement, "event_photo") == 1,
                        "Only the linked photo should become an event_photo");

                try (ResultSet event = statement.executeQuery("""
                        SELECT event_date, event_time, source_name, source_reference,
                               observations, weight, is_dead
                        FROM bird_event
                        """)) {
                    require(event.next(), "Imported event should be readable");
                    require("2025-01-02".equals(event.getString("event_date")),
                            "Excel dates should be stored as ISO dates");
                    require("07:00".equals(event.getString("event_time")),
                            "Whole-hour text cells with Excel time styling should stay intact");
                    require("Access".equals(event.getString("source_name")),
                            "Import provenance should be preserved");
                    require("ACCESS:CAPTURAS:1".equals(event.getString("source_reference")),
                            "Stable source reference should be preserved");
                    require("Observación histórica".equals(event.getString("observations")),
                            "Event notes should be imported");
                    require(Math.abs(event.getDouble("weight") - 71.5) < 0.001,
                            "Event measurements should be imported");
                    require(event.getInt("is_dead") == 0,
                            "Boolean event values should be imported");
                }

                try (ResultSet photo = statement.executeQuery(
                        "SELECT file_name, file_path, mime_type FROM event_photo"
                )) {
                    require(photo.next(), "Linked photo should be readable");
                    require("linked.jpg".equals(photo.getString("file_name")),
                            "Original linked photo name should be preserved");
                    String reference = photo.getString("file_path");
                    require(reference.startsWith("events/")
                                    && !Path.of(reference).isAbsolute(),
                            "Schema v4 must persist a managed relative photo reference");
                    Path copied = new MediaPathResolver(
                            AppPaths.forDataRoot(fixture.managedPhotos())
                    ).resolveEventPhoto(reference);
                    require(Files.isRegularFile(copied),
                            "Linked photo should be copied into RingLog storage");
                    require(copied.startsWith(fixture.managedPhotos()),
                            "Linked photos should live in RingLog-managed storage");
                }
            }

            require(!containsFileNamed(fixture.managedPhotos(), "orphan.jpg"),
                    "Unassigned photos must be ignored completely");
        } finally {
            fixture.delete();
        }
    }

    public static void reimportSkipsExistingEventsAndKeepsUserChanges()
            throws Exception {
        Fixture fixture = createFixture(false, "2");
        try {
            Database.initialize(fixture.database().toString());
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            service.importWorkbook(fixture.workbook());

            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE bird_event
                        SET observations = 'Editado por la persona usuaria'
                        WHERE source_reference = 'ACCESS:CAPTURAS:1'
                        """);
            }

            ImportResult result = service.importWorkbook(fixture.workbook());

            require(result.eventsCreated() == 0,
                    "Reimport must not duplicate a source event");
            require(result.eventsSkipped() == 1,
                    "The existing source event should be reported as skipped");
            require(result.photosCreated() == 0 && result.photosSkipped() == 1,
                    "Reimport must not duplicate linked photos");
            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "bird_event") == 1,
                        "Reimport should keep exactly one event");
                try (ResultSet event = statement.executeQuery(
                        "SELECT observations FROM bird_event"
                )) {
                    require(event.next()
                                    && "Editado por la persona usuaria".equals(event.getString(1)),
                            "Reimport should preserve later user edits");
                }
            }
        } finally {
            fixture.delete();
        }
    }

    public static void recognizesRowsImportedByAnOlderRingLogVersion()
            throws Exception {
        Fixture fixture = createFixture(false, "2");
        try {
            Database.initialize(fixture.database().toString());
            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO species(stable_key, code, scientific_name, common_name)
                        VALUES ('11111111-1111-4111-8111-111111111111', 'TUR-PHI',
                                'Turdus philomelos', 'Zorzal común')
                        """);
                statement.executeUpdate("""
                        INSERT INTO bird(stable_key, ring_number, species_id)
                        VALUES ('22222222-2222-4222-8222-222222222222', 'V100', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO place(
                            stable_key, name, locality, latitude, longitude,
                            is_favorite, is_default
                        ) VALUES ('33333333-3333-4333-8333-333333333333', 'ELS RAFALS',
                                  'POLLENSA', 39.95, 3.01, 1, 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO bird_event(
                            stable_key, bird_id, event_type, event_date, event_time, place_id,
                            observations, is_dead, source_name, source_reference
                        ) VALUES (
                            '44444444-4444-4444-8444-444444444444',
                            1, 'RINGING', '2025-01-02', '08:30:00', 1,
                            'Nota editada en RingLog', 0,
                            'Registro anillamiento filats.xlsx', 'Hoja1!2'
                        )
                        """);
            }

            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            ImportResult result = service.importWorkbook(fixture.workbook());

            require(result.placesCreated() == 0 && result.placesReused() == 1,
                    "POLLENSA and POLLENÇA should resolve to the same saved place");
            require(result.eventsCreated() == 0 && result.eventsSkipped() == 1,
                    "An older imported event should be recognized by its stable identity");
            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "place") == 1,
                        "Legacy locality spelling must not create a duplicate place");
                require(count(statement, "bird_event") == 1,
                        "Legacy source references must not duplicate an event");
                try (ResultSet event = statement.executeQuery("""
                        SELECT observations, source_reference
                        FROM bird_event
                        """)) {
                    require(event.next(), "Legacy event should remain available");
                    require("Nota editada en RingLog".equals(event.getString(1)),
                            "Reconciling provenance must preserve user-entered data");
                    require("ACCESS:CAPTURAS:1".equals(event.getString(2)),
                            "Recognized legacy events should receive the stable new reference");
                }
            }
        } finally {
            fixture.delete();
        }
    }

    public static void invalidReferencesLeaveTheDatabaseUntouched() throws Exception {
        Fixture fixture = createFixture(true, "2");
        try {
            Database.initialize(fixture.database().toString());
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );

            requireThrows(
                    () -> service.importWorkbook(fixture.workbook()),
                    "An event that references an unknown bird should be rejected"
            );

            try (Connection connection = Database.getConnection(fixture.database().toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "species") == 0,
                        "A rejected workbook must not leave species behind");
                require(count(statement, "bird") == 0,
                        "A rejected workbook must not leave birds behind");
                require(count(statement, "place") == 0,
                        "A rejected workbook must not leave places behind");
                require(count(statement, "bird_event") == 0,
                        "A rejected workbook must not leave events behind");
                require(count(statement, "event_photo") == 0,
                        "A rejected workbook must not leave photos behind");
            }
        } finally {
            fixture.delete();
        }
    }

    public static void incompatibleFormatIsRejected() throws Exception {
        Fixture fixture = createFixture(false, "99");
        try {
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );
            requireThrows(
                    () -> service.preview(fixture.workbook()),
                    "Unknown import versions must be rejected before writing"
            );
            require(!Files.exists(fixture.database()),
                    "Previewing an incompatible workbook must not create a database");
        } finally {
            fixture.delete();
        }
    }

    public static void formulasAreRejectedInsteadOfEvaluated() throws Exception {
        Fixture fixture = createFixture(false, "2");
        try {
            Path manipulated = fixture.root().resolve("formula-import.xlsx");
            try (InputStream input = Files.newInputStream(fixture.workbook());
                 Workbook workbook = WorkbookFactory.create(input)) {
                workbook.getSheet("events").getRow(1).getCell(22)
                        .setCellFormula("1+1");
                try (OutputStream output = Files.newOutputStream(manipulated)) {
                    workbook.write(output);
                }
            }
            LegacyImportService service = new LegacyImportService(
                    fixture.database().toString(),
                    fixture.managedPhotos()
            );

            requireThrows(
                    () -> service.preview(manipulated),
                    "Formula cells must be rejected without being evaluated"
            );
        } finally {
            fixture.delete();
        }
    }

    private static Fixture createFixture(boolean unknownEventBird, String formatVersion)
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-import-test-");
        Path workbookPath = root.resolve("ringlog-import.xlsx");
        Path linkedPhoto = root.resolve("photos/events/V100/linked.jpg");
        Files.createDirectories(linkedPhoto.getParent());
        Files.write(linkedPhoto, "linked-photo".getBytes(StandardCharsets.UTF_8));

        try (Workbook workbook = new XSSFWorkbook()) {
            boolean version4 = "4".equals(formatVersion);
            List<Object[]> metadata = new java.util.ArrayList<>(List.of(
                    new Object[]{"format", "RingLog Import"},
                    new Object[]{"format_version", formatVersion},
                    new Object[]{"species_count", 1},
                    new Object[]{"birds_count", 1},
                    new Object[]{"places_count", 1},
                    new Object[]{"events_count", 1},
                    new Object[]{"photos_count", 1},
                    new Object[]{"unassigned_photos_count", 1},
                    new Object[]{"warnings_count", 2}
            ));
            if (version4) {
                metadata.add(new Object[]{"source_records_count", 1});
                metadata.add(new Object[]{"audit_records_count", 1});
                metadata.add(new Object[]{"audit_lost_count", 0});
                metadata.add(new Object[]{"audit_unmapped_count", 0});
                metadata.add(new Object[]{"audit_conflict_count", 1});
            }
            writeSheet(workbook, "metadata", new String[]{"key", "value"}, metadata);
            writeSheet(workbook, "species",
                    new String[]{"species_key", "code", "scientific_name",
                            "common_name", "active"},
                    List.<Object[]>of(new Object[]{
                            "SP-TURDUS", "TUR-PHI", "Turdus philomelos",
                            "Zorzal común", 1
                    }));
            writeSheet(workbook, "birds",
                    new String[]{"ring_number", "species_key"},
                    List.<Object[]>of(new Object[]{"V100", "SP-TURDUS"}));
            writeSheet(workbook, "places",
                    new String[]{"place_key", "name", "locality", "latitude",
                            "longitude", "notes", "is_favorite", "is_default", "active"},
                    List.<Object[]>of(new Object[]{
                            "PL-RAFALS", "ELS RAFALS", "POLLENÇA", 39.95, 3.01,
                            "Lugar histórico", 1, 1, 1
                    }));
            writeSheet(workbook, "events", EVENT_HEADERS,
                    List.<Object[]>of(new Object[]{
                            "EV-1", unknownEventBird ? "MISSING" : "V100", "RINGING",
                            LocalDate.of(2025, 1, 2), "07:00", "PL-RAFALS", null,
                            "U", "4", 2, 3, "AA", "OK", null, null, null,
                            "GOOD", null, 83.2, 42.1, 31.0, 71.5,
                            version4 ? "Primera línea\n  Segunda línea" : "Observación histórica",
                            "MEDIUM", "NO", "12.5 °C",
                            "LIGHT", "CAPTURE", 0, "Access", "ACCESS:CAPTURAS:1",
                            "ACCESS:CAPTURAS:1 | XLSX:Hoja1:2 | DOCX:RINGING:6:V100"
                    }));
            writeSheet(workbook, "photos",
                    new String[]{"event_key", "file_name", "file_path", "mime_type",
                            "source_reference"},
                    List.<Object[]>of(new Object[]{
                            "EV-1", "linked.jpg", "photos/events/V100/linked.jpg",
                            "image/jpeg", "ACCESS:CAPTURAS:1:PHOTO:1"
                    }));
            writeSheet(workbook, "unassigned_photos",
                    new String[]{"file_name", "file_path", "mime_type", "sha256",
                            "width", "height", "source_reference"},
                    List.<Object[]>of(new Object[]{
                            "orphan.jpg", "photos/unassigned/orphan.jpg", "image/jpeg",
                            "0123456789abcdef0123456789abcdef"
                                    + "0123456789abcdef0123456789abcdef",
                            20, 20, "ACCESS:EMBEDDED:1"
                    }));
            if (version4) {
                writeSheet(workbook, "source_records",
                        new String[]{"source_name", "source_section", "source_reference",
                                "ring_number", "raw_payload"},
                        List.<Object[]>of(new Object[]{
                                "Access", "CAPTURAS", "ACCESS:CAPTURAS:1", "V100",
                                "{\"observations\":\"Primera línea\\n  Segunda línea\"}"
                        }));
                writeSheet(workbook, "migration_audit",
                        new String[]{"source_name", "source_section", "source_reference",
                                "source_field", "original_value", "original_display",
                                "destination", "normalized_value", "status", "note"},
                        List.<Object[]>of(new Object[]{
                                "MERGE", "EVENTS", "V100", "weight", "71.5", "71,5",
                                "events.weight", "71.5", "CONFLICT", "Valor conservado"
                        }));
            }
            writeSheet(workbook, "migration_warnings",
                    new String[]{"severity", "source", "reference", "message"},
                    List.of(
                            new Object[]{"WARN", "Excel", "XLSX:1", "Dato incompleto"},
                            new Object[]{"REVIEW", "Access", "ACCESS:1", "Revisar orden"}
                    ));

            try (OutputStream output = Files.newOutputStream(workbookPath)) {
                workbook.write(output);
            }
        }

        return new Fixture(
                root,
                workbookPath,
                root.resolve("ringlog.db"),
                root.resolve("managed-photos")
        );
    }

    private static void writeSheet(
            Workbook workbook,
            String name,
            String[] headers,
            List<Object[]> rows
    ) {
        Sheet sheet = workbook.createSheet(name);
        var timeStyle = workbook.createCellStyle();
        timeStyle.setDataFormat(workbook.createDataFormat().getFormat("hh:mm"));
        Row header = sheet.createRow(0);
        for (int index = 0; index < headers.length; index++) {
            header.createCell(index).setCellValue(headers[index]);
        }
        int rowIndex = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int column = 0; column < values.length; column++) {
                Object value = values[column];
                if (value == null) {
                    continue;
                }
                Cell cell = row.createCell(column);
                if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else if (value instanceof LocalDate date) {
                    cell.setCellValue(date);
                } else {
                    cell.setCellValue(value.toString());
                }
                if ("events".equals(name) && column == 4) {
                    cell.setCellStyle(timeStyle);
                }
            }
        }
    }

    private static void setMetadataValue(Sheet metadata, String key, String value) {
        for (int rowIndex = 1; rowIndex <= metadata.getLastRowNum(); rowIndex++) {
            Row row = metadata.getRow(rowIndex);
            if (row != null && key.equals(row.getCell(0).getStringCellValue())) {
                row.getCell(1).setCellValue(value);
                return;
            }
        }
        throw new AssertionError("Missing metadata key in test fixture: " + key);
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            require(result.next(), "Count query should return one row");
            return result.getLong(1);
        }
    }

    private static boolean containsFileNamed(Path directory, String fileName)
            throws Exception {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var paths = Files.walk(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().equals(fileName));
        }
    }

    private static void requireThrows(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
        } catch (LegacyImportException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Fixture(Path root, Path workbook, Path database, Path managedPhotos) {

        void delete() throws Exception {
            if (!Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        previewExcludesUnassignedPhotos();
        importsVersion4MigratorWorkbook();
        rejectsVersion4WorkbookWithLostAuditData();
        rejectsVersion4WorkbookDisguisedAsVersion2();
        rejectsVersion4WorkbookWithMalformedSourceRecord();
        rejectsVersion4WorkbookWithMalformedUnassignedPhoto();
        rejectsVersion4WorkbookWithMalformedAuditRecord();
        rejectsVersion4WorkbookWithBackupTextContent();
        importsRowsAndOnlyEventLinkedPhotos();
        reimportSkipsExistingEventsAndKeepsUserChanges();
        recognizesRowsImportedByAnOlderRingLogVersion();
        invalidReferencesLeaveTheDatabaseUntouched();
        incompatibleFormatIsRejected();
        formulasAreRejectedInsteadOfEvaluated();
        System.out.println("LegacyImportServiceTest: PASS");
    }
}

package com.adelylria.ringlog.benchmark;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;

import org.apache.pdfbox.Loader;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.backup.BackupExportResult;
import com.adelylria.ringlog.backup.BackupExportService;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.ImportCoordinator;
import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportPlan;
import com.adelylria.ringlog.importexport.nativeformat.ExportResult;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.media.EventPhotoService;
import com.adelylria.ringlog.media.PhotoImages;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportResult;
import com.adelylria.ringlog.report.RecordReportService;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.SQLiteBackupService;

/** Real filesystem and interchange workloads; every write uses a private synthetic destination. */
public final class StorageBenchmarks {

    private StorageBenchmarks() { }

    public static void run(BenchmarkContext context) throws Exception {
        Path root = Files.createTempDirectory(context.workDirectory(), "storage-");
        Counts expected = counts(context.databaseFile());
        context.require(expected.events() == context.eventCount(), "Storage fixture event count differs from context");
        int heavySamples = Math.max(1, Math.min(3, context.samples()));
        reportBenchmarks(context, root, heavySamples);
        interchangeBenchmarks(context, root, expected, heavySamples);
        snapshotBenchmarks(context, root, expected, heavySamples);
        photoBenchmarks(context, root, heavySamples);
        context.require(counts(context.databaseFile()).equals(expected),
                "Storage benchmarks must leave the source diary unchanged");
    }

    private static void reportBenchmarks(BenchmarkContext context, Path root, int samples) throws Exception {
        int limit = Math.max(1, Math.min(1_000, context.reportLimit()));
        var records = context.events().findReportRows(context.reportIds(limit));
        context.require(!records.isEmpty() && records.size() <= limit, "Report fixture selection is invalid");
        RecordReportService reports = new RecordReportService();
        AtomicInteger sequence = new AtomicInteger();
        for (RecordReportFormat format : RecordReportFormat.values()) {
            List<RecordReportResult> outputs = new ArrayList<>();
            context.measure("storage", "report." + format.extension() + ".rows-" + records.size(), 0, samples, () -> {
                Path destination = root.resolve("report-" + sequence.incrementAndGet() + '.' + format.extension());
                RecordReportResult result = reports.export(format, destination, records,
                        List.of("Benchmark: datos sintéticos", "Registros seleccionados: " + records.size()));
                outputs.add(result);
                return Files.size(result.path()) ^ result.recordCount();
            });
            // Additional benchmark assertions run outside the timed production export/validation path.
            for (RecordReportResult result : outputs) {
                context.require(result.recordCount() == records.size() && result.format() == format,
                        "Report export returned incorrect metadata");
                if (format == RecordReportFormat.EXCEL) {
                    requirePrefix(context, result.path(), new byte[]{'P', 'K'});
                    try (InputStream input = Files.newInputStream(result.path());
                         var workbook = WorkbookFactory.create(input)) {
                        var sheet = workbook.getSheet("Registros");
                        context.require(sheet != null && sheet.getLastRowNum() == records.size() + 3,
                                "The report workbook must contain every selected record");
                    }
                } else {
                    requirePrefix(context, result.path(), "%PDF-".getBytes(StandardCharsets.US_ASCII));
                    try (var document = Loader.loadPDF(result.path().toFile())) {
                        context.require(document.getNumberOfPages() >= records.size() + 1,
                                "The PDF must contain its cover and every selected record");
                    }
                }
            }
        }
    }

    private static void interchangeBenchmarks(BenchmarkContext context, Path root, Counts expected,
            int samples) throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        List<ExportResult> nativeFiles = new ArrayList<>();
        RingLogExporter nativeExporter = new RingLogExporter(context.databaseFile(), new MediaPathResolver(context.paths()));
        context.measure("storage", "native.export_all", 0, samples, () -> {
            ExportResult exported = nativeExporter.exportTo(root.resolve("native-" + sequence.incrementAndGet()));
            nativeFiles.add(exported);
            return Files.size(exported.file()) ^ exported.binaryCount();
        });
        for (ExportResult exported : nativeFiles) {
            requirePrefix(context, exported.file(), new byte[]{'P', 'K'});
            context.require(expected.photos() == 0 || exported.binaryCount() > 0,
                    "A native export with managed photos must include binaries");
        }
        Path nativeFile = nativeFiles.get(nativeFiles.size() - 1).file();
        context.measure("storage", "native.analyze_verify_close", 0, samples, () -> {
            try (ImportPlan plan = new ImportCoordinator().analyze(nativeFile)) {
                validatePlan(context, plan, ImportFormat.RINGLOG_EXPORT_V1, expected);
                return plan.nativeModel().rows("events").size() ^ plan.fingerprint().hashCode();
            }
        });
        restoreBenchmarks(context, root, "native", nativeFile, ImportFormat.RINGLOG_EXPORT_V1, expected, samples);

        List<BackupExportResult> backups = new ArrayList<>();
        BackupExportService fullBackup = new BackupExportService(context.paths());
        context.measure("storage", "full_backup_v3.export_all", 0, samples, () -> {
            BackupExportResult exported = fullBackup.exportTo(root.resolve("full-backup-" + sequence.incrementAndGet()));
            backups.add(exported);
            return Files.size(exported.file()) ^ exported.eventsCount() ^ exported.photosCount();
        });
        for (BackupExportResult backup : backups) {
            requirePrefix(context, backup.file(), new byte[]{'P', 'K'});
            context.require(backup.eventsCount() == expected.events() && backup.photosCount() == expected.photos(),
                    "The self-contained workbook backup returned incorrect counts");
        }
        Path backupFile = backups.get(backups.size() - 1).file();
        context.measure("storage", "full_backup_v3.analyze_verify_close", 0, samples, () -> {
            try (ImportPlan plan = new ImportCoordinator().analyze(backupFile)) {
                validatePlan(context, plan, ImportFormat.RINGLOG_BACKUP_V3, expected);
                return plan.backupV3Model().events().size() ^ plan.fingerprint().hashCode();
            }
        });
        restoreBenchmarks(context, root, "full_backup_v3", backupFile, ImportFormat.RINGLOG_BACKUP_V3, expected, samples);
    }

    private static void validatePlan(BenchmarkContext context, ImportPlan plan, ImportFormat format,
            Counts expected) {
        context.require(plan.profile().format() == format, "Interchange format detection returned the wrong profile");
        long events = format == ImportFormat.RINGLOG_EXPORT_V1
                ? plan.nativeModel().rows("events").size() : plan.backupV3Model().events().size();
        long photos = format == ImportFormat.RINGLOG_EXPORT_V1
                ? plan.nativeModel().rows("photos").size() : plan.backupV3Model().photos().size();
        context.require(events == expected.events() && photos == expected.photos(),
                "Analyzing an export must preserve the event and photo counts");
        context.require(!plan.fingerprint().isBlank(), "A validated import must have a fingerprint");
    }

    private static void restoreBenchmarks(BenchmarkContext context, Path root, String label, Path file,
            ImportFormat format, Counts expected, int samples) throws Exception {
        List<AppPaths> destinations = new ArrayList<>();
        // The validated immutable plan is reusable for independent empty destinations. Parsing is
        // measured separately, so restore measures initialization, transactional writes and media copy.
        try (ImportPlan plan = new ImportCoordinator().analyze(file)) {
            validatePlan(context, plan, format, expected);
            context.measure("storage", label + ".restore_to_empty_from_plan", 0, samples, () -> {
                AppPaths destination = AppPaths.forDataRoot(Files.createTempDirectory(root, label + "-restore-"));
                destinations.add(destination);
                ImportExecutionResult result = new ImportTransactionService(
                        destination.databasePath().toString(), destination).execute(plan);
                context.require(result.status() == ImportExecutionResult.Status.APPLIED,
                        "Restoring to an independent destination must apply the import");
                return result.importBatchId() ^ Files.size(destination.databasePath());
            });
        }
        for (AppPaths destination : destinations) verifyRestored(context, destination, expected);
    }

    private static void snapshotBenchmarks(BenchmarkContext context, Path root, Counts expected,
            int samples) throws Exception {
        List<Path> snapshots = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        context.measure("storage", "sqlite.online_snapshot", 0, samples, () -> {
            Path snapshot = root.resolve("snapshot-" + sequence.incrementAndGet() + ".db");
            new SQLiteBackupService().backup(context.paths().databasePath(), snapshot);
            snapshots.add(snapshot);
            return Files.size(snapshot);
        });
        for (Path snapshot : snapshots) {
            requirePrefix(context, snapshot, "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII));
            Database.validate(snapshot.toString());
            context.require(counts(snapshot.toString()).equals(expected), "A SQLite snapshot must preserve all core counts");
        }
    }

    private static void photoBenchmarks(BenchmarkContext context, Path root, int samples) throws Exception {
        List<Path> photos = context.samplePhotos().stream().limit(context.eventCount() <= 1_000 ? 1 : 3).toList();
        context.require(!photos.isEmpty(), "Storage benchmarks need synthetic JPEG or PNG photos");
        int lightSamples = Math.max(1, context.samples());
        int lightWarmups = Math.max(0, context.warmups());
        context.measure("storage", "photos.read_dimensions.files-" + photos.size(), lightWarmups, lightSamples, () -> {
            long checksum = 0;
            for (Path photo : photos) {
                try (var input = new FileImageInputStream(photo.toFile())) {
                    var readers = ImageIO.getImageReaders(input);
                    context.require(readers.hasNext(), "A synthetic photo needs an ImageIO reader");
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        int width = reader.getWidth(0);
                        int height = reader.getHeight(0);
                        context.require(width > 0 && height > 0, "Photo metadata dimensions must be positive");
                        checksum += ((long) width << 32) ^ height;
                    } finally {
                        reader.dispose();
                    }
                }
            }
            return checksum;
        });
        context.measure("storage", "photos.decode_thumbnail_92.files-" + photos.size(), lightWarmups, lightSamples, () -> {
            long checksum = 0;
            for (Path photo : photos) {
                BufferedImage image = PhotoImages.load(photo, 92, 92).image();
                context.require(image.getWidth() <= 92 && image.getHeight() <= 92, "Thumbnail decoding must stay bounded");
                checksum += consume(image);
            }
            return checksum;
        });
        context.measure("storage", "photos.decode_full.files-" + photos.size(), 0, samples, () -> {
            long checksum = 0;
            for (Path photo : photos) {
                BufferedImage image = ImageIO.read(photo.toFile());
                context.require(image != null, "Full photo decoding must produce pixels");
                checksum += consume(image);
            }
            return checksum;
        });

        AppPaths managed = AppPaths.forDataRoot(Files.createTempDirectory(root, "photo-management-"));
        new SQLiteBackupService().backup(context.paths().databasePath(), managed.databasePath());
        EventPhotoService service = new EventPhotoService(managed, new DataMutationCoordinator());
        BirdEventRepository events = new BirdEventRepository(managed);
        BirdEventInput input = photoEventInput(context.events().findDetail(context.eventId()).orElseThrow());
        Path original = photos.get(0);
        String originalHash = sha256(original);
        List<Long> addedEvents = new ArrayList<>();
        context.measure("storage", "photos.save_new_event_with_managed_photo", 0, samples, () -> {
            long id = service.saveEvent(null, input, List.of(original));
            addedEvents.add(id);
            return id;
        });
        List<Removal> removals = new ArrayList<>();
        for (Long id : addedEvents) {
            BirdEventDetail saved = events.findDetail(id).orElseThrow();
            context.require(saved.photoPaths().size() == 1, "A new photo event must have one managed reference");
            Path copy = Path.of(saved.photoPaths().get(0));
            context.require(copy.startsWith(managed.dataRoot()) && !copy.equals(original)
                            && originalHash.equals(sha256(copy)),
                    "Photo management must own an independent byte-identical copy");
            removals.add(new Removal(id, copy));
        }
        long latestId = addedEvents.get(addedEvents.size() - 1);
        context.measure("storage", "photos.read_event_metadata", lightWarmups, lightSamples, () -> {
            BirdEventDetail detail = events.findDetail(latestId).orElseThrow();
            context.require(detail.photoPaths().size() == 1, "Photo metadata must retain its event association");
            return detail.id() ^ detail.photoPaths().get(0).hashCode();
        });
        AtomicInteger removalIndex = new AtomicInteger();
        context.measure("storage", "photos.remove_reference_and_owned_file", 0, samples, () -> {
            Removal removal = removals.get(removalIndex.getAndIncrement());
            return service.saveEvent(removal.eventId(), input, List.of(), List.of(removal.file().toString()));
        });
        for (Removal removal : removals) {
            context.require(events.findDetail(removal.eventId()).orElseThrow().photoPaths().isEmpty()
                            && !Files.exists(removal.file()), "Photo removal must clean both metadata and its managed file");
        }
        context.require(originalHash.equals(sha256(original)), "Managing photos must preserve the synthetic original bytes");
    }

    private static BirdEventInput photoEventInput(BirdEventDetail original) {
        return new BirdEventInput(original.ringNumber(), original.speciesId(), EventType.CONTROL,
                original.eventDate(), original.eventTime(), original.placeId(), original.locationText(),
                original.sexCode(), original.ageEuringCode(), original.fatScore(), original.muscleScore(),
                original.ringerInitials(), original.status(), original.reproductiveStatus(), original.moultIntensity(),
                original.moultExtension(), original.birdCondition(), original.returnStatus(), original.wing(),
                original.p3(), original.torso(), original.weight(), original.observations(), original.clouds(),
                original.rain(), original.thermalSensation(), original.wind(), null, original.dead(), null);
    }

    private static long consume(BufferedImage image) {
        long checksum = ((long) image.getWidth() << 32) ^ image.getHeight()
                ^ image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        image.flush();
        return checksum;
    }

    private static void verifyRestored(BenchmarkContext context, AppPaths paths, Counts expected) throws Exception {
        Database.validate(paths.databasePath().toString());
        context.require(counts(paths.databasePath().toString()).equals(expected),
                "Interchange restoration must preserve all core table counts");
        MediaPathResolver resolver = new MediaPathResolver(paths);
        try (Connection connection = Database.getReadOnlyConnection(paths.databasePath().toString());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT file_path, content_sha256, content_size FROM event_photo")) {
            while (rows.next()) {
                Path photo = resolver.resolveEventPhoto(rows.getString(1));
                context.require(Files.isRegularFile(photo), "Restored photos must exist at their managed paths");
                String hash = rows.getString(2);
                if (hash != null && !hash.isBlank()) context.require(hash.equals(sha256(photo)), "Restored photo hash differs");
                long bytes = rows.getLong(3);
                if (!rows.wasNull()) context.require(Files.size(photo) == bytes, "Restored photo size differs");
            }
        }
    }

    private static Counts counts(String databaseFile) throws Exception {
        try (Connection connection = Database.getReadOnlyConnection(databaseFile);
             Statement statement = connection.createStatement()) {
            return new Counts(count(statement, "species"), count(statement, "bird"), count(statement, "place"),
                    count(statement, "capture_method"), count(statement, "bird_event"), count(statement, "event_photo"));
        }
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!result.next()) throw new AssertionError("Missing benchmark count for " + table);
            return result.getLong(1);
        }
    }

    private static void requirePrefix(BenchmarkContext context, Path file, byte[] prefix) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            context.require(java.util.Arrays.equals(prefix, input.readNBytes(prefix.length)),
                    "Benchmark output has an unexpected file-format signature");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Counts(long species, long birds, long places, long methods, long events, long photos) { }
    private record Removal(long eventId, Path file) { }
}

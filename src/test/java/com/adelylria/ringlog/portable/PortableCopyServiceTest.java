package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.ImportCoordinator;
import com.adelylria.ringlog.importexport.ImportPlan;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.report.RecordReportFormat;
import com.adelylria.ringlog.report.RecordReportService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.PortableAppPaths;
import com.adelylria.ringlog.storage.SQLiteBackupService;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

public final class PortableCopyServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-04T18:30:00Z"), ZoneOffset.UTC
    );
    private static final Path REAL_LEGACY_V52 = LegacyV52TestFixture.path();

    private PortableCopyServiceTest() {
    }

    public static void portableCopyIsCompleteValidatedAndMovable() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-copy-");
        try {
            AppPaths source = sourceDataset(root.resolve("source"));
            InstalledDistribution distribution = installedDistribution(root.resolve("installed"));
            Path destination = Files.createDirectories(root.resolve("device-a"));
            String sourceDatabaseHash = PortableHashing.sha256(source.databasePath());
            String sourceEventPhotoHash = PortableHashing.sha256(
                    source.eventPhotosDirectory().resolve("aa/event.jpg")
            );
            List<PortableCopyStage> stages = new ArrayList<>();
            PortableCopyService service = service(source, distribution, Long.MAX_VALUE, ignored -> { });

            PortableCopyResult result = service.create(
                    destination, false, progress -> stages.add(progress.stage())
            );
            PortableAppPaths portable = new PortableAppPaths(result.portableRoot());
            PortableDatasetSummary summary = new PortableCopyValidator().validateStartup(portable);

            require(summary.events() == 1 && summary.eventPhotos() == 1
                            && summary.unassignedPhotos() == 2,
                    "The portable snapshot must retain records and assigned/unassigned photo rows");
            require(result.photoCount() == 3,
                    "Portable result metadata must describe all photo records");
            require(Files.isRegularFile(portable.portableRoot().resolve("RingLog.jar"))
                            && Files.isDirectory(portable.portableRoot().resolve("libs"))
                            && Files.isRegularFile(portable.portableRoot()
                            .resolve("runtime/bin/javaw.exe"))
                            && Files.isRegularFile(portable.manifestPath())
                            && Files.isRegularFile(portable.infoPath()),
                    "The portable copy must contain application, runtime and technical metadata");
            String launcher = Files.readString(
                    portable.portableRoot().resolve("RingLog-Portatil.cmd"),
                    StandardCharsets.US_ASCII
            );
            require(launcher.contains("runtime\\bin\\javaw.exe")
                            && launcher.contains("-Dringlog.mode=portable-readonly")
                            && launcher.contains("-jar \"RingLog.jar\"")
                            && !launcher.matches("(?is).*[A-Z]:\\\\.*"),
                    "The launcher must use only paths relative to its current drive letter");
            String manifest = Files.readString(portable.manifestPath());
            require(manifest.contains("photos/events/aa/event.jpg")
                            && manifest.contains(sourceEventPhotoHash),
                    "Creation must record the full SHA-256 of every immutable photograph");
            require(stages.containsAll(List.of(
                            PortableCopyStage.CHECKING_SPACE,
                            PortableCopyStage.SNAPSHOTTING_DATABASE,
                            PortableCopyStage.COPYING_MEDIA,
                            PortableCopyStage.VERIFYING,
                            PortableCopyStage.PUBLISHING,
                            PortableCopyStage.COMPLETE
                    )),
                    "Creation must expose all user-facing progress stages");
            require(sourceDatabaseHash.equals(PortableHashing.sha256(source.databasePath()))
                            && sourceEventPhotoHash.equals(PortableHashing.sha256(
                            source.eventPhotosDirectory().resolve("aa/event.jpg"))),
                    "Creating a portable copy must leave the source byte-for-byte intact");

            Path movedParent = Files.createDirectories(root.resolve("device-b"));
            Path movedRoot = movedParent.resolve("renamed-portable-copy");
            Files.move(portable.portableRoot(), movedRoot);
            PortableDatasetSummary moved = new PortableCopyValidator().validateStartup(
                    new PortableAppPaths(movedRoot)
            );
            require(moved.events() == 1,
                    "The copy must remain valid after its drive letter/path changes");
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    public static void startupValidationDoesNotRehashEveryPhoto() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-fast-start-");
        try {
            AppPaths source = sourceDataset(root.resolve("source"));
            InstalledDistribution distribution = installedDistribution(root.resolve("installed"));
            Path destination = Files.createDirectories(root.resolve("device"));
            PortableCopyResult result = service(
                    source, distribution, Long.MAX_VALUE, ignored -> { }
            ).create(destination, false, null);
            PortableAppPaths portable = new PortableAppPaths(result.portableRoot());
            Path copiedPhoto = portable.eventPhotosDirectory().resolve("aa/event.jpg");

            Files.writeString(copiedPhoto, "changed after publication", StandardCharsets.UTF_8);
            PortableDatasetSummary summary = new PortableCopyValidator().validateStartup(portable);
            require(summary.eventPhotos() == 1,
                    "Normal startup should resolve referenced photos without hashing every binary");
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    public static void preflightPreservesAnExistingCopyWhenSpaceIsInsufficient()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-space-");
        try {
            AppPaths source = sourceDataset(root.resolve("source"));
            InstalledDistribution distribution = installedDistribution(root.resolve("installed"));
            Path destination = Files.createDirectories(root.resolve("device"));
            Path existing = Files.createDirectories(
                    destination.resolve(PortableCopyService.DIRECTORY_NAME)
            );
            Path marker = Files.writeString(existing.resolve("old-copy.txt"), "keep me");
            PortableCopyService service = service(source, distribution, 0, ignored -> { });

            requirePortableFailure(() -> service.preflight(destination));
            require(Files.readString(marker).equals("keep me"),
                    "Preflight must not delete the old copy to manufacture free space");
            try (var files = Files.list(destination)) {
                require(files.noneMatch(path -> path.getFileName().toString()
                                .startsWith(".RingLog-Portatil.creating-")),
                        "An insufficient-space failure must occur before staging starts");
            }
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    public static void installedRuntimeMustBeTheLockedZulu17Build() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-runtime-");
        try {
            Path installed = root.resolve("installed");
            installedDistribution(installed);
            Files.writeString(installed.resolve("runtime/release"), """
                    IMPLEMENTOR="Another vendor"
                    IMPLEMENTOR_VERSION="Other17"
                    JAVA_VERSION="17.0.8"
                    OS_ARCH="x86_64"
                    """);
            requirePortableFailure(() -> new InstalledDistribution(installed));
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    public static void failedReplacementRollsBackToThePreviousCopy() throws Exception {
        Path root = Files.createTempDirectory("ringlog-portable-rollback-");
        try {
            AppPaths source = sourceDataset(root.resolve("source"));
            InstalledDistribution distribution = installedDistribution(root.resolve("installed"));
            Path destination = Files.createDirectories(root.resolve("device"));
            Path existing = Files.createDirectories(
                    destination.resolve(PortableCopyService.DIRECTORY_NAME)
            );
            Path marker = Files.writeString(existing.resolve("old-copy.txt"), "previous");
            PortableCopyService service = service(
                    source, distribution, Long.MAX_VALUE,
                    staging -> {
                        try {
                            Files.delete(staging.resolve(PortableAppPaths.INFO_FILE));
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
            );

            requirePortableFailure(() -> service.create(destination, true, null));
            require(Files.isRegularFile(marker) && Files.readString(marker).equals("previous"),
                    "A failed replacement must leave the complete old copy in place");
            try (var files = Files.list(destination)) {
                require(files.noneMatch(path -> path.getFileName().toString()
                                .startsWith(".RingLog-Portatil.creating-")),
                        "Failed staging must be cleaned up safely");
            }
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    public static void consistentSnapshotBlocksOrdinaryMutations() throws Exception {
        DataMutationCoordinator coordinator = new DataMutationCoordinator();
        List<Boolean> states = new ArrayList<>();
        coordinator.addSnapshotListener(states::add);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (DataMutationCoordinator.Lease snapshot = coordinator.acquireConsistentSnapshot()) {
            executor.submit(() -> {
                attempted.countDown();
                try (DataMutationCoordinator.Lease ignored = coordinator.acquireMutation()) {
                    acquired.countDown();
                }
            });
            require(attempted.await(2, TimeUnit.SECONDS),
                    "The mutation test worker did not start");
            require(!acquired.await(150, TimeUnit.MILLISECONDS),
                    "A mutation entered while the consistent snapshot was active");
            require(coordinator.snapshotInProgress(),
                    "The coordinator must expose snapshot state to disable UI mutations");
        } finally {
            executor.shutdown();
        }
        require(acquired.await(2, TimeUnit.SECONDS),
                "Mutations must resume after the snapshot finishes");
        require(states.equals(List.of(true, false)),
                "The UI must receive start and finish snapshot notifications");
    }

    public static void realLegacyDatasetWorksEndToEndAsMovablePortableCopy()
            throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Portable copy legacy integration")) {
            return;
        }
        require(Files.isRegularFile(REAL_LEGACY_V52),
                "The real sanitized legacy v5.2 integration workbook is required");
        Path root = Files.createTempDirectory("ringlog-portable-real-v52-");
        try {
            AppPaths source = AppPaths.forDataRoot(root.resolve("normal-data"));
            try (ImportPlan plan = new ImportCoordinator().analyze(REAL_LEGACY_V52)) {
                new ImportTransactionService(source.databasePath().toString(), source)
                        .execute(plan);
            }
            require(PortableReadOnlyTest.queryCount(source.databasePath(), "bird_event") == 289,
                    "The real legacy fixture must import 289 events before portable creation");
            require(PortableReadOnlyTest.queryCount(
                            source.databasePath(), "legacy_unassigned_photo") == 9,
                    "The real legacy fixture must import nine unassigned photographs");

            String installedRoot = System.getProperty("ringlog.portable.installed.root");
            InstalledDistribution distribution = installedRoot == null || installedRoot.isBlank()
                    ? installedDistribution(root.resolve("installed"))
                    : new InstalledDistribution(Path.of(installedRoot));
            Path firstDevice = Files.createDirectories(root.resolve("device-a"));
            PortableCopyResult result = service(
                    source, distribution, Long.MAX_VALUE, ignored -> { }
            ).create(firstDevice, false, null);
            PortableAppPaths portable = new PortableAppPaths(result.portableRoot());
            PortableDatasetSummary summary = new PortableCopyValidator().validateStartup(portable);
            require(summary.events() == 289 && summary.unassignedPhotos() == 9,
                    "The portable copy must retain all 289 events and nine photo records");
            try (var photos = Files.walk(portable.unassignedPhotosDirectory())) {
                require(photos.filter(Files::isRegularFile).count() == 9,
                        "All nine physical legacy photographs must be copied");
            }

            ApplicationContext context = ApplicationContext.portable(portable);
            BirdEventRepository repository = new BirdEventRepository(
                    portable, context.databaseAccess(), context.mutationCoordinator()
            );
            var events = repository.findAll();
            require(events.size() == 289
                            && repository.findDetail(events.get(0).id()).isPresent()
                            && repository.findDashboardStats().eventCount() == 289,
                    "Portable repositories must support diary navigation and detail views");
            String databaseHash = PortableHashing.sha256(portable.databasePath());
            var reportRows = repository.findReportRows(
                    events.stream().map(event -> event.id()).toList()
            );
            Path reportDirectory = Files.createDirectories(root.resolve("reports"));
            new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.PDF,
                    reportDirectory.resolve("legacy-289.pdf"),
                    reportRows,
                    List.of("Archivo completo")
            );
            new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.EXCEL,
                    reportDirectory.resolve("legacy-289.xlsx"),
                    reportRows,
                    List.of("Archivo completo")
            );
            require(Files.size(reportDirectory.resolve("legacy-289.pdf")) > 0
                            && Files.size(reportDirectory.resolve("legacy-289.xlsx")) > 0,
                    "PDF and XLSX must export all 289 portable records");
            require(databaseHash.equals(PortableHashing.sha256(portable.databasePath())),
                    "Real PDF/XLSX export must not change portable database state");

            if (installedRoot != null && !installedRoot.isBlank()) {
                probePackagedStartup(portable.portableRoot(), root.resolve("probe-before-move"));
                require(databaseHash.equals(PortableHashing.sha256(portable.databasePath())),
                        "Real packaged navigation must not change the portable database");
            }

            Path secondDevice = Files.createDirectories(root.resolve("device-b"));
            Path movedRoot = secondDevice.resolve("RingLog-Portatil-movido");
            Files.move(portable.portableRoot(), movedRoot);
            PortableDatasetSummary moved = new PortableCopyValidator().validateStartup(
                    new PortableAppPaths(movedRoot)
            );
            require(moved.events() == 289 && moved.unassignedPhotos() == 9,
                    "The complete real dataset must survive a drive/path change");
            require(new ApplicationContextPathAssertion(
                    ApplicationContext.portable(new PortableAppPaths(movedRoot)), movedRoot
            ).allPathsStayPortable(),
                    "Portable context must not use host APPDATA or LOCALAPPDATA");
            if (installedRoot != null && !installedRoot.isBlank()) {
                probePackagedStartup(movedRoot, root.resolve("probe-after-move"));
                require(databaseHash.equals(PortableHashing.sha256(
                                new PortableAppPaths(movedRoot).databasePath())),
                        "Moving and reopening the portable must not change its database");
            }

            String retainedOutput = System.getProperty("ringlog.portable.validation.output");
            if (retainedOutput != null && !retainedOutput.isBlank()) {
                require(installedRoot != null && !installedRoot.isBlank(),
                        "A retained validation copy requires a real packaged distribution");
                Path output = Path.of(retainedOutput).toAbsolutePath().normalize();
                // Explicit opt-in only; never overwrite any existing folder or user copy.
                Files.createDirectory(output);
                Files.move(movedRoot, output.resolve(PortableCopyService.DIRECTORY_NAME));
                Files.move(reportDirectory, output.resolve("reports"));
                System.out.println("Portable validation copy: " + output);
            }
        } finally {
            PortableReadOnlyTest.deleteTree(root);
        }
    }

    private static void probePackagedStartup(Path portableRoot, Path work) throws Exception {
        Files.createDirectories(work);
        Path testClasses = Path.of(PortableStartupProbe.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path hostAppData = work.resolve("host-appdata-must-stay-absent");
        Path hostLocalAppData = work.resolve("host-localappdata-must-stay-absent");
        Path output = work.resolve("startup.log");
        ProcessBuilder builder = new ProcessBuilder(
                portableRoot.resolve("runtime/bin/java.exe").toString(),
                "-Dringlog.mode=portable-readonly",
                "-cp", portableRoot.resolve("RingLog.jar") + java.io.File.pathSeparator + testClasses,
                PortableStartupProbe.class.getName(), portableRoot.toString()
        ).directory(work.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("APPDATA", hostAppData.toString());
        builder.environment().put("LOCALAPPDATA", hostLocalAppData.toString());
        Process process = builder.start();
        try {
            require(process.waitFor(45, TimeUnit.SECONDS), "Packaged portable startup timed out");
            require(process.exitValue() == 0 && Files.readString(output).contains("PortableStartupProbe: PASS"),
                    "Packaged portable startup failed: " + Files.readString(output));
            require(!Files.exists(hostAppData) && !Files.exists(hostLocalAppData),
                    "Portable process must not write host APPDATA or LOCALAPPDATA");
            System.out.println(Files.readString(output).strip());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record ApplicationContextPathAssertion(
            ApplicationContext context,
            Path portableRoot
    ) {
        private boolean allPathsStayPortable() {
            Path root = portableRoot.toAbsolutePath().normalize();
            return context.paths().databasePath().startsWith(root)
                    && context.paths().photosDirectory().startsWith(root)
                    && context.paths().unassignedPhotosDirectory().startsWith(root)
                    && context.paths().logsDirectory().startsWith(root)
                    && context.preferences().path().startsWith(root);
        }
    }

    private static AppPaths sourceDataset(Path root) throws Exception {
        AppPaths paths = AppPaths.forDataRoot(root);
        Files.createDirectories(paths.eventPhotosDirectory().resolve("aa"));
        Files.createDirectories(paths.nativePhotosDirectory());
        Files.createDirectories(paths.unassignedPhotosDirectory().resolve("bb"));
        Path eventPhoto = Files.writeString(
                paths.eventPhotosDirectory().resolve("aa/event.jpg"), "event-photo"
        );
        Path orphanPhoto = Files.writeString(
                paths.unassignedPhotosDirectory().resolve("bb/orphan.jpg"), "orphan-photo"
        );
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
                        1, 'Registro de prueba', 'OK'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_photo(
                        stable_key, event_id, file_name, file_path, content_sha256, content_size
                    ) VALUES (
                        'event-photo-1', 1, 'event.jpg', 'events/aa/event.jpg',
                        '%s', %d
                    )
                    """.formatted(PortableHashing.sha256(eventPhoto), Files.size(eventPhoto)));
            statement.executeUpdate("""
                    INSERT INTO import_batch(
                        id, stable_key, source_format, format_version, source_name, import_mode
                    ) VALUES (1, 'batch-1', 'LEGACY_MIGRATION', '5.2', 'fixture.xlsx', 'MERGE')
                    """);
            statement.executeUpdate("""
                    INSERT INTO legacy_unassigned_photo(
                        stable_key, import_batch_id, source_reference, file_name,
                        file_path, content_sha256, content_size
                    ) VALUES (
                        'orphan-1', 1, 'orphan:1', 'orphan.jpg', 'bb/orphan.jpg',
                        '%s', %d
                    )
                    """.formatted(PortableHashing.sha256(orphanPhoto), Files.size(orphanPhoto)));
            statement.executeUpdate("""
                    INSERT INTO legacy_unassigned_photo(
                        stable_key, import_batch_id, source_reference, file_name, file_path
                    ) VALUES ('orphan-missing', 1, 'orphan:missing', 'missing.jpg', NULL)
                    """);
        }
        return paths;
    }

    private static InstalledDistribution installedDistribution(Path root) throws Exception {
        Files.createDirectories(root.resolve("libs"));
        Files.createDirectories(root.resolve("runtime/bin"));
        Files.writeString(root.resolve("RingLog.jar"), "packaged-ringlog");
        Files.writeString(root.resolve("RingLog.ico"), "icon");
        Files.writeString(root.resolve("runtime/bin/javaw.exe"), "javaw");
        Files.writeString(root.resolve("runtime/bin/java.exe"), "java");
        Files.writeString(root.resolve("runtime/release"), """
                IMPLEMENTOR="Azul Systems, Inc."
                IMPLEMENTOR_VERSION="Zulu17.42+19-CA"
                JAVA_VERSION="17.0.7"
                OS_ARCH="x86_64"
                OS_NAME="Windows"
                """);
        for (String library : List.of(
                "sqlite-jdbc-test.jar", "ormlite-jdbc-test.jar", "flatlaf-test.jar",
                "poi-ooxml-test.jar", "pdfbox-test.jar", "jackson-core-test.jar",
                "log4j-api-test.jar", "log4j-core-test.jar"
        )) {
            Files.writeString(root.resolve("libs").resolve(library), library);
        }
        Files.writeString(root.resolve("distribution.properties"), """
                version=0.0.1
                architecture=x64
                runtime.filename=zulu17.42.19-ca-jre17.0.7-win_x64.zip
                runtime.sha256=8e6d0708ae79742d2652772a91b37b05e014306df1950bfe68c9f88f0fefaf16
                """);
        return new InstalledDistribution(root);
    }

    private static PortableCopyService service(
            AppPaths source,
            InstalledDistribution distribution,
            long availableSpace,
            java.util.function.Consumer<Path> beforePublish
    ) {
        return new PortableCopyService(
                source,
                distribution,
                new DataMutationCoordinator(),
                new SQLiteBackupService(),
                new PortableCopyValidator(),
                FIXED_CLOCK,
                ignored -> availableSpace,
                beforePublish
        );
    }

    private static void requirePortableFailure(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (PortableCopyException expected) {
            return;
        }
        throw new AssertionError("Expected portable copy operation to fail");
    }

    private static void require(boolean condition, String message) {
        PortableReadOnlyTest.require(condition, message);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        portableCopyIsCompleteValidatedAndMovable();
        startupValidationDoesNotRehashEveryPhoto();
        preflightPreservesAnExistingCopyWhenSpaceIsInsufficient();
        installedRuntimeMustBeTheLockedZulu17Build();
        failedReplacementRollsBackToThePreviousCopy();
        consistentSnapshotBlocksOrdinaryMutations();
        realLegacyDatasetWorksEndToEndAsMovablePortableCopy();
        System.out.println("PortableCopyServiceTest: PASS");
    }
}

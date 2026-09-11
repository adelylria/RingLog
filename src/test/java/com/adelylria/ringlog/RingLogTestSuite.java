package com.adelylria.ringlog;

import com.adelylria.ringlog.backup.BackupExportServiceTest;
import com.adelylria.ringlog.build.DistributionConfigurationTest;
import com.adelylria.ringlog.build.ReleaseVersionToolTest;
import com.adelylria.ringlog.build.ReleaseToolingTest;
import com.adelylria.ringlog.database.SchemaMigrationTest;
import com.adelylria.ringlog.database.SchemaMigrationRegistryTest;
import com.adelylria.ringlog.database.SchemaBackupServiceTest;
import com.adelylria.ringlog.database.DatabaseUpgradeServiceTest;
import com.adelylria.ringlog.diagnostics.RingLogLoggingTest;
import com.adelylria.ringlog.diagnostics.BuildInfoTest;
import com.adelylria.ringlog.diagnostics.DiagnosticsServiceTest;
import com.adelylria.ringlog.diagnostics.OperationLoggingTest;
import com.adelylria.ringlog.database.SchemaVersionContractTest;
import java.io.File;
import java.net.URLClassLoader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.adelylria.ringlog.model.BirdEventFormattingTest;
import com.adelylria.ringlog.model.BirdStatusCatalogTest;
import com.adelylria.ringlog.portable.PortableCopyServiceTest;
import com.adelylria.ringlog.portable.PortableReadOnlyTest;
import com.adelylria.ringlog.importer.LegacyImportServiceTest;
import com.adelylria.ringlog.importexport.WorkbookFormatDetectorTest;
import com.adelylria.ringlog.importexport.ImportFingerprintServiceTest;
import com.adelylria.ringlog.importexport.LegacyV5WorkbookReaderTest;
import com.adelylria.ringlog.importexport.LegacyImportTransactionTest;
import com.adelylria.ringlog.importexport.ConflictResolutionServiceTest;
import com.adelylria.ringlog.importexport.RingLogV3CompatibilityTest;
import com.adelylria.ringlog.importexport.NativeExportRoundTripTest;
import com.adelylria.ringlog.repository.BirdEventRepositoryTest;
import com.adelylria.ringlog.repository.CatalogRepositoryTest;
import com.adelylria.ringlog.report.RecordReportServiceTest;
import com.adelylria.ringlog.storage.AppPathsTest;
import com.adelylria.ringlog.storage.ApplicationStorageBootstrapTest;
import com.adelylria.ringlog.storage.LegacyDataLocationMigratorTest;
import com.adelylria.ringlog.storage.LegacyDataLocationDetectorTest;
import com.adelylria.ringlog.storage.MediaPathResolverTest;
import com.adelylria.ringlog.ui.BirdEventUiTest;
import com.adelylria.ringlog.ui.ApplicationIconTest;
import com.adelylria.ringlog.ui.CalendarDatePickerTest;
import com.adelylria.ringlog.ui.CapturePanelTest;
import com.adelylria.ringlog.ui.CatalogFormTest;
import com.adelylria.ringlog.ui.CatalogRefreshTest;
import com.adelylria.ringlog.ui.LayoutDesignTest;
import com.adelylria.ringlog.ui.MainFrameNavigationTest;
import com.adelylria.ringlog.ui.ImportExportUiTest;
import com.adelylria.ringlog.ui.SettingsPanelTest;
import com.adelylria.ringlog.ui.StartupErrorUiTest;
import com.adelylria.ringlog.ui.ThemeManagerTest;
import com.adelylria.ringlog.ui.UpdateOverlayTest;
import com.adelylria.ringlog.ui.components.OpenStreetMapTileStoreTest;
import com.adelylria.ringlog.ui.importexport.ConflictReviewPanelTest;
import com.adelylria.ringlog.update.SemanticVersionTest;
import com.adelylria.ringlog.update.UpdateInstallerLauncherTest;
import com.adelylria.ringlog.update.UpdateCheckerTest;
import com.adelylria.ringlog.update.UpdateConfigurationTest;
import com.adelylria.ringlog.update.UpdateDownloadServiceTest;
import com.adelylria.ringlog.update.UpdateManifestTest;
import com.adelylria.ringlog.update.UpdatePreferencesTest;
import com.adelylria.ringlog.update.UpdateSignatureVerifierTest;
import com.formdev.flatlaf.FlatLaf;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.jdbc.DataSourceConnectionSource;
import org.sqlite.JDBC;

public final class RingLogTestSuite {

    private RingLogTestSuite() {
    }

    public static void main(String[] args) throws Exception {
        for (Class<?> test : List.of(
                DistributionConfigurationTest.class,
                ReleaseVersionToolTest.class,
                ReleaseToolingTest.class,
                SemanticVersionTest.class,
                UpdateConfigurationTest.class,
                UpdateManifestTest.class,
                UpdateSignatureVerifierTest.class,
                UpdateCheckerTest.class,
                UpdateDownloadServiceTest.class,
                UpdatePreferencesTest.class,
                UpdateInstallerLauncherTest.class,
                BuildInfoTest.class,
                BirdEventFormattingTest.class,
                BirdStatusCatalogTest.class,
                AppPathsTest.class,
                MediaPathResolverTest.class,
                LegacyDataLocationDetectorTest.class,
                LegacyDataLocationMigratorTest.class,
                ApplicationStorageBootstrapTest.class,
                SchemaVersionContractTest.class,
                SchemaMigrationRegistryTest.class,
                SchemaBackupServiceTest.class,
                DatabaseUpgradeServiceTest.class,
                RingLogLoggingTest.class,
                DiagnosticsServiceTest.class,
                OperationLoggingTest.class,
                SchemaMigrationTest.class,
                WorkbookFormatDetectorTest.class,
                LegacyV5WorkbookReaderTest.class,
                ImportFingerprintServiceTest.class,
                LegacyImportTransactionTest.class,
                ConflictResolutionServiceTest.class,
                RingLogV3CompatibilityTest.class,
                NativeExportRoundTripTest.class,
                LegacyImportServiceTest.class,
                BackupExportServiceTest.class,
                RecordReportServiceTest.class,
                BirdEventRepositoryTest.class,
                CatalogRepositoryTest.class,
                PortableReadOnlyTest.class,
                PortableCopyServiceTest.class,
                ApplicationIconTest.class,
                CalendarDatePickerTest.class,
                OpenStreetMapTileStoreTest.class,
                BirdEventUiTest.class,
                CatalogFormTest.class,
                CapturePanelTest.class,
                ThemeManagerTest.class,
                LayoutDesignTest.class,
                CatalogRefreshTest.class,
                ImportExportUiTest.class,
                ConflictReviewPanelTest.class,
                SettingsPanelTest.class,
                StartupErrorUiTest.class,
                UpdateOverlayTest.class,
                MainFrameNavigationTest.class
        )) {
            runIsolated(test);
        }
        System.out.println("RingLogTestSuite: PASS");
    }

    private static void runIsolated(Class<?> test) throws Exception {
        Path testDataRoot = Files.createTempDirectory(
                "ringlog-suite-" + test.getSimpleName() + '-'
        ).toAbsolutePath().normalize();
        try {
            Process process = new ProcessBuilder(
                    javaExecutable().toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dringlog.test.mode=true",
                    "-Dringlog.data.dir=" + testDataRoot,
                    "-cp",
                    testClasspath(),
                    test.getName()
            ).inheritIO().start();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new AssertionError(
                        test.getSimpleName() + " terminó con el código " + exitCode
                );
            }
        } finally {
            Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath().normalize();
            if (!testDataRoot.startsWith(temporaryRoot) || testDataRoot.equals(temporaryRoot)) {
                throw new AssertionError("Refusing to clean a non-temporary test data root");
            }
            try (var paths = Files.walk(testDataRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String testClasspath() throws URISyntaxException {
        Set<String> entries = new LinkedHashSet<>();
        String configuredClasspath = System.getProperty("java.class.path", "");
        for (String entry : configuredClasspath.split(
                java.util.regex.Pattern.quote(File.pathSeparator)
        )) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        while (loader != null) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
            loader = loader.getParent();
        }
        for (Class<?> type : List.of(
                RingLogTestSuite.class,
                RingLog.class,
                JDBC.class,
                Dao.class,
                DataSourceConnectionSource.class,
                FlatLaf.class
        )) {
            entries.add(Path.of(
                    type.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}

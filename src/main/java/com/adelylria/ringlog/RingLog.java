package com.adelylria.ringlog;

import java.nio.file.Path;

import javax.swing.SwingUtilities;

import com.adelylria.ringlog.application.ApplicationContext;
import com.adelylria.ringlog.application.ApplicationMode;
import com.adelylria.ringlog.diagnostics.LoggingInitialization;
import com.adelylria.ringlog.diagnostics.RingLogLogging;
import com.adelylria.ringlog.diagnostics.SafeLog;
import com.adelylria.ringlog.portable.PortableStorageBootstrap;
import com.adelylria.ringlog.preferences.ApplicationPreferences;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.ApplicationStorageBootstrap;
import com.adelylria.ringlog.storage.LegacyDataLocationDetector;
import com.adelylria.ringlog.storage.PortableAppPaths;
import com.adelylria.ringlog.storage.StartupStorageResult;
import com.adelylria.ringlog.storage.StartupStorageStatus;
import com.adelylria.ringlog.ui.MainFrame;
import com.adelylria.ringlog.ui.StartupErrorFrame;
import com.adelylria.ringlog.ui.StorageStartupFrame;
import com.adelylria.ringlog.ui.theme.ThemeManager;

public class RingLog {

    public static void main(String[] args) {
        Path requestedLog = null;
        try {
            ApplicationMode mode = ApplicationMode.detect();
            if (mode == ApplicationMode.PORTABLE_READ_ONLY) {
                PortableAppPaths paths = PortableAppPaths.fromRunningJar(RingLog.class);
                ApplicationContext context = ApplicationContext.portable(paths);
                ThemeManager.initialize(context.preferences());
                LoggingInitialization logging = RingLogLogging.initialize(paths);
                requestedLog = logging.logFile();
                new PortableStorageBootstrap().prepare(paths);
                SwingUtilities.invokeLater(() -> {
                    MainFrame frame = new MainFrame(context);
                    frame.setVisible(true);
                });
                return;
            }

            AppPaths paths = AppPaths.production();
            ApplicationPreferences preferences = ApplicationPreferences.production();
            ThemeManager.initialize(preferences);
            LoggingInitialization logging = RingLogLogging.initialize(paths);
            requestedLog = logging.logFile();
            LegacyDataLocationDetector detector =
                    LegacyDataLocationDetector.production(paths);
            ApplicationStorageBootstrap bootstrap = new ApplicationStorageBootstrap(
                    paths, detector
            );
            StartupStorageResult startup = bootstrap.prepare();
            SwingUtilities.invokeLater(() -> {
                if (startup.status() == StartupStorageStatus.READY) {
                    MainFrame frame = new MainFrame(ApplicationContext.normal(paths));
                    frame.setVisible(true);
                    frame.startUpdateCheck();
                } else {
                    StorageStartupFrame frame = new StorageStartupFrame(
                            paths, bootstrap, detector, startup
                    );
                    frame.setVisible(true);
                }
            });
        } catch (Exception exception) {
            SafeLog.failure("startup", exception);
            Path logFile = requestedLog;
            SwingUtilities.invokeLater(() -> {
                StartupErrorFrame frame = new StartupErrorFrame(exception, logFile);
                frame.setVisible(true);
            });
        }
    }
}

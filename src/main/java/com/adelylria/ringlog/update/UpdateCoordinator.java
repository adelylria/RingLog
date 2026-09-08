package com.adelylria.ringlog.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import javax.swing.SwingUtilities;

import com.adelylria.ringlog.diagnostics.BuildInfo;
import com.adelylria.ringlog.diagnostics.SafeLog;
import com.adelylria.ringlog.ui.update.UpdateOverlay;

/** Connects the non-blocking update services to RingLog's in-window experience. */
public final class UpdateCoordinator {

    private static final String PUBLIC_KEY_RESOURCE = "/ringlog-update-public-key.der";

    private final UpdateOverlay overlay;
    private final Runnable closeApplication;
    private final UpdatePreferences preferences;
    private final UpdateChecker checker;
    private final UpdateDownloadService downloads;
    private final UpdateArchitecture architecture;
    private final SemanticVersion currentVersion;

    private UpdateCoordinator(
            UpdateOverlay overlay,
            Runnable closeApplication,
            UpdatePreferences preferences,
            UpdateChecker checker,
            UpdateDownloadService downloads,
            UpdateArchitecture architecture,
            SemanticVersion currentVersion
    ) {
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.closeApplication = Objects.requireNonNull(closeApplication, "closeApplication");
        this.preferences = preferences;
        this.checker = checker;
        this.downloads = downloads;
        this.architecture = architecture;
        this.currentVersion = currentVersion;
    }

    public static UpdateCoordinator application(
            UpdateOverlay overlay,
            Runnable closeApplication
    ) {
        try {
            Optional<UpdateConfiguration> configuration = UpdateConfiguration.application();
            Optional<SemanticVersion> current = SemanticVersion.parse(BuildInfo.applicationVersion());
            Optional<UpdateArchitecture> architecture = UpdateArchitecture.current();
            if (configuration.isEmpty() || current.isEmpty() || architecture.isEmpty()
                    || Boolean.getBoolean("ringlog.test.mode")) {
                return disabled(overlay, closeApplication);
            }
            byte[] publicKey = readPublicKey();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            UpdateConfiguration channel = configuration.orElseThrow();
            UpdateChecker checker = new UpdateChecker(
                    client,
                    channel.manifestUri(),
                    channel.signatureUri(),
                    new UpdateManifestParser(),
                    UpdateSignatureVerifier.fromX509(publicKey),
                    architecture.orElseThrow(),
                    current.orElseThrow(),
                    false
            );
            return new UpdateCoordinator(
                    overlay,
                    closeApplication,
                    UpdatePreferences.production(),
                    checker,
                    UpdateDownloadService.production(client),
                    architecture.orElseThrow(),
                    current.orElseThrow()
            );
        } catch (IOException | UpdateException | RuntimeException exception) {
            SafeLog.warning("update_configuration", exception);
            return disabled(overlay, closeApplication);
        }
    }

    private static UpdateCoordinator disabled(UpdateOverlay overlay, Runnable closeApplication) {
        return new UpdateCoordinator(
                overlay, closeApplication, null, null, null, null, null
        );
    }

    public void checkAutomatically() {
        if (!isEnabled()) {
            return;
        }
        try {
            if (!preferences.claimAutomaticCheck()) {
                return;
            }
        } catch (IOException exception) {
            SafeLog.warning("update_preferences", exception);
            return;
        }
        check(false);
    }

    public void checkManually() {
        if (!isEnabled()) {
            overlay.showInformation(
                    "Actualizaciones no configuradas",
                    "El canal público se activará en el artefacto de distribución.",
                    "Cerrar"
            );
            return;
        }
        overlay.showChecking();
        check(true);
    }

    private boolean isEnabled() {
        return checker != null;
    }

    private void check(boolean manual) {
        checker.check().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                SafeLog.warning("update_check", root(failure));
                if (manual) {
                    overlay.showInformation(
                            "No se pudo comprobar",
                            "RingLog sigue funcionando con normalidad. Inténtalo más tarde.",
                            "Cerrar"
                    );
                }
                return;
            }
            switch (result.status()) {
                case UPDATE_AVAILABLE -> overlay.showAvailable(
                        currentVersion,
                        result.manifest().version(),
                        () -> beginDownload(result.asset()),
                        overlay::hideOverlay
                );
                case UP_TO_DATE -> {
                    if (manual) {
                        overlay.showInformation(
                                "RingLog está actualizado",
                                "Ya tienes instalada la versión " + currentVersion + '.',
                                "Cerrar"
                        );
                    }
                }
                case UNSUPPORTED_ARCHITECTURE -> {
                    SafeLog.warning("update_architecture", new IllegalStateException(
                            "No release asset for " + architecture.manifestKey()
                    ));
                    if (manual) {
                        overlay.showInformation(
                                "Actualización no disponible",
                                "La nueva versión aún no incluye un instalador para este equipo.",
                                "Cerrar"
                        );
                    }
                }
            }
        }));
    }

    private void beginDownload(UpdateAsset asset) {
        overlay.showDownloading(asset.size());
        downloads.download(
                asset,
                architecture,
                progress -> SwingUtilities.invokeLater(() -> overlay.updateProgress(progress))
        ).whenComplete((setup, failure) -> SwingUtilities.invokeLater(() -> {
            if (failure != null) {
                SafeLog.warning("update_download", root(failure));
                overlay.showInformation(
                        "No se pudo preparar la actualización",
                        "No se ha modificado la instalación actual.",
                        "Cerrar"
                );
                return;
            }
            try {
                UpdateInstallerLauncher.launch(setup, ProcessHandle.current().pid());
                closeApplication.run();
            } catch (IOException | RuntimeException exception) {
                SafeLog.warning("update_installer", exception);
                overlay.showInformation(
                        "No se pudo abrir el instalador",
                        "RingLog continúa abierto y no se ha sustituido ningún archivo.",
                        "Cerrar"
                );
            }
        }));
    }

    private static byte[] readPublicKey() throws IOException {
        try (InputStream input = UpdateCoordinator.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (input == null) {
                throw new IOException("No se encontró la clave pública de actualizaciones.");
            }
            byte[] bytes = input.readNBytes(1024);
            if (bytes.length == 0 || input.read() != -1) {
                throw new IOException("La clave pública de actualizaciones no es válida.");
            }
            return bytes;
        }
    }

    private static Throwable root(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException || result.getClass() == RuntimeException.class)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}

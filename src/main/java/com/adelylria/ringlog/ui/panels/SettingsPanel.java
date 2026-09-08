package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.diagnostics.DiagnosticsService;
import com.adelylria.ringlog.diagnostics.DiagnosticsSnapshot;
import com.adelylria.ringlog.importexport.nativeformat.ExportResult;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.DataPaths;
import com.adelylria.ringlog.application.ApplicationCapabilities;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.importexport.ImportAnalysis;
import com.adelylria.ringlog.ui.importexport.ImportExportController;
import com.adelylria.ringlog.ui.theme.ThemeManager;
import com.adelylria.ringlog.ui.theme.UiKit;

public class SettingsPanel extends JPanel {

    private final JFrame frame;
    private final DataPaths appPaths;
    private final ApplicationCapabilities capabilities;
    private final ImportExportController importExport;
    private final Runnable importCompleted;
    private final Runnable openConflicts;
    private final Runnable checkForUpdates;
    private final JCheckBox darkMode;
    private final JButton importButton;
    private final JButton exportButton;
    private final JLabel importStatus;
    private final JButton portableButton;

    public SettingsPanel(JFrame frame) {
        this(
                frame,
                AppPaths.production(),
                ImportExportController.applicationDefault(),
                () -> { },
                () -> { }
        );
    }

    public SettingsPanel(
            JFrame frame,
            ImportExportController importExport,
            Runnable importCompleted,
            Runnable openConflicts
    ) {
        this(frame, AppPaths.production(), importExport, importCompleted, openConflicts);
    }

    public SettingsPanel(
            JFrame frame,
            AppPaths appPaths,
            ImportExportController importExport,
            Runnable importCompleted,
            Runnable openConflicts
    ) {
        this(frame, appPaths, ApplicationCapabilities.normal(), importExport,
                importCompleted, openConflicts, () -> { }, () -> { });
    }

    public SettingsPanel(
            JFrame frame,
            AppPaths appPaths,
            ImportExportController importExport,
            Runnable importCompleted,
            Runnable openConflicts,
            Runnable checkForUpdates
    ) {
        this(frame, appPaths, ApplicationCapabilities.normal(), importExport,
                importCompleted, openConflicts, checkForUpdates, () -> { });
    }

    public SettingsPanel(
            JFrame frame,
            DataPaths appPaths,
            ApplicationCapabilities capabilities,
            ImportExportController importExport,
            Runnable importCompleted,
            Runnable openConflicts,
            Runnable checkForUpdates,
            Runnable createPortableCopy
    ) {
        this.frame = frame;
        this.appPaths = java.util.Objects.requireNonNull(appPaths, "appPaths");
        this.capabilities = java.util.Objects.requireNonNull(capabilities, "capabilities");
        this.importExport = importExport;
        this.importCompleted = importCompleted == null ? () -> { } : importCompleted;
        this.openConflicts = openConflicts == null ? () -> { } : openConflicts;
        this.checkForUpdates = checkForUpdates == null ? () -> { } : checkForUpdates;
        darkMode = new JCheckBox("Usar tema oscuro");
        darkMode.setSelected(ThemeManager.isDarkMode());
        importButton = UiKit.primaryButton("Importar historial");
        importButton.getAccessibleContext().setAccessibleName(
                "Importar historial XLSX o copia ZIP de RingLog"
        );
        importButton.setToolTipText(
                "Admite Migrador v5/v5.2, copias RingLog v3 y RingLog Export v1"
        );
        exportButton = UiKit.secondaryButton("Exportar copia");
        exportButton.getAccessibleContext().setAccessibleName(
                "Exportar una copia completa y restaurable de RingLog"
        );
        exportButton.setToolTipText(
                "Crea un Excel si no hay fotos o un ZIP completo si las hay"
        );
        importStatus = UiKit.muted(
                "Admite el migrador, copias anteriores y el formato nativo de RingLog."
        );
        portableButton = UiKit.primaryButton("Crear copia portátil");
        portableButton.setName("createPortableCopy");
        portableButton.addActionListener(event -> createPortableCopy.run());
        initialize();
    }

    public SettingsPanel() {
        this(null);
    }

    private void initialize() {
        setLayout(new BorderLayout());
        JPanel page = UiKit.pagePanel();
        page.add(new PageHeader(
                "PREFERENCIAS", "Ajustes",
                "Personaliza RingLog y protege todo tu diario.", null
        ), BorderLayout.NORTH);

        JPanel cards = UiKit.verticalScrollPanel();
        cards.add(appearanceCard());
        cards.add(Box.createVerticalStrut(14));
        cards.add(dataCard());
        if (capabilities.createPortableCopy()) {
            cards.add(Box.createVerticalStrut(14));
            cards.add(portableCard());
        }
        cards.add(Box.createVerticalStrut(14));
        cards.add(aboutCard());
        page.add(UiKit.scrollPane(cards), BorderLayout.CENTER);
        add(page, BorderLayout.CENTER);
    }

    private JPanel appearanceCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Apariencia"), BorderLayout.NORTH);
        JPanel copy = verticalCopy();
        copy.add(darkMode);
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(
                "El tema se aplica al instante y se conserva para próximos inicios."
        ));
        card.add(copy, BorderLayout.CENTER);
        darkMode.addActionListener(event -> applyTheme());
        return card;
    }

    private JPanel dataCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel(capabilities.readOnly()
                ? "Esta copia portátil" : "Copias e importación"), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        JPanel copy = verticalCopy();
        copy.add(UiKit.valueLabel(capabilities.readOnly()
                ? "Consulta segura, sin modificar el diario"
                : "Conserva y recupera tu diario"));
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(capabilities.readOnly()
                ? "Los cambios se realizan únicamente en el RingLog principal."
                : "La copia incluye registros, catálogos, trazabilidad y fotografías."));
        copy.add(Box.createVerticalStrut(9));
        if (!capabilities.readOnly()) {
            copy.add(importStatus);
        }
        body.add(copy, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        if (capabilities.exportNativeBackup()) {
            actions.add(exportButton);
        }
        if (capabilities.importOrRestore()) {
            actions.add(importButton);
        }
        body.add(actions, BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);

        importButton.addActionListener(event -> startImportChooser());
        exportButton.addActionListener(event -> startExportChooser());
        return card;
    }

    private JPanel portableCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Copia portátil"), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        JPanel copy = verticalCopy();
        copy.add(UiKit.valueLabel("Lleva tu diario en modo de solo lectura"));
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(
                "Incluye RingLog, Java, la base de datos y todas las fotografías."
        ));
        body.add(copy, BorderLayout.CENTER);
        JPanel action = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        action.setOpaque(false);
        action.add(portableButton);
        body.add(action, BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel aboutCard() {
        DiagnosticsSnapshot diagnostics = new DiagnosticsService().snapshot(appPaths);
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Acerca de RingLog"), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        JPanel copy = verticalCopy();
        copy.add(UiKit.valueLabel("RingLog " + diagnostics.applicationVersion()));
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted("Java · " + diagnostics.javaVersion()));
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted("Sistema · " + diagnostics.operatingSystem()));
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted("Schema · "
                + (diagnostics.schemaVersion() < 0 ? "no disponible" : diagnostics.schemaVersion())));
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted("Datos · " + diagnostics.dataRoot()));
        body.add(copy, BorderLayout.CENTER);

        JButton openLogs = UiKit.secondaryButton("Abrir carpeta de logs");
        openLogs.setEnabled(canOpenLogDirectory(diagnostics.logFile()));
        openLogs.addActionListener(event -> openLogDirectory(diagnostics.logFile()));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        actions.add(openLogs);
        if (capabilities.checkUpdates()) {
            JButton updates = UiKit.primaryButton("Buscar actualizaciones");
            updates.getAccessibleContext().setAccessibleName(
                    "Buscar actualizaciones de RingLog"
            );
            updates.addActionListener(event -> checkForUpdates.run());
            actions.add(updates);
        }
        body.add(actions, BorderLayout.EAST);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private static boolean canOpenLogDirectory(Path logFile) {
        return logFile != null
                && logFile.getParent() != null
                && Files.isDirectory(logFile.getParent())
                && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
    }

    private static void openLogDirectory(Path logFile) {
        if (!canOpenLogDirectory(logFile)) {
            return;
        }
        try {
            Desktop.getDesktop().open(logFile.getParent().toFile());
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics remain visible even when Windows cannot open Explorer.
        }
    }

    private static JPanel verticalCopy() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    public void startImportChooser() {
        if (!capabilities.importOrRestore() || importExport == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar historial o copia de RingLog");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Archivos de RingLog (*.xlsx, *.zip)", "xlsx", "zip"
        ));
        chooser.setSelectedFile(new File("ringlog-import.xlsx"));
        if (chooser.showOpenDialog(dialogParent()) == JFileChooser.APPROVE_OPTION) {
            analyzeImport(chooser.getSelectedFile().toPath());
        }
    }

    public void startExportChooser() {
        if (!capabilities.exportNativeBackup() || importExport == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Guardar copia completa de RingLog");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Copia de RingLog (*.xlsx o *.zip)", "xlsx", "zip"
        ));
        chooser.setSelectedFile(new File(
                "RingLog-copia-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        ));
        if (chooser.showSaveDialog(dialogParent()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = withoutKnownExtension(chooser.getSelectedFile().toPath());
        if (outputExists(selected) && !confirmOverwrite()) {
            return;
        }
        runExport(selected);
    }

    private void analyzeImport(Path file) {
        setBusy(true, "Comprobando el archivo…", false);
        new SwingWorker<ImportAnalysis, Void>() {
            @Override
            protected ImportAnalysis doInBackground() throws Exception {
                return importExport.analyze(file);
            }

            @Override
            protected void done() {
                try {
                    ImportAnalysis analysis = get();
                    setBusy(false, "Archivo comprobado y listo para importar.", false);
                    if (confirmImport(analysis)) {
                        runImport(analysis);
                    } else {
                        closeQuietly(analysis);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showImportError(exception);
                } catch (ExecutionException | RuntimeException exception) {
                    showImportError(rootCause(exception));
                }
            }
        }.execute();
    }

    private boolean confirmImport(ImportAnalysis analysis) {
        JPanel content = verticalCopy();
        content.add(UiKit.valueLabel(analysis.file().getFileName().toString()));
        content.add(Box.createVerticalStrut(4));
        content.add(UiKit.muted(analysis.formatName()));
        content.add(Box.createVerticalStrut(10));
        content.add(UiKit.valueLabel(analysis.eventsCount() + " registros"));
        content.add(UiKit.muted(
                analysis.birdsCount() + " aves · "
                        + analysis.speciesCount() + " especies · "
                        + analysis.placesCount() + " lugares"
        ));
        content.add(UiKit.muted(analysis.photosCount() + " fotografías o archivos asociados"));
        if (analysis.warningCount() > 0) {
            content.add(Box.createVerticalStrut(9));
            content.add(UiKit.muted(
                    analysis.warningCount() + " avisos de trazabilidad conservados"
            ));
        }
        if (analysis.requiresConflictReview()) {
            content.add(Box.createVerticalStrut(9));
            content.add(UiKit.valueLabel(
                    analysis.pendingConflictCount() + " campos necesitan revisión"
            ));
            content.add(UiKit.muted(
                    "Se importarán sin perder ninguna fuente y podrás decidirlos después."
            ));
        } else if (analysis.format() == ImportFormat.RINGLOG_EXPORT_V1) {
            content.add(Box.createVerticalStrut(9));
            content.add(UiKit.muted(
                    "Es una restauración nativa: conservará también las decisiones ya tomadas."
            ));
        }
        content.add(Box.createVerticalStrut(12));
        content.add(UiKit.muted(
                "La operación es transaccional y no duplicará una importación ya aplicada."
        ));

        return JOptionPane.showConfirmDialog(
                dialogParent(), content, "Revisar importación",
                JOptionPane.OK_CANCEL_OPTION,
                analysis.requiresConflictReview()
                        ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE
        ) == JOptionPane.OK_OPTION;
    }

    private void runImport(ImportAnalysis analysis) {
        setBusy(true, "Importando el historial…", false);
        new SwingWorker<ImportExecutionResult, Void>() {
            @Override
            protected ImportExecutionResult doInBackground() throws Exception {
                try (analysis) {
                    return importExport.execute(analysis);
                }
            }

            @Override
            protected void done() {
                try {
                    ImportExecutionResult result = get();
                    importCompleted.run();
                    boolean duplicate = result.status()
                            == ImportExecutionResult.Status.ALREADY_IMPORTED;
                    setBusy(false, duplicate
                            ? "Esta copia ya estaba importada; no se duplicó nada."
                            : "Importación completada correctamente.", false);
                    showImportResult(analysis, duplicate);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showImportError(exception);
                } catch (ExecutionException | RuntimeException exception) {
                    showImportError(rootCause(exception));
                }
            }
        }.execute();
    }

    private void showImportResult(ImportAnalysis analysis, boolean duplicate) {
        String message = duplicate
                ? "RingLog ha reconocido esta importación y ha conservado los datos actuales."
                : "Se han incorporado " + analysis.eventsCount()
                        + " registros con su catálogo y trazabilidad.";
        if (analysis.requiresConflictReview() && !duplicate) {
            int answer = JOptionPane.showConfirmDialog(
                    dialogParent(), message + "\n\nQuedan "
                            + analysis.pendingConflictCount()
                            + " campos por comprobar. ¿Quieres revisarlos ahora?",
                    "Importación completada", JOptionPane.YES_NO_OPTION,
                    JOptionPane.INFORMATION_MESSAGE
            );
            if (answer == JOptionPane.YES_OPTION) {
                openConflicts.run();
            }
            return;
        }
        JOptionPane.showMessageDialog(
                dialogParent(), message, "Importación completada",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void runExport(Path destination) {
        setBusy(true, "Creando y verificando la copia…", true);
        new SwingWorker<ExportResult, Void>() {
            @Override
            protected ExportResult doInBackground() throws Exception {
                return importExport.exportTo(destination);
            }

            @Override
            protected void done() {
                try {
                    ExportResult result = get();
                    setBusy(false, "Copia guardada: " + result.file().getFileName(), true);
                    String packaging = result.binaryCount() == 0
                            ? "Excel autocontenido"
                            : "ZIP con Excel y " + result.binaryCount() + " archivos";
                    JOptionPane.showMessageDialog(
                            dialogParent(), packaging + "\n\n" + result.file(),
                            "Copia completada", JOptionPane.INFORMATION_MESSAGE
                    );
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showExportError(exception);
                } catch (ExecutionException | RuntimeException exception) {
                    showExportError(rootCause(exception));
                }
            }
        }.execute();
    }

    private boolean confirmOverwrite() {
        return JOptionPane.showConfirmDialog(
                dialogParent(),
                "Ya existe una copia con ese nombre. ¿Quieres sustituirla?",
                "Sustituir copia", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.OK_OPTION;
    }

    private void showImportError(Throwable exception) {
        setBusy(false, "No se pudo completar la importación.", false);
        JOptionPane.showMessageDialog(
                dialogParent(), userMessage(exception), "No se pudo importar",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private void showExportError(Throwable exception) {
        setBusy(false, "No se pudo crear la copia.", true);
        JOptionPane.showMessageDialog(
                dialogParent(), userMessage(exception), "No se pudo exportar",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private void setBusy(boolean busy, String message, boolean exporting) {
        importButton.setEnabled(!busy);
        exportButton.setEnabled(!busy);
        importButton.setText(busy && !exporting ? "Procesando…" : "Importar historial");
        exportButton.setText(busy && exporting ? "Guardando…" : "Exportar copia");
        importStatus.setText(message);
    }

    private Component dialogParent() {
        return frame == null ? this : frame;
    }

    private void applyTheme() {
        ThemeManager.updateApplicationTheme(frame, darkMode.isSelected());
    }

    public void setMutationActionsEnabled(boolean enabled) {
        importButton.setEnabled(enabled && capabilities.importOrRestore());
        exportButton.setEnabled(enabled && capabilities.exportNativeBackup());
        portableButton.setEnabled(enabled && capabilities.createPortableCopy());
    }

    public static boolean toggleValue(boolean current) {
        return !current;
    }

    private static Path withoutKnownExtension(Path selected) {
        String name = selected.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".zip")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        return selected.resolveSibling(name);
    }

    private static boolean outputExists(Path base) {
        return Files.exists(base.resolveSibling(base.getFileName() + ".xlsx"))
                || Files.exists(base.resolveSibling(base.getFileName() + ".zip"));
    }

    private static void closeQuietly(ImportAnalysis analysis) {
        try {
            analysis.close();
        } catch (IOException ignored) {
            // The user cancelled before persistent state was touched.
        }
    }

    private static Throwable rootCause(Throwable exception) {
        return exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause() : exception;
    }

    private static String userMessage(Throwable exception) {
        return exception == null || exception.getMessage() == null
                ? "Se produjo un error inesperado."
                : exception.getMessage();
    }
}

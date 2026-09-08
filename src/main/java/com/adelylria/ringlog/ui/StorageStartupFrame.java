package com.adelylria.ringlog.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.ApplicationStorageBootstrap;
import com.adelylria.ringlog.storage.DatabaseCandidate;
import com.adelylria.ringlog.storage.LegacyDataLocationDetector;
import com.adelylria.ringlog.storage.LegacyDataLocationMigrator;
import com.adelylria.ringlog.storage.MigrationIssue;
import com.adelylria.ringlog.storage.MigrationResult;
import com.adelylria.ringlog.storage.MigrationStatus;
import com.adelylria.ringlog.storage.StartupStorageResult;
import com.adelylria.ringlog.storage.StartupStorageStatus;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Full-size, in-app migration flow; never a floating JOptionPane wizard. */
public final class StorageStartupFrame extends JFrame {

    private final AppPaths paths;
    private final ApplicationStorageBootstrap bootstrap;
    private final LegacyDataLocationDetector detector;
    private final StartupStorageResult startup;
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    private final JLabel progress = UiKit.muted("Tus datos anteriores permanecen intactos.");
    private final JTextArea feedback = new JTextArea();

    public StorageStartupFrame(
            AppPaths paths,
            ApplicationStorageBootstrap bootstrap,
            LegacyDataLocationDetector detector,
            StartupStorageResult startup
    ) {
        this.paths = paths;
        this.bootstrap = bootstrap;
        this.detector = detector;
        this.startup = startup;
        initializeWindow();
        initializeContent();
    }

    private void initializeWindow() {
        setTitle("RingLog · Preparar diario");
        ApplicationIcon.applyTo(this);
        setSize(1280, 820);
        setMinimumSize(new Dimension(1040, 680));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    private void initializeContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(brandPanel(), BorderLayout.WEST);

        JPanel page = UiKit.pagePanel();
        String title = startup.status() == StartupStorageStatus.USER_DECISION_REQUIRED
                ? "Elige qué diario quieres abrir"
                : "Hemos encontrado tu diario anterior";
        String subtitle = startup.status() == StartupStorageStatus.USER_DECISION_REQUIRED
                ? "Hay dos bases distintas. RingLog no va a sobrescribir ni fusionar ninguna."
                : "Lo trasladaremos al espacio estable de RingLog sin modificar el original.";
        page.add(new PageHeader("PREPARAR RINGLOG", title, subtitle, null), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(locationComparison());
        body.add(Box.createVerticalStrut(16));
        feedback.setEditable(false);
        feedback.setLineWrap(true);
        feedback.setWrapStyleWord(true);
        feedback.setRows(5);
        feedback.setVisible(false);
        feedback.setBorder(UiKit.cardBorder());
        body.add(feedback);
        JScrollPane bodyScroll = new JScrollPane(body);
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());
        bodyScroll.setOpaque(false);
        bodyScroll.getViewport().setOpaque(false);
        page.add(bodyScroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        footer.add(progress, BorderLayout.CENTER);
        actions.setOpaque(false);
        configureActions();
        footer.add(actions, BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);

        root.add(page, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel brandPanel() {
        JPanel brand = new JPanel();
        brand.setPreferredSize(new Dimension(244, 0));
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(BorderFactory.createEmptyBorder(34, 24, 24, 24));
        JLabel name = new JLabel("RingLog");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 26f));
        brand.add(name);
        brand.add(Box.createVerticalStrut(4));
        brand.add(UiKit.muted("Diario de campo"));
        brand.add(Box.createVerticalGlue());
        brand.add(UiKit.muted("Tus observaciones, a tu ritmo."));
        return brand;
    }

    private JPanel locationComparison() {
        JPanel grid = new JPanel(new GridLayout(1, 2, 14, 0));
        grid.setOpaque(false);
        DatabaseCandidate legacy = startup.assessment().legacyDatabase();
        DatabaseCandidate managed = startup.assessment().managedDatabase();
        grid.add(locationCard(
                "Diario anterior",
                legacy == null ? startup.migrationSource() : legacy.path(),
                legacy,
                "Se conservará en su ubicación actual."
        ));
        grid.add(locationCard(
                "Ubicación de RingLog",
                paths.databasePath(),
                managed,
                managed == null
                        ? "Aquí se publicará la copia verificada."
                        : "Esta base ya existe y no se sobrescribirá automáticamente."
        ));
        return grid;
    }

    private JPanel locationCard(
            String title,
            Path path,
            DatabaseCandidate candidate,
            String explanation
    ) {
        SurfacePanel card = new SurfacePanel(new BorderLayout());
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(UiKit.cardBorder());
        JLabel heading = UiKit.valueLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        card.add(heading);
        card.add(Box.createVerticalStrut(9));
        JTextArea location = new JTextArea(path == null ? "No disponible" : path.toString());
        location.setEditable(false);
        location.setLineWrap(true);
        location.setWrapStyleWord(true);
        location.setOpaque(false);
        location.setFont(location.getFont().deriveFont(13f));
        card.add(location);
        if (candidate != null) {
            card.add(Box.createVerticalStrut(8));
            card.add(UiKit.muted(formatBytes(candidate.size())
                    + " · modificado " + candidate.modifiedAt()));
        }
        card.add(Box.createVerticalStrut(12));
        card.add(UiKit.muted(explanation));
        return card;
    }

    private void configureActions() {
        if (startup.status() == StartupStorageStatus.USER_DECISION_REQUIRED) {
            JButton managed = UiKit.primaryButton("Usar el diario de RingLog");
            managed.addActionListener(event -> useManaged());
            actions.add(managed);
            JButton choose = UiKit.secondaryButton("Elegir otro archivo");
            choose.addActionListener(event -> chooseDatabase());
            actions.add(choose);
            return;
        }
        if (startup.status() == StartupStorageStatus.MANAGED_MEDIA_MIGRATION_REQUIRED) {
            showFeedback("Esta base ya está en AppData, pero aún usa referencias antiguas. "
                    + "Selecciona su copia original para realizar la conversión segura.", true);
            JButton choose = UiKit.primaryButton("Elegir base original");
            choose.addActionListener(event -> chooseDatabase());
            actions.add(choose);
            return;
        }
        JButton fresh = UiKit.secondaryButton("Empezar un diario nuevo");
        fresh.addActionListener(event -> startFresh());
        actions.add(fresh);
        JButton choose = UiKit.secondaryButton("Elegir otro archivo");
        choose.addActionListener(event -> chooseDatabase());
        actions.add(choose);
        JButton migrate = UiKit.primaryButton("Continuar migración");
        migrate.addActionListener(event -> beginMigration(startup.migrationSource()));
        actions.add(migrate);
    }

    private void useManaged() {
        try {
            StartupStorageResult result = bootstrap.useManaged(startup.assessment());
            if (result.status() == StartupStorageStatus.READY) {
                openDiary();
            } else {
                showFeedback("La base elegida necesita convertir primero sus fotografías.", true);
            }
        } catch (Exception exception) {
            showFeedback(userMessage(exception), true);
        }
    }

    private void startFresh() {
        try {
            StartupStorageResult result = bootstrap.startFresh(startup.assessment());
            if (result.status() == StartupStorageStatus.READY) {
                openDiary();
            }
        } catch (Exception exception) {
            showFeedback(userMessage(exception), true);
        }
    }

    private void chooseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Elegir base de datos anterior de RingLog");
        chooser.setFileFilter(new FileNameExtensionFilter("Base de datos SQLite (*.db)", "db"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        try {
            detector.inspectUserSelected(selected.toPath());
            beginMigration(selected.toPath());
        } catch (Exception exception) {
            showFeedback(userMessage(exception), true);
        }
    }

    private void beginMigration(Path source) {
        if (source == null) {
            showFeedback("No se ha seleccionado una base de datos anterior.", true);
            return;
        }
        setBusy(true);
        showFeedback("Preparando una copia consistente y verificando fotografías…", false);
        new SwingWorker<MigrationResult, String>() {
            @Override
            protected MigrationResult doInBackground() throws Exception {
                return new LegacyDataLocationMigrator().migrate(
                        source,
                        paths,
                        state -> publish(stageText(state.stage().name()))
                );
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    progress.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    MigrationResult result = get();
                    if (result.status() == MigrationStatus.COMPLETED) {
                        openDiary();
                        return;
                    }
                    showFeedback(issueText(result.issues()), true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFeedback("La migración se ha interrumpido sin publicar cambios.", true);
                } catch (ExecutionException exception) {
                    showFeedback(userMessage(exception.getCause()), true);
                }
                setBusy(false);
            }
        }.execute();
    }

    private void openDiary() {
        dispose();
        MainFrame frame = new MainFrame(paths);
        frame.setVisible(true);
        frame.startUpdateCheck();
    }

    private void setBusy(boolean busy) {
        for (java.awt.Component component : actions.getComponents()) {
            component.setEnabled(!busy);
        }
        progress.setText(busy ? "Migración en curso…" : "No se ha modificado el origen.");
    }

    private void showFeedback(String message, boolean error) {
        feedback.setText(message);
        feedback.setForeground(error ? new Color(190, 68, 68) : getForeground());
        feedback.setVisible(true);
        feedback.revalidate();
    }

    private static String issueText(List<MigrationIssue> issues) {
        if (issues.isEmpty()) {
            return "La migración necesita atención antes de continuar.";
        }
        StringBuilder result = new StringBuilder(
                "No se ha publicado nada porque faltan medios. "
                        + "Conservamos sus referencias para poder relocalizarlos:\n\n"
        );
        for (MigrationIssue issue : issues) {
            result.append("• ").append(issue.originalReference())
                    .append(" — ").append(issue.mediaStatus()).append('\n');
        }
        return result.toString();
    }

    private static String stageText(String stage) {
        return switch (stage) {
            case "SQLITE_SNAPSHOT_CREATED" -> "Copia SQLite consistente creada.";
            case "MEDIA_STAGED" -> "Fotografías copiadas y verificadas.";
            case "SCHEMA_V4_MARKED" -> "Referencias relativas validadas.";
            case "MEDIA_PUBLISHED" -> "Fotografías publicadas de forma segura.";
            case "DATABASE_PUBLISHED" -> "Diario preparado.";
            default -> "Preparando migración…";
        };
    }

    private static String formatBytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024d);
        }
        return String.format("%.1f MB", size / (1024d * 1024d));
    }

    private static String userMessage(Throwable exception) {
        if (exception == null || exception.getMessage() == null
                || exception.getMessage().isBlank()) {
            return "No se pudo completar la preparación de RingLog.";
        }
        return exception.getMessage();
    }
}

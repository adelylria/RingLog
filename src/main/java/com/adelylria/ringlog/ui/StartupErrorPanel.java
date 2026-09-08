package com.adelylria.ringlog.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.adelylria.ringlog.database.FutureSchemaVersionException;
import com.adelylria.ringlog.diagnostics.LogSanitizer;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Themed in-app startup failure screen; never displays a raw stack trace. */
public final class StartupErrorPanel extends JPanel {

    public StartupErrorPanel(Throwable failure, Path logFile) {
        setLayout(new BorderLayout());
        JPanel page = UiKit.pagePanel();
        boolean future = failure instanceof FutureSchemaVersionException;
        String title = future
                ? "Necesitas una versión más reciente"
                : "No se ha podido abrir tu diario";
        String subtitle = future
                ? "Tus datos no se han modificado. Actualiza RingLog antes de volver a abrirlos."
                : "RingLog ha detenido el arranque para proteger tus datos.";
        page.add(new PageHeader("INICIO SEGURO", title, subtitle, null), BorderLayout.NORTH);

        JPanel card = UiKit.sectionPanel();
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.valueLabel(explanation(failure)));
        copy.add(Box.createVerticalStrut(8));
        copy.add(UiKit.muted("Detalle técnico: " + LogSanitizer.failure(failure)));
        if (logFile != null) {
            copy.add(Box.createVerticalStrut(8));
            copy.add(UiKit.muted("Registro de diagnóstico: " + logFile));
        }
        card.add(copy, BorderLayout.CENTER);
        page.add(card, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        JButton openLogs = UiKit.secondaryButton("Abrir carpeta de logs");
        openLogs.setEnabled(canOpen(logFile));
        openLogs.addActionListener(event -> openParent(logFile));
        JButton exit = UiKit.primaryButton("Cerrar RingLog");
        exit.addActionListener(event -> System.exit(1));
        actions.add(openLogs);
        actions.add(exit);
        page.add(actions, BorderLayout.SOUTH);
        add(page, BorderLayout.CENTER);
    }

    private static String explanation(Throwable failure) {
        if (failure instanceof FutureSchemaVersionException future) {
            return "La base usa schema v" + future.detectedSchema()
                    + " y este RingLog admite hasta schema v" + future.supportedSchema() + '.';
        }
        return "Puedes consultar el registro técnico o volver a intentarlo sin riesgo para la base.";
    }

    private static boolean canOpen(Path logFile) {
        return logFile != null
                && logFile.getParent() != null
                && Files.isDirectory(logFile.getParent())
                && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
    }

    private static void openParent(Path logFile) {
        if (!canOpen(logFile)) {
            return;
        }
        try {
            Desktop.getDesktop().open(logFile.getParent().toFile());
        } catch (IOException | RuntimeException failure) {
            // Startup remains on this safe screen; persistent diagnostics already contain context.
        }
    }
}

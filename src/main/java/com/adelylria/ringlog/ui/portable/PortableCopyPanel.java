package com.adelylria.ringlog.ui.portable;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.portable.PortableCopyEstimate;
import com.adelylria.ringlog.portable.PortableCopyProgress;
import com.adelylria.ringlog.portable.PortableCopyResult;
import com.adelylria.ringlog.portable.PortableCopyService;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Integrated flow for selecting, confirming, and creating a portable copy. */
public final class PortableCopyPanel extends JPanel {

    private final PortableCopyServiceFactory serviceFactory;
    private final Runnable onBack;
    private final Consumer<Boolean> busyChanged;
    private final JLabel destination = UiKit.valueLabel("Ninguna carpeta seleccionada");
    private final JLabel estimate = UiKit.muted(
            "Selecciona una unidad o carpeta con espacio suficiente."
    );
    private final JLabel status = UiKit.muted(
            "La copia existente no se modificará hasta validar completamente la nueva."
    );
    private final JCheckBox replaceConfirmation = new JCheckBox(
            "Sustituir completamente la copia portátil existente"
    );
    private final JButton chooseButton = UiKit.secondaryButton("Elegir destino");
    private final JButton createButton = UiKit.primaryButton("Crear copia portátil");
    private Path destinationParent;
    private PortableCopyEstimate currentEstimate;
    private boolean busy;

    public PortableCopyPanel(
            PortableCopyServiceFactory serviceFactory,
            Runnable onBack,
            Consumer<Boolean> busyChanged
    ) {
        this.serviceFactory = java.util.Objects.requireNonNull(
                serviceFactory, "serviceFactory"
        );
        this.onBack = onBack == null ? () -> { } : onBack;
        this.busyChanged = busyChanged == null ? ignored -> { } : busyChanged;
        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());
        JPanel page = UiKit.pagePanel();
        JButton back = UiKit.secondaryButton("← Volver a Ajustes");
        back.addActionListener(event -> {
            if (!busy) {
                onBack.run();
            }
        });
        page.add(new PageHeader(
                "COPIA PORTÁTIL",
                "Lleva tu diario contigo",
                "Una copia completa para consultar RingLog sin instalar ni modificar datos.",
                back
        ), BorderLayout.NORTH);

        JPanel body = UiKit.verticalScrollPanel();
        body.add(destinationCard());
        body.add(Box.createVerticalStrut(14));
        body.add(contentsCard());
        body.add(Box.createVerticalStrut(14));
        body.add(progressCard());
        page.add(UiKit.scrollPane(body), BorderLayout.CENTER);
        add(page, BorderLayout.CENTER);
    }

    private JPanel destinationCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Destino"), BorderLayout.NORTH);
        JPanel content = new JPanel(new BorderLayout(18, 0));
        content.setOpaque(false);
        JPanel copy = vertical();
        copy.add(destination);
        copy.add(Box.createVerticalStrut(5));
        copy.add(estimate);
        copy.add(Box.createVerticalStrut(10));
        replaceConfirmation.setVisible(false);
        replaceConfirmation.setOpaque(false);
        replaceConfirmation.addActionListener(event -> refreshCreateEnabled());
        copy.add(replaceConfirmation);
        content.add(copy, BorderLayout.CENTER);
        content.add(chooseButton, BorderLayout.EAST);
        chooseButton.addActionListener(event -> chooseDestination());
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JPanel contentsCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("La copia incluirá"), BorderLayout.NORTH);
        JPanel copy = vertical();
        copy.add(UiKit.muted("• RingLog y sus librerías"));
        copy.add(UiKit.muted("• Java 17 x64 incluido"));
        copy.add(UiKit.muted("• Base de datos coherente"));
        copy.add(UiKit.muted("• Fotografías asignadas y sin asignar"));
        copy.add(UiKit.muted("• Informes PDF y XLSX en modo de solo lectura"));
        card.add(copy, BorderLayout.CENTER);
        return card;
    }

    private JPanel progressCard() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Estado"), BorderLayout.NORTH);
        JPanel content = new JPanel(new BorderLayout(18, 0));
        content.setOpaque(false);
        JPanel copy = vertical();
        copy.add(status);
        content.add(copy, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        createButton.setEnabled(false);
        createButton.addActionListener(event -> createPortableCopy());
        actions.add(createButton);
        content.add(actions, BorderLayout.EAST);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    public void prepare() {
        if (!busy) {
            status.setText(
                    "La copia existente no se modificará hasta validar completamente la nueva."
            );
        }
    }

    private void chooseDestination() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Elegir dónde crear RingLog-Portatil");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (destinationParent != null) {
            chooser.setCurrentDirectory(destinationParent.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        destinationParent = selected.toPath().toAbsolutePath().normalize();
        destination.setText(destinationParent.resolve(PortableCopyService.DIRECTORY_NAME).toString());
        replaceConfirmation.setSelected(false);
        checkDestination();
    }

    private void checkDestination() {
        setBusy(true, "Comprobando el contenido y el espacio disponible…");
        new SwingWorker<PortableCopyEstimate, Void>() {
            @Override
            protected PortableCopyEstimate doInBackground() throws Exception {
                return serviceFactory.create().preflight(destinationParent);
            }

            @Override
            protected void done() {
                try {
                    currentEstimate = get();
                    replaceConfirmation.setVisible(currentEstimate.replacingExistingCopy());
                    estimate.setText(
                            formatSize(currentEstimate.estimatedCopyBytes())
                                    + " para la nueva copia · "
                                    + formatSize(currentEstimate.usableSpaceBytes())
                                    + " disponibles"
                    );
                    setBusy(false, currentEstimate.replacingExistingCopy()
                            ? "La copia actual se conservará hasta validar la nueva."
                            : "Destino preparado para crear la copia.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFailure("Se interrumpió la comprobación del destino.");
                } catch (ExecutionException exception) {
                    showFailure(userMessage(exception.getCause()));
                }
            }
        }.execute();
    }

    private void createPortableCopy() {
        if (currentEstimate == null || busy) {
            return;
        }
        setBusy(true, "Creando copia portátil. No cierres RingLog ni retires el dispositivo.");
        busyChanged.accept(true);
        new SwingWorker<PortableCopyResult, PortableCopyProgress>() {
            @Override
            protected PortableCopyResult doInBackground() throws Exception {
                return serviceFactory.create().create(
                        destinationParent,
                        replaceConfirmation.isSelected(),
                        this::publish
                );
            }

            @Override
            protected void process(java.util.List<PortableCopyProgress> chunks) {
                if (!chunks.isEmpty()) {
                    status.setText(chunks.get(chunks.size() - 1).message());
                }
            }

            @Override
            protected void done() {
                busyChanged.accept(false);
                try {
                    PortableCopyResult result = get();
                    currentEstimate = null;
                    replaceConfirmation.setVisible(false);
                    setBusy(false, "Copia lista: " + result.portableRoot());
                    createButton.setEnabled(false);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFailure("Se interrumpió la creación de la copia.");
                } catch (ExecutionException exception) {
                    showFailure(userMessage(exception.getCause()));
                }
            }
        }.execute();
    }

    private void showFailure(String message) {
        currentEstimate = null;
        replaceConfirmation.setVisible(false);
        setBusy(false, message);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        chooseButton.setEnabled(!value);
        status.setText(message);
        refreshCreateEnabled();
    }

    private void refreshCreateEnabled() {
        createButton.setEnabled(!busy && currentEstimate != null
                && (!currentEstimate.replacingExistingCopy()
                || replaceConfirmation.isSelected()));
    }

    private static JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) {
            return Math.max(1, bytes / 1024L) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static String userMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "No se pudo crear la copia portátil."
                : failure.getMessage();
    }

    @FunctionalInterface
    public interface PortableCopyServiceFactory {
        PortableCopyService create() throws Exception;
    }
}

package com.adelylria.ringlog.ui.update;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.update.DownloadProgress;
import com.adelylria.ringlog.update.SemanticVersion;

/** Themed glass-pane experience for update checks and downloads. */
public final class UpdateOverlay extends JPanel {

    private final JPanel card = UiKit.sectionPanel();
    private JProgressBar progressBar;

    public UpdateOverlay() {
        super(new GridBagLayout());
        setOpaque(false);
        setVisible(false);
        card.setBorder(UiKit.cardBorder());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(24, 24, 24, 24);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.ipadx = 36;
        add(card, constraints);
    }

    public void showAvailable(
            SemanticVersion installed,
            SemanticVersion available,
            Runnable updateNow,
            Runnable later
    ) {
        JPanel body = verticalBody();
        body.add(UiKit.valueLabel("Versión instalada · " + installed));
        body.add(Box.createVerticalStrut(6));
        body.add(UiKit.valueLabel("Nueva versión · " + available));
        body.add(Box.createVerticalStrut(10));
        body.add(UiKit.muted(
                "La descarga se verificará antes de cerrar RingLog. Tus datos no se modificarán."
        ));
        JButton install = UiKit.primaryButton("Actualizar ahora");
        install.addActionListener(event -> updateNow.run());
        JButton postpone = UiKit.secondaryButton("Más tarde");
        postpone.addActionListener(event -> later.run());
        showCard("ACTUALIZACIÓN", "Nueva versión disponible", body, actions(postpone, install));
    }

    public void showChecking() {
        JPanel body = verticalBody();
        body.add(UiKit.muted("Consultando el canal público de RingLog…"));
        showCard("ACTUALIZACIÓN", "Buscando actualizaciones", body, new JPanel());
    }

    public void showDownloading(long totalBytes) {
        JPanel body = verticalBody();
        body.add(UiKit.muted("Descargando y verificando el instalador…"));
        body.add(Box.createVerticalStrut(12));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("0 %");
        progressBar.getAccessibleContext().setAccessibleName("Progreso de actualización");
        body.add(progressBar);
        body.add(Box.createVerticalStrut(8));
        body.add(UiKit.muted(formatBytes(totalBytes)));
        showCard("ACTUALIZACIÓN", "Preparando la nueva versión", body, new JPanel());
    }

    public void updateProgress(DownloadProgress progress) {
        if (progressBar == null) {
            return;
        }
        int percent = (int) Math.min(
                100, Math.round(progress.downloadedBytes() * 100d / progress.totalBytes())
        );
        progressBar.setValue(percent);
        progressBar.setString(percent + " %");
    }

    public void showInformation(String title, String message, String closeText) {
        JPanel body = verticalBody();
        body.add(UiKit.muted(message));
        JButton close = UiKit.primaryButton(closeText);
        close.addActionListener(event -> hideOverlay());
        showCard("ACTUALIZACIÓN", title, body, actions(close));
    }

    public void hideOverlay() {
        setVisible(false);
        progressBar = null;
    }

    private void showCard(String eyebrow, String title, JPanel body, JPanel actions) {
        card.removeAll();
        JPanel header = verticalBody();
        header.add(UiKit.eyebrow(eyebrow));
        header.add(Box.createVerticalStrut(6));
        JLabel heading = UiKit.title(title);
        heading.setFont(heading.getFont().deriveFont(24f));
        header.add(heading);
        card.add(header, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        actions.setOpaque(false);
        card.add(actions, BorderLayout.SOUTH);
        setVisible(true);
        revalidate();
        repaint();
    }

    private static JPanel verticalBody() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel actions(JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        panel.setOpaque(false);
        for (JButton button : buttons) {
            panel.add(button);
        }
        return panel;
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setComposite(AlphaComposite.SrcOver.derive(0.72f));
            copy.setColor(Color.BLACK);
            copy.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }
}

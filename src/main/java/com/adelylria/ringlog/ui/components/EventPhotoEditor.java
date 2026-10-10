package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import java.util.function.Predicate;
import javax.swing.filechooser.FileNameExtensionFilter;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Form-local selections only: no files or database rows are created until the record is saved. */
public final class EventPhotoEditor extends JPanel {
    private final JPanel preview = new JPanel(new BorderLayout());
    private final JLabel pendingLabel = UiKit.muted("");
    private final JButton discard = UiKit.secondaryButton("Deshacer");
    private final List<Path> pending = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final Predicate<JOptionPane> confirmation;
    private List<String> existing = List.of();
    private EventPhotoGallery gallery;
    private boolean actionsEnabled = true;

    public EventPhotoEditor() {
        this(null);
    }

    /** Confirmation is injectable so draft behaviour can be tested without a native dialog. */
    public EventPhotoEditor(Predicate<JOptionPane> confirmation) {
        super(new BorderLayout(0, 8));
        this.confirmation = confirmation == null ? this::showConfirmation : confirmation;
        setName("eventPhotoEditor");
        setOpaque(false);
        setPreferredSize(new Dimension(300, 260));
        setMinimumSize(new Dimension(240, 210));
        add(UiKit.muted("Fotos de este registro"), BorderLayout.NORTH);
        preview.setOpaque(false);
        add(preview, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.setOpaque(false);
        pendingLabel.setName("pendingPhotoStatus");
        pendingLabel.setFont(pendingLabel.getFont().deriveFont(12f));
        discard.setName("discardPendingPhotos");
        discard.setFont(discard.getFont().deriveFont(12f));
        discard.setPreferredSize(new Dimension(65, 24));
        discard.setMargin(new Insets(0, 0, 0, 0));
        discard.setBorderPainted(false);
        discard.setContentAreaFilled(false);
        discard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        discard.setToolTipText("Deshacer todos los cambios de fotos sin guardar");
        discard.addActionListener(event -> discardPending());
        footer.add(pendingLabel, BorderLayout.CENTER);
        footer.add(discard, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        reset(List.of());
    }

    public List<Path> pendingPhotos() { return List.copyOf(pending); }
    public List<String> removedPhotos() { return List.copyOf(removed); }

    public void reset(List<String> savedPhotos) {
        existing = List.copyOf(savedPhotos);
        pending.clear();
        removed.clear();
        render(false);
    }

    public void discardPending() {
        pending.clear();
        removed.clear();
        render(false);
    }

    private void removeSelected() {
        if (!actionsEnabled) return;
        List<String> saved = visibleSaved();
        int index = gallery.selectedIndex();
        if (saved.isEmpty() && pending.isEmpty()) return;
        boolean alreadySaved = index < saved.size();
        String message = "¿Eliminar la foto " + (index + 1) + " de este registro?\n\n"
                + (alreadySaved
                        ? "Se eliminará al pulsar «Guardar cambios».\nPuedes deshacerlo o cancelar la edición antes de guardar."
                        : "Se quitará de las fotos nuevas pendientes de guardar.")
                + "\nEl archivo original que seleccionaste no se modifica.";
        JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION, null, new Object[]{"Cancelar", "Eliminar foto"}, "Cancelar");
        if (!confirmation.test(pane) || !actionsEnabled) return;
        if (alreadySaved) removed.add(saved.get(index));
        else pending.remove(index - saved.size());
        render(false);
        gallery.showPhoto(index);
    }

    private boolean showConfirmation(JOptionPane pane) {
        var dialog = pane.createDialog(this, "Eliminar foto");
        try { dialog.setVisible(true); }
        finally { dialog.dispose(); }
        return "Eliminar foto".equals(pane.getValue());
    }

    private List<String> visibleSaved() {
        List<String> saved = new ArrayList<>(existing);
        removed.forEach(saved::remove);
        return saved;
    }

    public void addSelection(List<Path> files) {
        if (!actionsEnabled) return;
        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            if (!pending.contains(normalized)) pending.add(normalized);
        }
        render(true);
    }

    public void setMutationActionsEnabled(boolean enabled) {
        actionsEnabled = enabled;
        if (gallery != null) gallery.setMutationActionsEnabled(enabled);
        discard.setEnabled(enabled);
    }

    private void choosePhotos() {
        if (!actionsEnabled) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Añadir fotos al registro");
        chooser.setFileFilter(new FileNameExtensionFilter("Fotografías JPG y PNG", "jpg", "jpeg", "png"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addSelection(Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList());
        }
    }

    private void render(boolean showLast) {
        if (gallery != null) gallery.dispose();
        List<String> photos = visibleSaved();
        photos.addAll(pending.stream().map(Path::toString).toList());
        gallery = new EventPhotoGallery(photos, this::choosePhotos);
        gallery.setRemoveAction(this::removeSelected);
        gallery.setMutationActionsEnabled(actionsEnabled);
        if (showLast) gallery.showLastPhoto();
        preview.removeAll();
        preview.add(gallery);
        boolean changed = !pending.isEmpty() || !removed.isEmpty();
        String summary = pending.isEmpty() ? "" : pending.size() + (pending.size() == 1 ? " nueva" : " nuevas");
        if (!removed.isEmpty()) summary += (summary.isEmpty() ? "" : ", ") + removed.size() + " por borrar";
        pendingLabel.setText(changed ? summary + " · Sin guardar" : "JPG o PNG");
        pendingLabel.setToolTipText(pendingLabel.getText());
        discard.setVisible(changed);
        discard.setEnabled(actionsEnabled);
        revalidate();
        repaint();
    }
}

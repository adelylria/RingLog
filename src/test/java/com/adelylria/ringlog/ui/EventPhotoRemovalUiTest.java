package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import com.adelylria.ringlog.ui.components.EventPhotoEditor;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;

public final class EventPhotoRemovalUiTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean accept = new AtomicBoolean();
            AtomicInteger confirmations = new AtomicInteger();
            EventPhotoEditor editor = new EventPhotoEditor(pane -> {
                confirmations.incrementAndGet();
                require(pane.getMessageType() == JOptionPane.WARNING_MESSAGE, "Destructive action must be explicit");
                require("Cancelar".equals(pane.getInitialValue()), "Default action must be safe");
                if (confirmations.get() <= 2) require(pane.getMessage().toString().contains("Guardar cambios"), "Explain deferred deletion");
                return accept.get();
            });
            String first = Path.of("fixture-one.png").toAbsolutePath().toString();
            String second = Path.of("fixture-two.png").toAbsolutePath().toString();
            editor.reset(List.of(first, second));
            find(editor, "eventPhotoGallery", EventPhotoGallery.class).showPhoto(1);
            find(editor, "removeEventPhoto", JButton.class).doClick();
            require(confirmations.get() == 1 && editor.removedPhotos().isEmpty(), "Cancel confirmation must retain the selected photo");
            accept.set(true);
            find(editor, "removeEventPhoto", JButton.class).doClick();
            require(editor.removedPhotos().equals(List.of(second)), "Only the selected photo can be removed");
            require(find(editor, "pendingPhotoStatus", JLabel.class).getText().contains("Sin guardar"), "Removal must be marked unsaved");
            editor.discardPending();
            require(editor.removedPhotos().isEmpty(), "Discard must undo photo removals");
            editor.addSelection(List.of(Path.of("new.png")));
            find(editor, "removeEventPhoto", JButton.class).doClick();
            require(editor.pendingPhotos().isEmpty() && editor.removedPhotos().isEmpty(), "Removing an unsaved photo must not request a persisted deletion");
            editor.setMutationActionsEnabled(false);
            int prompts = confirmations.get();
            find(editor, "removeEventPhoto", JButton.class).doClick();
            require(confirmations.get() == prompts, "Deletion must be disabled while saving/snapshotting");
            editor.setMutationActionsEnabled(true);
            editor.reset(List.of(first));
            find(editor, "removeEventPhoto", JButton.class).doClick();
            require(!find(editor, "removeEventPhoto", JButton.class).isVisible(), "No trash action when no photos remain");
            require(!find(editor, "nextEventPhoto", JButton.class).isVisible(), "No next arrow for an empty/single gallery");
            editor.reset(List.of());
            require(editor.removedPhotos().isEmpty(), "Removals must not leak to a different record");
            find(editor, "eventPhotoGallery", EventPhotoGallery.class).dispose();
        });
        System.out.println("EventPhotoRemovalUiTest: PASS");
    }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) return type.cast(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            T found = find(child, name, type); if (found != null) return found;
        }
        return null;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

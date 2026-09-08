package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;

import javax.swing.AbstractButton;
import javax.swing.JLabel;

import com.adelylria.ringlog.ui.update.UpdateOverlay;
import com.adelylria.ringlog.update.SemanticVersion;

public final class UpdateOverlayTest {

    private UpdateOverlayTest() {
    }

    public static void updatePromptLivesInsideTheThemedGlassPane() {
        UpdateOverlay overlay = new UpdateOverlay();
        overlay.showAvailable(
                new SemanticVersion(1, 0, 0),
                new SemanticVersion(1, 0, 1),
                () -> { },
                () -> { }
        );
        require(overlay.isVisible(), "Update overlay must become visible in the app window");
        require(hasText(overlay, "Nueva versión disponible"),
                "The comparison title must be clear");
        require(hasButton(overlay, "Actualizar ahora"), "Primary update action is missing");
        require(hasButton(overlay, "Más tarde"), "Postpone action is missing");
    }

    private static boolean hasText(Component component, String text) {
        if (component instanceof JLabel label && text.equals(label.getText())) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasButton(Component component, String text) {
        if (component instanceof AbstractButton button && text.equals(button.getText())) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasButton(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        updatePromptLivesInsideTheThemedGlassPane();
        System.out.println("UpdateOverlayTest: PASS");
    }
}

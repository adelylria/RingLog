package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.ui.components.Sidebar;

public final class MainFrameNavigationTest {

    private MainFrameNavigationTest() {
    }

    public static void diaryIsTheInitialScreen() {
        require(
                MainFrame.HOME.equals(MainFrame.initialScreen()),
                "RingLog should open in Diario"
        );
    }

    public static void detailHasItsOwnRoute() {
        require(
                "CAPTURE_DETAIL".equals(MainFrame.CAPTURE_DETAIL),
                "Capture detail should have a dedicated route"
        );
    }

    public static void captureMethodsHaveTheirOwnCatalogRoute() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> destination = new AtomicReference<>();
            Sidebar sidebar = new Sidebar(destination::set);
            JButton methods = button(sidebar, "Métodos de captura");
            require(methods != null, "Capture methods need a separate catalog action like places");
            methods.doClick();
            require(MainFrame.CAPTURE_METHODS.equals(destination.get()),
                    "The catalog action must navigate to capture methods");
        });
    }

    private static JButton button(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) return button;
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                JButton found = button(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        diaryIsTheInitialScreen();
        detailHasItsOwnRoute();
        captureMethodsHaveTheirOwnCatalogRoute();
        System.out.println("MainFrameNavigationTest: PASS");
    }
}

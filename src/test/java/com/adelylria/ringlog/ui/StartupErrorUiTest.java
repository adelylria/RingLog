package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;

import javax.swing.JLabel;

import com.adelylria.ringlog.database.FutureSchemaVersionException;

/** Ensures startup failures stay integrated, useful, and privacy-safe. */
public final class StartupErrorUiTest {

    private StartupErrorUiTest() {
    }

    public static void futureSchemaHasDedicatedIntegratedExplanation() {
        StartupErrorPanel panel = new StartupErrorPanel(
                new FutureSchemaVersionException(7, 4),
                Path.of("C:/safe/RingLog/logs/ringlog.log")
        );
        String text = visibleText(panel);

        require(text.contains("versión más reciente") && text.contains("schema v7"),
                "A future schema must explain that a newer RingLog version is required");
        require(text.contains("ringlog.log"),
                "The integrated error screen must point to technical diagnostics");
    }

    public static void visibleDetailsNeverExposeRawSensitiveFailureValues() {
        StartupErrorPanel panel = new StartupErrorPanel(
                new IllegalStateException("token=very-secret; observations=private bird note"),
                null
        );
        String text = visibleText(panel);

        require(!text.contains("very-secret") && !text.contains("private bird note"),
                "The startup panel must not expose arbitrary exception payloads");
        require(text.contains("REDACTED"),
                "The user should still see that sensitive details were intentionally hidden");
    }

    private static String visibleText(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && label.getText() != null) {
                text.append(label.getText()).append(' ');
            }
            if (component instanceof Container child) {
                text.append(visibleText(child));
            }
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        futureSchemaHasDedicatedIntegratedExplanation();
        visibleDetailsNeverExposeRawSensitiveFailureValues();
        System.out.println("StartupErrorUiTest: PASS");
    }
}

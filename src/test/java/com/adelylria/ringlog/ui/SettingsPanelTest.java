package com.adelylria.ringlog.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.ui.panels.SettingsPanel;
import com.adelylria.ringlog.ui.theme.ThemeManager;
import com.adelylria.ringlog.diagnostics.BuildInfo;

public final class SettingsPanelTest {

    private SettingsPanelTest() {
    }

    public static void themeToggleInvertsCurrentValue() {
        if (!SettingsPanel.toggleValue(false) || SettingsPanel.toggleValue(true)) {
            throw new AssertionError("Theme toggle should invert the current value");
        }
    }

    public static void themeChoiceSurvivesApplicationReinitialization()
            throws Exception {
        String property = "ringlog.preferences.path";
        String previousPath = System.getProperty(property);
        Path temporaryDirectory = Files.createTempDirectory("ringlog-theme-test");
        Path preferences = temporaryDirectory.resolve("preferences.properties");
        System.setProperty(property, preferences.toString());

        try {
            ThemeManager.setupLightTheme();
            SettingsPanel settings = new SettingsPanel();
            JCheckBox darkMode = findCheckBox(settings);
            SwingUtilities.invokeAndWait(darkMode::doClick);

            ThemeManager.initialize();
            SettingsPanel restoredSettings = new SettingsPanel();
            if (!findCheckBox(restoredSettings).isSelected()) {
                throw new AssertionError(
                        "The selected theme should survive an application restart"
                );
            }
        } finally {
            if (previousPath == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previousPath);
            }
            ThemeManager.setupLightTheme();
            Files.deleteIfExists(preferences);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    public static void settingsOffersLegacyHistoryImport() {
        SettingsPanel settings = new SettingsPanel();
        JButton importButton = findButton(settings, "Importar historial");
        if (!importButton.isEnabled()) {
            throw new AssertionError("The history import action should be available");
        }
    }

    public static void settingsOffersBackupExport() {
        SettingsPanel settings = new SettingsPanel();
        JButton exportButton = findButton(settings, "Exportar copia");
        if (!exportButton.isEnabled()) {
            throw new AssertionError("The backup export action should be available");
        }
    }

    public static void settingsShowsAboutAndDiagnosticsCard() {
        SettingsPanel settings = new SettingsPanel();
        String text = visibleText(settings);
        if (!text.contains("Acerca de RingLog")
                || !text.contains("RingLog " + BuildInfo.applicationVersion())
                || !text.contains("Java")
                || !text.contains("Schema")) {
            throw new AssertionError(
                    "Settings should explain the app version, Java runtime, and database schema"
            );
        }
        findButton(settings, "Abrir carpeta de logs");
    }

    private static String visibleText(java.awt.Container root) {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel label && label.getText() != null) {
                text.append(label.getText()).append(' ');
            }
            if (component instanceof java.awt.Container child) {
                text.append(visibleText(child));
            }
        }
        return text.toString();
    }

    private static JCheckBox findCheckBox(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JCheckBox checkBox) {
                return checkBox;
            }
            if (component instanceof java.awt.Container child) {
                try {
                    return findCheckBox(child);
                } catch (IllegalStateException ignored) {
                    // Continue with the remaining branches.
                }
            }
        }
        throw new IllegalStateException("Theme checkbox not found");
    }

    private static JButton findButton(java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container child) {
                try {
                    return findButton(child, text);
                } catch (IllegalStateException ignored) {
                    // Continue with the remaining branches.
                }
            }
        }
        throw new IllegalStateException("Button not found: " + text);
    }

    public static void main(String[] args) throws Exception {
        themeToggleInvertsCurrentValue();
        themeChoiceSurvivesApplicationReinitialization();
        settingsOffersLegacyHistoryImport();
        settingsOffersBackupExport();
        settingsShowsAboutAndDiagnosticsCard();
        System.out.println("SettingsPanelTest: PASS");
    }
}

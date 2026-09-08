package com.adelylria.ringlog.ui.theme;

import java.awt.Font;
import java.awt.Insets;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.adelylria.ringlog.preferences.ApplicationPreferences;

public final class ThemeManager {

    private static boolean darkMode;

    private ThemeManager() {
    }

    public static void initialize() {
        if (ThemePreferences.loadDarkMode()) {
            setupDarkTheme();
        } else {
            setupLightTheme();
        }
    }

    public static void initialize(ApplicationPreferences preferences) {
        ThemePreferences.configure(preferences);
        initialize();
    }

    public static void setupLightTheme() {
        FlatLightLaf.setup();
        configureThemeColors(false);
        configureGlobalStyles();
        darkMode = false;
    }

    public static void setupDarkTheme() {
        FlatDarkLaf.setup();
        configureThemeColors(true);
        configureGlobalStyles();
        darkMode = true;
    }

    public static boolean isDarkMode() {
        return darkMode;
    }

    private static void configureGlobalStyles() {
        UIManager.put("defaultFont", new FontUIResource("Segoe UI", Font.PLAIN, 14));
        UIManager.put("Button.arc", 18);
        UIManager.put("Component.arc", 18);
        UIManager.put("TextComponent.arc", 16);
        UIManager.put("Button.height", 42);
        UIManager.put("Button.minimumWidth", 96);

        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", UiKit.accentColor());
        UIManager.put("Component.innerFocusColor", UiKit.accentSoftColor());

        UIManager.put(
                "Button.margin",
                new Insets(10, 16, 10, 16)
        );
        UIManager.put("TextComponent.margin", new Insets(10, 12, 10, 12));

        UIManager.put("Table.rowHeight", 36);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);

        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);

        UIManager.put("TabbedPane.tabHeight", 36);
        UIManager.put("TabbedPane.showTabSeparators", true);
    }

    private static void configureThemeColors(boolean darkMode) {
        ColorUIResource background = darkMode
                ? color(25, 28, 26)
                : color(247, 245, 239);
        ColorUIResource surface = darkMode
                ? color(35, 39, 36)
                : color(255, 254, 250);
        ColorUIResource surfaceRaised = darkMode
                ? color(43, 48, 44)
                : color(241, 244, 237);
        ColorUIResource border = darkMode
                ? color(62, 68, 63)
                : color(224, 222, 213);
        ColorUIResource muted = darkMode
                ? color(166, 174, 168)
                : color(105, 112, 106);
        ColorUIResource text = darkMode
                ? color(238, 240, 236)
                : color(39, 45, 41);
        ColorUIResource accent = darkMode
                ? color(141, 190, 155)
                : color(55, 105, 75);
        ColorUIResource accentSoft = darkMode
                ? color(47, 69, 55)
                : color(226, 237, 227);
        ColorUIResource accentForeground = darkMode
                ? color(22, 31, 25)
                : color(255, 255, 255);
        ColorUIResource sidebar = darkMode
                ? color(30, 34, 31)
                : color(239, 239, 231);

        UIManager.put("Panel.background", background);
        UIManager.put("ScrollPane.background", background);
        UIManager.put("Viewport.background", background);
        UIManager.put("TextField.background", surface);
        UIManager.put("TextArea.background", surface);
        UIManager.put("ComboBox.background", surface);
        UIManager.put("ComboBox.foreground", text);
        UIManager.put("ComboBox.popupBackground", surface);
        UIManager.put("ComboBox.selectionBackground", accentSoft);
        UIManager.put("ComboBox.selectionForeground", text);
        UIManager.put("List.background", surface);
        UIManager.put("List.foreground", text);
        UIManager.put("List.selectionBackground", accentSoft);
        UIManager.put("List.selectionForeground", text);
        UIManager.put("PopupMenu.background", surface);
        UIManager.put("PopupMenu.foreground", text);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Table.gridColor", border);
        UIManager.put("RingLog.background", background);
        UIManager.put("RingLog.surface", surface);
        UIManager.put("RingLog.surfaceRaised", surfaceRaised);
        UIManager.put("RingLog.border", border);
        UIManager.put("RingLog.muted", muted);
        UIManager.put("RingLog.text", text);
        UIManager.put("RingLog.accent", accent);
        UIManager.put("RingLog.accentSoft", accentSoft);
        UIManager.put("RingLog.accentForeground", accentForeground);
        UIManager.put("RingLog.sidebar", sidebar);
    }

    private static ColorUIResource color(int red, int green, int blue) {
        return new ColorUIResource(red, green, blue);
    }

    public static void updateApplicationTheme(
            JFrame frame,
            boolean darkMode
    ) {
        if (darkMode) {
            setupDarkTheme();
        } else {
            setupLightTheme();
        }

        ThemePreferences.saveDarkMode(darkMode);

        if (frame == null) {
            return;
        }

        SwingUtilities.updateComponentTreeUI(frame);
        UiKit.refreshSemanticColors(frame);

        frame.invalidate();
        frame.validate();
        frame.repaint();
    }
}

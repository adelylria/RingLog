package com.adelylria.ringlog.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JPanel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboPopup;

import com.adelylria.ringlog.ui.theme.RoundedBorder;
import com.adelylria.ringlog.ui.theme.ThemeManager;
import com.adelylria.ringlog.ui.theme.UiKit;

public final class ThemeManagerTest {

    private ThemeManagerTest() {
    }

    public static void lightThemePublishesSemanticPalette() {
        ThemeManager.setupLightTheme();

        require(UIManager.getColor("RingLog.background") != null,
                "Theme background token is missing");
        require(UIManager.getColor("RingLog.surface") != null,
                "Theme surface token is missing");
        require(UIManager.getColor("RingLog.accent") != null,
                "Theme accent token is missing");
        require(UIManager.getInt("Button.arc") >= 14,
                "Buttons should use a modern rounded radius");
    }

    public static void applicationStartsWithTheLightThemeByDefault()
            throws Exception {
        String property = "ringlog.preferences.path";
        String previousPath = System.getProperty(property);
        Path temporaryDirectory = Files.createTempDirectory("ringlog-theme-default");
        Path preferences = temporaryDirectory.resolve("preferences.properties");
        System.setProperty(property, preferences.toString());
        try {
            ThemeManager.initialize();
            require(
                    UIManager.getLookAndFeel().getName().toLowerCase().contains("light"),
                    "The journal should use the light theme without a saved choice"
            );
        } finally {
            if (previousPath == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previousPath);
            }
            Files.deleteIfExists(preferences);
            Files.deleteIfExists(temporaryDirectory);
        }
    }

    public static void sharedSurfacesUseRoundedBorders() {
        require(UiKit.cardBorder() instanceof RoundedBorder,
                "Cards should use the rounded border implementation");
        require(UiKit.sectionPanel().getClass().getSimpleName().equals("SurfacePanel"),
                "Sections should use the rounded surface panel");
    }

    public static void roundedBordersPaintTheirBottomEdgeInsideTheCard() {
        int width = 140;
        int height = 70;
        BufferedImage image = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            RoundedBorder border = new RoundedBorder(
                    Color.RED,
                    18,
                    1,
                    new Insets(0, 0, 0, 0)
            );
            border.paintBorder(new JPanel(), graphics, 0, 0, width, height);
        } finally {
            graphics.dispose();
        }

        int bottomCenterAlpha = image.getRGB(width / 2, height - 1) >>> 24;
        require(bottomCenterAlpha >= 240,
                "Rounded card borders must not be clipped at the bottom edge");
    }

    public static void pageBackgroundFollowsThemeChanges() {
        ThemeManager.setupLightTheme();
        JPanel page = UiKit.pagePanel();

        ThemeManager.setupDarkTheme();
        UiKit.refreshSemanticColors(page);

        require(page.isOpaque(),
                "Page surfaces should paint their own semantic background");
        require(page.getBackground().equals(UiKit.backgroundColor()),
                "Existing pages should adopt the new theme background");
        ThemeManager.setupLightTheme();
    }

    public static void genericPanelsFollowRuntimeThemeChanges() {
        ThemeManager.setupLightTheme();
        JPanel panel = new JPanel();

        ThemeManager.setupDarkTheme();
        SwingUtilities.updateComponentTreeUI(panel);

        require(panel.getBackground().equals(UiKit.backgroundColor()),
                "Generic panels must not retain the previous theme background");
        ThemeManager.setupLightTheme();
    }

    public static void darkPopupPaletteStaysReadable() {
        ThemeManager.setupDarkTheme();

        require(UIManager.getColor("List.background").equals(UiKit.surfaceColor()),
                "Combo popup lists should use the dark surface color");
        require(UIManager.getColor("List.foreground").equals(UiKit.textColor()),
                "Combo popup list text should use the semantic text color");
        require(UIManager.getColor("PopupMenu.background").equals(UiKit.surfaceColor()),
                "Popup menus should not keep a light background in dark mode");

        JComboBox<String> combo = new JComboBox<>(
                new String[] {"Anillamiento", "Control"}
        );
        BasicComboPopup popup = (BasicComboPopup) combo
                .getAccessibleContext()
                .getAccessibleChild(0);
        JList<?> popupList = popup.getList();
        require(popupList.getBackground().equals(UiKit.surfaceColor()),
                "The real combo popup should use the dark surface color");
        require(popupList.getSelectionBackground().equals(UiKit.accentSoftColor()),
                "Combo popup selection should use the semantic accent color");
        ThemeManager.setupLightTheme();
    }

    public static void existingFormFieldsFollowDarkTheme() {
        ThemeManager.setupLightTheme();
        JTextField field = new JTextField();
        JTextArea area = new JTextArea();
        JComboBox<String> combo = new JComboBox<>(new String[] {"Captura"});
        JPanel form = new JPanel();
        form.add(field);
        form.add(area);
        form.add(combo);

        ThemeManager.setupDarkTheme();
        UiKit.refreshSemanticColors(form);

        require(field.getBackground().equals(UiKit.surfaceColor()),
                "Existing text fields should adopt the dark surface color");
        require(area.getBackground().equals(UiKit.surfaceColor()),
                "Existing notes areas should adopt the dark surface color");
        require(combo.getBackground().equals(UiKit.surfaceColor()),
                "Existing combo boxes should adopt the dark surface color");
        require(field.getForeground().equals(UiKit.textColor()),
                "Form field text should remain readable in dark mode");
        ThemeManager.setupLightTheme();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        applicationStartsWithTheLightThemeByDefault();
        lightThemePublishesSemanticPalette();
        sharedSurfacesUseRoundedBorders();
        roundedBordersPaintTheirBottomEdgeInsideTheCard();
        pageBackgroundFollowsThemeChanges();
        genericPanelsFollowRuntimeThemeChanges();
        darkPopupPaletteStaysReadable();
        existingFormFieldsFollowDarkTheme();
        System.out.println("ThemeManagerTest: PASS");
    }
}

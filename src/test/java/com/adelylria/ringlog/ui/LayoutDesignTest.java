package com.adelylria.ringlog.ui;

import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.Sidebar;
import com.adelylria.ringlog.ui.components.StatCard;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.HomePanel;
import com.adelylria.ringlog.ui.panels.ReportsPanel;
import com.adelylria.ringlog.ui.panels.PlacePanel;
import com.adelylria.ringlog.ui.panels.SpeciesPanel;
import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.theme.ThemeManager;

import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JPanel;

public final class LayoutDesignTest {

    private LayoutDesignTest() {
    }

    public static void homeShowsItsHeader() {
        HomePanel panel = new HomePanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );

        require(count(panel, PageHeader.class) == 1,
                "Home should render exactly one visible page header");
    }

    public static void journalScreensUseOneScrollContainer() {
        HomePanel home = new HomePanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );
        CaptureListPanel captures = new CaptureListPanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );

        require(count(home, JScrollPane.class) == 1,
                "Home should not contain nested scroll panes");
        require(count(captures, JScrollPane.class) == 1,
                "Captures should not contain nested scroll panes");
    }

    public static void sidebarUsesRenderableIcons() {
        Sidebar sidebar = new Sidebar(ignored -> { });
        List<JButton> buttons = findButtons(sidebar);

        require(buttons.size() == 8,
                "Sidebar should expose all eight navigation actions, including reviews");
        require(buttons.stream().allMatch(button -> button.getIcon() != null),
                "Navigation icons should be painted icons, not missing font glyphs");
    }

    public static void diaryCardsKeepTheirNaturalHeight() {
        JPanel card = UiKit.sectionPanel();
        card.add(UiKit.valueLabel("Carbonero común"));

        require(card.getMaximumSize().height == card.getPreferredSize().height,
                "Diary cards should not stretch vertically to fill the viewport");
    }

    public static void catalogsUseACompactTwoColumnLayout() {
        CatalogRepository unusedRepository = new CatalogRepository("unused.db");
        SpeciesPanel species = new SpeciesPanel(unusedRepository);
        PlacePanel places = new PlacePanel(unusedRepository);

        requireCatalogLayout(species, "Species");
        requireCatalogLayout(places, "Places");
    }

    public static void reportStatsKeepTheirNaturalHeight() {
        ReportsPanel reports = new ReportsPanel(
                new BirdEventRepository("unused.db"),
                new CatalogRepository("unused.db")
        );
        JPanel content = (JPanel) findScrollPane(reports).getViewport().getView();
        content.removeAll();

        JPanel stats = UiKit.statGrid();
        stats.add(new StatCard("REGISTROS", "5", "eventos guardados"));
        stats.add(new StatCard("ESPECIES", "5", "especies distintas"));
        stats.add(new StatCard("ÚLTIMO DÍA", "22 ago 2026", "última entrada"));
        content.add(stats);
        content.setSize(900, 500);
        content.doLayout();

        require(stats.getHeight() <= stats.getPreferredSize().height + 2,
                "Report statistics should not stretch to fill the viewport");
    }

    public static void primaryCaptureActionUsesAPaintedIcon() {
        CaptureListPanel captures = new CaptureListPanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );
        JButton action = findButtons(captures).stream()
                .filter(button -> button.getText().contains("Nuevo registro"))
                .findFirst()
                .orElseThrow();

        require(action.getIcon() != null,
                "The new capture action should not depend on a font glyph");
    }

    public static void scrollFramesStayInvisibleAfterThemeChanges() {
        ThemeManager.setupLightTheme();
        CaptureListPanel captures = new CaptureListPanel(
                new BirdEventRepository("unused.db"),
                ignored -> { },
                () -> { }
        );
        ReportsPanel reports = new ReportsPanel(
                new BirdEventRepository("unused.db"),
                new CatalogRepository("unused.db")
        );

        ThemeManager.setupDarkTheme();
        findScrollPane(captures).updateUI();
        findScrollPane(reports).updateUI();

        require(hasZeroBorderInsets(findScrollPane(captures)),
                "Capture scroll should stay visually borderless");
        require(hasZeroBorderInsets(findScrollPane(reports)),
                "Report scroll should stay visually borderless");
        ThemeManager.setupLightTheme();
    }

    public static void detailKeepsSemanticBackgroundAndLeftAlignment() {
        ThemeManager.setupLightTheme();
        CaptureDetailPanel detail = new CaptureDetailPanel(
                new BirdEventRepository("unused.db"),
                () -> { }
        );

        ThemeManager.setupDarkTheme();
        javax.swing.SwingUtilities.updateComponentTreeUI(detail);
        UiKit.refreshSemanticColors(detail);

        require(detail.getBackground().equals(UiKit.backgroundColor()),
                "Detail view must not retain the light background in dark mode");

        JPanel verticalContent = (JPanel) findScrollPane(detail)
                .getViewport()
                .getView();
        JPanel section = new JPanel();
        verticalContent.add(section);
        require(section.getAlignmentX() == Component.LEFT_ALIGNMENT,
                "Detail sections should start at the left edge");
        ThemeManager.setupLightTheme();
    }

    public static void captureCardsUseCompactAlignedActions() {
        BirdEventCard card = new BirdEventCard(
                new BirdEventTimelineItem(
                        1,
                        2,
                        "V25943",
                        EventType.CONTROL,
                        "2026-08-22",
                        "08:55",
                        "TURDUS PHILOMELOS",
                        "ELS RAFALS",
                        "Observación breve",
                        "GOOD",
                        null
                ),
                ignored -> { }
        );
        JButton action = findButtons(card).get(0);
        JLabel place = findLabels(card).stream()
                .filter(label -> label.getText().contains("ELS RAFALS"))
                .findFirst()
                .orElseThrow();

        require(action.getPreferredSize().height <= 36,
                "Capture detail actions should stay visually compact");
        require(place.getAlignmentX() == Component.LEFT_ALIGNMENT,
                "Capture place metadata should align with the species title");
        require(!place.getText().contains("⌖"),
                "Capture metadata should not depend on a missing location glyph");
    }

    private static List<JLabel> findLabels(Container root) {
        List<JLabel> labels = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label) {
                labels.add(label);
            }
            if (component instanceof Container child) {
                labels.addAll(findLabels(child));
            }
        }
        return labels;
    }

    private static boolean hasZeroBorderInsets(JScrollPane scroll) {
        Insets insets = scroll.getBorder() == null
                ? new Insets(0, 0, 0, 0)
                : scroll.getBorder().getBorderInsets(scroll);
        return insets.top == 0
                && insets.left == 0
                && insets.bottom == 0
                && insets.right == 0;
    }

    private static void requireCatalogLayout(Container catalog, String name) {
        JScrollPane scroll = findScrollPane(catalog);
        JPanel grid = (JPanel) scroll.getViewport().getView();
        grid.removeAll();

        JPanel first = UiKit.sectionPanel();
        first.add(UiKit.valueLabel("Primera tarjeta"));
        JPanel second = UiKit.sectionPanel();
        second.add(UiKit.valueLabel("Segunda tarjeta"));
        grid.add(first);
        grid.add(second);
        grid.setSize(900, 500);
        grid.doLayout();

        require(first.getX() < second.getX(),
                name + " cards should form two columns");
        require(first.getHeight() <= first.getPreferredSize().height + 2,
                name + " cards should keep their natural height");
    }

    private static JScrollPane findScrollPane(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JScrollPane scrollPane) {
                return scrollPane;
            }
            if (component instanceof Container child) {
                JScrollPane result = findScrollPane(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static List<JButton> findButtons(Container root) {
        List<JButton> buttons = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button) {
                buttons.add(button);
            }
            if (component instanceof Container child) {
                buttons.addAll(findButtons(child));
            }
        }
        return buttons;
    }

    private static int count(Container root, Class<?> type) {
        int result = 0;
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result++;
            }
            if (component instanceof Container child) {
                result += count(child, type);
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        captureCardsUseCompactAlignedActions();
        scrollFramesStayInvisibleAfterThemeChanges();
        detailKeepsSemanticBackgroundAndLeftAlignment();
        primaryCaptureActionUsesAPaintedIcon();
        reportStatsKeepTheirNaturalHeight();
        homeShowsItsHeader();
        journalScreensUseOneScrollContainer();
        diaryCardsKeepTheirNaturalHeight();
        sidebarUsesRenderableIcons();
        catalogsUseACompactTwoColumnLayout();
        System.out.println("LayoutDesignTest: PASS");
    }
}

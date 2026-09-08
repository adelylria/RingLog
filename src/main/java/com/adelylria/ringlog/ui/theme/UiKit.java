package com.adelylria.ringlog.ui.theme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;

public final class UiKit {

    private static final String FOREGROUND_TOKEN = "RingLog.foregroundToken";
    private static final String BACKGROUND_TOKEN = "RingLog.backgroundToken";

    private static final String[] MONTHS = {
            "ene", "feb", "mar", "abr", "may", "jun",
            "jul", "ago", "sep", "oct", "nov", "dic"
    };

    private UiKit() {
    }

    public static String display(Object value) {
        if (value == null || value.toString().isBlank()) {
            return "—";
        }
        return value.toString();
    }

    public static String date(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return "—";
        }

        try {
            LocalDate parsed = LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return parsed.getDayOfMonth()
                    + " "
                    + MONTHS[parsed.getMonthValue() - 1]
                    + " "
                    + parsed.getYear();
        } catch (DateTimeParseException ignored) {
            return isoDate;
        }
    }

    public static String time(String time) {
        if (time == null || time.isBlank()) {
            return "—";
        }
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    public static JPanel pagePanel() {
        JPanel panel = new PagePanel();
        panel.setBorder(new EmptyBorder(38, 42, 40, 42));
        panel.setBackground(backgroundColor());
        panel.putClientProperty(BACKGROUND_TOKEN, "background");
        panel.setOpaque(true);
        return panel;
    }

    public static JPanel verticalPanel(int gap) {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        if (gap > 0) {
            panel.setBorder(new EmptyBorder(0, 0, gap, 0));
        }
        return panel;
    }

    public static JPanel verticalScrollPanel() {
        return new VerticalScrollPanel();
    }

    public static JPanel sectionPanel() {
        return new SurfacePanel(new BorderLayout(0, 10));
    }

    public static JPanel statGrid() {
        return naturalGrid(3, 14);
    }

    public static JPanel naturalGrid(int columns, int gap) {
        JPanel panel = new NaturalHeightPanel(new GridLayout(1, columns, gap, 0));
        panel.setOpaque(false);
        return panel;
    }

    public static JScrollPane scrollPane(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.setViewportBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    public static JPanel flowLeft() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);
        return panel;
    }

    public static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 30f));
        label.setForeground(textColor());
        label.putClientProperty(FOREGROUND_TOKEN, "text");
        return label;
    }

    public static JLabel subtitle(String text) {
        JLabel label = new JLabel(display(text));
        label.setForeground(mutedColor());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.putClientProperty(FOREGROUND_TOKEN, "muted");
        return label;
    }

    public static JLabel eyebrow(String text) {
        JLabel label = new JLabel(display(text).toUpperCase(Locale.ROOT));
        label.setForeground(accentColor());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.putClientProperty(FOREGROUND_TOKEN, "accent");
        return label;
    }

    public static JLabel valueLabel(String value) {
        JLabel label = new JLabel(display(value));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setForeground(textColor());
        label.putClientProperty(FOREGROUND_TOKEN, "text");
        return label;
    }

    public static JLabel muted(String value) {
        return subtitle(value);
    }

    public static JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setBackground(accentColor());
        button.setForeground(accentForegroundColor());
        button.putClientProperty(BACKGROUND_TOKEN, "accent");
        button.putClientProperty(FOREGROUND_TOKEN, "accentForeground");
        return button;
    }

    public static JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JLabel chip(String text) {
        return new PillLabel(display(text));
    }

    public static String friendlyCode(Object value) {
        String text = display(value);
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "M" -> "Macho";
            case "F" -> "Hembra";
            case "CAPTURE" -> "Captura";
            case "RECAPTURE" -> "Recaptura";
            case "GOOD" -> "Buen estado";
            case "REGULAR" -> "Regular";
            case "POOR" -> "Delicado";
            case "OK" -> "Correcto";
            case "PENDING" -> "Pendiente";
            case "REVIEW" -> "Revisar";
            case "NONE" -> "Ninguno";
            case "LOW" -> "Bajo";
            case "MEDIUM" -> "Medio";
            case "HIGH" -> "Alto";
            case "PARTIAL" -> "Parcial";
            case "NO" -> "No";
            case "LIGHT" -> "Ligero";
            case "MODERATE" -> "Moderado";
            default -> text;
        };
    }

    public static JSeparator separator() {
        return new JSeparator();
    }

    public static Border cardBorder() {
        return new RoundedBorder(
                null,
                18,
                1,
                new Insets(17, 17, 17, 17)
        );
    }

    public static Color mutedColor() {
        return colorToken(
                "RingLog.muted",
                new Color(112, 120, 115)
        );
    }

    public static Color backgroundColor() {
        return colorToken("RingLog.background", new Color(247, 249, 247));
    }

    public static Color surfaceColor() {
        return colorToken("RingLog.surface", Color.WHITE);
    }

    public static Color surfaceRaisedColor() {
        return colorToken("RingLog.surfaceRaised", new Color(238, 245, 240));
    }

    public static Color borderColor() {
        return colorToken("RingLog.border", new Color(218, 225, 220));
    }

    public static Color accentColor() {
        return colorToken("RingLog.accent", new Color(73, 125, 99));
    }

    public static Color accentSoftColor() {
        return colorToken("RingLog.accentSoft", new Color(226, 238, 231));
    }

    public static Color accentForegroundColor() {
        return colorToken("RingLog.accentForeground", Color.WHITE);
    }

    public static Color textColor() {
        return colorToken("RingLog.text", new Color(35, 45, 39));
    }

    public static Color sidebarColor() {
        return colorToken("RingLog.sidebar", new Color(239, 239, 231));
    }

    private static Color colorToken(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    public static void refreshSemanticColors(Component component) {
        if (component instanceof JTextComponent textComponent) {
            textComponent.setBackground(surfaceColor());
            textComponent.setForeground(textColor());
            textComponent.setCaretColor(textColor());
            textComponent.setSelectionColor(accentSoftColor());
            textComponent.setSelectedTextColor(textColor());
        } else if (component instanceof JComboBox<?> comboBox) {
            comboBox.setBackground(surfaceColor());
            comboBox.setForeground(textColor());
        }

        if (component instanceof JComponent swingComponent) {
            Object foreground = swingComponent.getClientProperty(FOREGROUND_TOKEN);
            if (foreground != null) {
                swingComponent.setForeground(switch (foreground.toString()) {
                    case "accent" -> accentColor();
                    case "accentForeground" -> accentForegroundColor();
                    case "muted" -> mutedColor();
                    default -> textColor();
                });
            }

            Object background = swingComponent.getClientProperty(BACKGROUND_TOKEN);
            if (background != null) {
                swingComponent.setBackground(switch (background.toString()) {
                    case "accent" -> accentColor();
                    case "accentSoft" -> accentSoftColor();
                    case "sidebar" -> sidebarColor();
                    default -> backgroundColor();
                });
            }

            if (swingComponent.getComponentPopupMenu() != null) {
                refreshSemanticColors(swingComponent.getComponentPopupMenu());
            }
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                refreshSemanticColors(child);
            }
        }
    }

    public static void constrain(Component component, int width, int height) {
        component.setMinimumSize(new Dimension(width, height));
        component.setPreferredSize(new Dimension(width, height));
    }

    private static final class PagePanel extends JPanel implements Scrollable {

        private PagePanel() {
            super(new BorderLayout(0, 22));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return orientation == SwingConstants.VERTICAL
                    ? Math.max(16, visibleRect.height - 32)
                    : Math.max(16, visibleRect.width - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class NaturalHeightPanel extends JPanel {

        private NaturalHeightPanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        protected void addImpl(Component component, Object constraints, int index) {
            super.addImpl(component, constraints, index);
            Dimension preferred = super.getPreferredSize();
            super.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        }
    }

    private static final class VerticalScrollPanel extends JPanel implements Scrollable {

        private VerticalScrollPanel() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        @Override
        protected void addImpl(Component component, Object constraints, int index) {
            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            super.addImpl(component, constraints, index);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
        ) {
            return orientation == SwingConstants.VERTICAL
                    ? Math.max(16, visibleRect.height - 32)
                    : Math.max(16, visibleRect.width - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}

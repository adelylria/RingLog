package com.adelylria.ringlog.ui.components;

import com.adelylria.ringlog.ui.MainFrame;
import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.application.ApplicationCapabilities;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

public class Sidebar extends JPanel {

    private final Consumer<String> navigationHandler;
    private final ApplicationCapabilities capabilities;
    private final Map<String, JButton> navigationButtons = new LinkedHashMap<>();
    private String activeDestination;

    public Sidebar(Consumer<String> navigationHandler) {
        this(navigationHandler, ApplicationCapabilities.normal());
    }

    public Sidebar(
            Consumer<String> navigationHandler,
            ApplicationCapabilities capabilities
    ) {
        this.navigationHandler = navigationHandler;
        this.capabilities = java.util.Objects.requireNonNull(capabilities, "capabilities");
        initialize();
    }

    private void initialize() {
        setPreferredSize(new Dimension(242, 0));
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(30, 20, 22, 20));
        setBackground(UiKit.sidebarColor());
        setOpaque(true);

        JPanel menu = new JPanel();
        menu.setOpaque(false);
        menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("RingLog");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        menu.add(title);
        menu.add(Box.createVerticalStrut(2));

        JLabel subtitle = UiKit.muted("Diario de campo");
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        menu.add(subtitle);
        menu.add(Box.createVerticalStrut(28));

        addNavigation(menu, "Diario", MainFrame.HOME, NavigationIcon.Kind.HOME);
        if (capabilities.mutateDiary()) {
            addNavigation(menu, "Nuevo registro", MainFrame.NEW_CAPTURE, NavigationIcon.Kind.ADD);
        }
        addNavigation(menu, "Registros", MainFrame.CAPTURES, NavigationIcon.Kind.CAPTURES);

        menu.add(Box.createVerticalStrut(22));
        JLabel explore = UiKit.eyebrow("Explorar");
        explore.setAlignmentX(Component.LEFT_ALIGNMENT);
        menu.add(explore);
        menu.add(Box.createVerticalStrut(8));

        addNavigation(menu, "Especies", MainFrame.SPECIES, NavigationIcon.Kind.SPECIES);
        addNavigation(menu, "Lugares", MainFrame.PLACES, NavigationIcon.Kind.PLACES);
        addNavigation(menu, "Informes", MainFrame.REPORTS, NavigationIcon.Kind.REPORTS);
        addNavigation(menu, "Revisiones", MainFrame.CONFLICTS, NavigationIcon.Kind.REVIEWS);

        add(menu, BorderLayout.NORTH);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        JLabel hint = UiKit.muted(capabilities.readOnly()
                ? "Copia portátil · Solo lectura"
                : "Tus observaciones, a tu ritmo.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(hint);
        footer.add(Box.createVerticalStrut(12));
        addNavigation(footer, "Ajustes", MainFrame.SETTINGS, NavigationIcon.Kind.SETTINGS);
        add(footer, BorderLayout.SOUTH);
    }

    private void addNavigation(
            JPanel parent,
            String text,
            String destination,
            NavigationIcon.Kind icon
    ) {
        JButton button = createButton(text, destination, icon);
        navigationButtons.put(destination, button);
        parent.add(button);
        parent.add(Box.createVerticalStrut(5));
    }

    private JButton createButton(
            String text,
            String destination,
            NavigationIcon.Kind icon
    ) {
        JButton button = new JButton(text);
        button.setIcon(new NavigationIcon(icon));
        button.setIconTextGap(11);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(10, 13, 10, 13));
        button.setBorderPainted(false);
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        button.addActionListener(event -> navigationHandler.accept(destination));
        return button;
    }

    public void setActiveDestination(String destination) {
        activeDestination = destination;
        setBackground(UiKit.sidebarColor());
        navigationButtons.forEach((key, button) -> {
            boolean active = key.equals(destination)
                    || (MainFrame.CAPTURE_DETAIL.equals(destination)
                    && MainFrame.CAPTURES.equals(key));
            boolean callToAction = MainFrame.NEW_CAPTURE.equals(key);
            button.putClientProperty(
                    "JButton.buttonType",
                    active || callToAction ? "roundRect" : null
            );
            button.setFont(button.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
            button.setBackground(callToAction
                    ? (active ? UiKit.accentColor() : UiKit.surfaceRaisedColor())
                    : active ? UiKit.accentSoftColor() : UiKit.sidebarColor());
            button.setForeground(callToAction
                    ? (active ? UiKit.accentForegroundColor() : UiKit.accentColor())
                    : active ? UiKit.accentColor() : UiKit.textColor());
            button.setOpaque(active || callToAction);
            button.setContentAreaFilled(active || callToAction);
        });
        revalidate();
        repaint();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        JButton button = navigationButtons.get(MainFrame.NEW_CAPTURE);
        if (button != null) {
            button.setEnabled(enabled);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (navigationButtons != null && !navigationButtons.isEmpty()) {
            SwingUtilities.invokeLater(() -> setActiveDestination(activeDestination));
        }
    }
}

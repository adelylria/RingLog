package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Point;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.model.view.DashboardStats;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.StatCard;
import com.adelylria.ringlog.ui.components.TimelineView;
import com.adelylria.ringlog.ui.theme.UiKit;

public class HomePanel extends JPanel {

    private final BirdEventRepository eventRepository;
    private final Consumer<Long> onOpenEvent;
    private final Runnable onNewEvent;
    private final boolean canCreateEvent;
    private JButton newEventButton;
    private final JPanel statsPanel;
    private final TimelineView timelineView;
    private final JLabel statusLabel;
    private JScrollPane scroll;
    private Point returnPosition = new Point();
    private boolean loaded;
    private int loadGeneration;

    public HomePanel(
            BirdEventRepository eventRepository,
            Consumer<Long> onOpenEvent,
            Runnable onNewEvent
    ) {
        this(eventRepository, onOpenEvent, onNewEvent, true);
    }

    public HomePanel(
            BirdEventRepository eventRepository,
            Consumer<Long> onOpenEvent,
            Runnable onNewEvent,
            boolean canCreateEvent
    ) {
        this.eventRepository = eventRepository;
        this.onOpenEvent = onOpenEvent;
        this.onNewEvent = onNewEvent;
        this.canCreateEvent = canCreateEvent;
        this.statsPanel = UiKit.statGrid();
        this.timelineView = new TimelineView();
        this.statusLabel = UiKit.muted("Cargando tu diario…");

        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());
        setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));

        JPanel page = UiKit.pagePanel();
        JButton action = null;
        if (canCreateEvent) {
            newEventButton = UiKit.primaryButton("Nuevo registro");
            newEventButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newEventButton.setIconTextGap(9);
            newEventButton.addActionListener(event -> onNewEvent.run());
            action = newEventButton;
        }
        page.add(new PageHeader(
                "RINGLOG · DIARIO DE CAMPO",
                "Tu diario de campo",
                "Un lugar tranquilo para volver a cada registro.",
                action
        ), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setOpaque(false);
        body.add(statsPanel, BorderLayout.NORTH);

        JPanel timelineSection = new JPanel(new BorderLayout(0, 12));
        timelineSection.setOpaque(false);
        JPanel sectionHeader = new JPanel(new BorderLayout());
        sectionHeader.setOpaque(false);
        JLabel sectionTitle = UiKit.valueLabel("Últimas páginas");
        sectionHeader.add(sectionTitle, BorderLayout.WEST);
        sectionHeader.add(statusLabel, BorderLayout.EAST);
        timelineSection.add(sectionHeader, BorderLayout.NORTH);
        timelineSection.add(timelineView, BorderLayout.CENTER);
        body.add(timelineSection, BorderLayout.CENTER);
        page.add(body, BorderLayout.CENTER);

        scroll = UiKit.scrollPane(page);
        add(scroll, BorderLayout.CENTER);
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (newEventButton != null) {
            newEventButton.setEnabled(enabled);
        }
    }

    public void refresh() {
        refresh(false);
    }

    public void rememberListPosition() {
        returnPosition = new Point(scroll.getViewport().getViewPosition());
    }

    public void resumeAfterDetail(boolean dataChanged) {
        if (!loaded || dataChanged) refresh(true);
        else restorePosition(loadGeneration, returnPosition);
    }

    private void refresh(boolean preservePosition) {
        int generation = ++loadGeneration;
        Point position = preservePosition ? new Point(returnPosition) : new Point();
        statusLabel.setText("Cargando tu diario…");

        new SwingWorker<DashboardWithTimeline, Void>() {
            @Override
            protected DashboardWithTimeline doInBackground() {
                return new DashboardWithTimeline(
                        eventRepository.findDashboardStats(),
                        eventRepository.findRecentTimeline(5)
                );
            }

            @Override
            protected void done() {
                if (generation != loadGeneration) return;
                try {
                    DashboardWithTimeline data = get();
                    renderStats(data.stats());
                    timelineView.setItems(data.timeline(), onOpenEvent, 5);
                    statusLabel.setText(data.timeline().isEmpty()
                            ? "Aún no hay registros"
                            : data.timeline().size() + " entradas guardadas");
                    loaded = true;
                    restorePosition(generation, position);
                } catch (InterruptedException | ExecutionException exception) {
                    loaded = false;
                    statusLabel.setText("No se ha podido cargar el diario");
                    timelineView.setItems(List.of(), onOpenEvent, 0);
                }
            }
        }.execute();
    }

    private void restorePosition(int generation, Point position) {
        SwingUtilities.invokeLater(() -> {
            if (generation != loadGeneration) return;
            scroll.validate();
            scroll.getViewport().setViewPosition(new Point(position));
            SwingUtilities.invokeLater(() -> {
                if (generation != loadGeneration) return;
                scroll.validate();
                int maximum = Math.max(0, scroll.getViewport().getViewSize().height
                        - scroll.getViewport().getExtentSize().height);
                scroll.getViewport().setViewPosition(new Point(0, Math.min(position.y, maximum)));
            });
        });
    }

    private void renderStats(DashboardStats stats) {
        statsPanel.removeAll();
        statsPanel.add(new StatCard(
                "REGISTROS",
                Long.toString(stats.eventCount()),
                "eventos guardados"
        ));
        statsPanel.add(new StatCard(
                "ESPECIES",
                Long.toString(stats.speciesCount()),
                "historias distintas"
        ));
        statsPanel.add(new StatCard(
                "LUGARES",
                Long.toString(stats.placeCount()),
                "lugares visitados"
        ));
        statsPanel.revalidate();
        statsPanel.repaint();
    }

    private record DashboardWithTimeline(
            DashboardStats stats,
            List<BirdEventTimelineItem> timeline
    ) {
    }
}

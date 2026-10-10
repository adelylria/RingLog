package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.model.BirdStatusCatalog;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.BirdHistoryPanel;
import com.adelylria.ringlog.ui.components.DetailSection;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.LocationMapPanel;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.UiKit;

public class CaptureDetailPanel extends JPanel {

    private final BirdEventRepository eventRepository;
    private final Runnable onBack;
    private final Consumer<BirdEventDetail> onEdit;
    private final boolean canEdit;
    private final Path mapCacheDirectory;
    private final JPanel content;
    private final JScrollPane scrollPane;
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private JPanel photoViewer;
    private EventPhotoGallery enlargedGallery;
    private Point photoReturnPosition;
    private long loadGeneration;
    private EventPhotoGallery photoGallery;
    private JButton editButton;
    private boolean mutationsEnabled = true;

    public CaptureDetailPanel(
            BirdEventRepository eventRepository,
            Runnable onBack
    ) {
        this(eventRepository, onBack, ignored -> { }, true, defaultMapCache());
    }

    public CaptureDetailPanel(
            BirdEventRepository eventRepository,
            Runnable onBack,
            Consumer<BirdEventDetail> onEdit
    ) {
        this(eventRepository, onBack, onEdit, true, defaultMapCache());
    }

    public CaptureDetailPanel(
            BirdEventRepository eventRepository,
            Runnable onBack,
            Consumer<BirdEventDetail> onEdit,
            boolean canEdit
    ) {
        this(eventRepository, onBack, onEdit, canEdit, defaultMapCache());
    }

    public CaptureDetailPanel(
            BirdEventRepository eventRepository,
            Runnable onBack,
            Consumer<BirdEventDetail> onEdit,
            boolean canEdit,
            Path mapCacheDirectory
    ) {
        this.eventRepository = eventRepository;
        this.onBack = onBack;
        this.onEdit = onEdit;
        this.canEdit = canEdit;
        this.mapCacheDirectory = mapCacheDirectory.toAbsolutePath().normalize();
        this.content = UiKit.verticalScrollPanel();
        this.scrollPane = UiKit.scrollPane(content);
        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());

        content.setBorder(new javax.swing.border.EmptyBorder(34, 38, 34, 38));

        scrollPane.setName("captureDetailScroll");
        scrollPane.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        views.setOpaque(false);
        views.add(scrollPane, "record");
        add(views, BorderLayout.CENTER);
    }

    public void setEventId(long eventId) {
        closePhotoViewer(false);
        long requestedGeneration = ++loadGeneration;
        if (photoGallery != null) photoGallery.dispose();
        scrollToTop();
        content.removeAll();
        content.add(new JLabel("Cargando la historia de la anilla…"));
        content.revalidate();
        content.repaint();
        scrollToTopAfterLayout();

        new SwingWorker<LoadedHistory, Void>() {
            @Override
            protected LoadedHistory doInBackground() {
                BirdEventDetail detail = eventRepository.findDetail(eventId).orElse(null);
                if (detail == null) {
                    return new LoadedHistory(null, List.of());
                }
                return new LoadedHistory(
                        detail,
                        eventRepository.findEventsByBird(detail.birdId())
                );
            }

            @Override
            protected void done() {
                if (requestedGeneration != loadGeneration) {
                    return;
                }
                try {
                    LoadedHistory loaded = get();
                    if (loaded.detail() == null) {
                        renderMissing();
                    } else {
                        render(
                                loaded.detail(),
                                normalizedHistory(loaded.detail(), loaded.events())
                        );
                    }
                } catch (InterruptedException | ExecutionException exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    renderMissing();
                }
            }
        }.execute();
    }

    private void render(
            BirdEventDetail detail,
            List<BirdEventTimelineItem> history
    ) {
        if (photoGallery != null) photoGallery.dispose();
        content.removeAll();

        JButton back = UiKit.secondaryButton("← Volver a registros");
        back.addActionListener(event -> onBack.run());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        if (canEdit) {
            JButton edit = UiKit.primaryButton("Editar este registro");
            editButton = edit;
            edit.setEnabled(mutationsEnabled);
            edit.setName("editSelectedEvent");
            edit.addActionListener(event -> onEdit.accept(detail));
            actions.add(edit);
        }
        actions.add(back);
        content.add(new PageHeader(
                "HISTORIA DEL AVE",
                UiKit.display(detail.species()),
                birdHistorySummary(detail, history.size()),
                actions
        ));
        content.add(Box.createVerticalStrut(20));

        BirdHistoryPanel historyPanel = new BirdHistoryPanel(
                history,
                detail.id(),
                this::setEventId
        );
        content.add(historyPanel);
        content.add(Box.createVerticalStrut(14));

        JPanel identity = UiKit.sectionPanel();
        JPanel identityCopy = new JPanel();
        identityCopy.setOpaque(false);
        identityCopy.setLayout(new BoxLayout(identityCopy, BoxLayout.Y_AXIS));
        JLabel selectedEvent = new JLabel(
                detail.eventType() + " · " + UiKit.date(detail.eventDate())
        );
        selectedEvent.setName("selectedEventTitle");
        selectedEvent.setFont(selectedEvent.getFont().deriveFont(Font.BOLD, 17f));
        identityCopy.add(selectedEvent);
        String eventSummary = selectedMomentSummary(detail);
        if (hasText(detail.status())) {
            eventSummary += " · " + displayBirdStatus(detail.status());
        }
        identityCopy.add(UiKit.muted(eventSummary));
        identity.add(identityCopy, BorderLayout.CENTER);
        if (hasText(detail.birdCondition())) {
            identity.add(
                    UiKit.chip(UiKit.friendlyCode(detail.birdCondition())),
                    BorderLayout.EAST
            );
        }
        content.add(identity);
        content.add(Box.createVerticalStrut(14));

        if ("REVIEW".equalsIgnoreCase(detail.reviewStatus())) {
            JPanel reviewNotice = UiKit.sectionPanel();
            reviewNotice.add(UiKit.valueLabel("Este registro necesita revisión"),
                    BorderLayout.NORTH);
            reviewNotice.add(UiKit.muted(hasText(detail.reviewNote())
                    ? detail.reviewNote()
                    : "Hay uno o más campos históricos pendientes de comprobar."),
                    BorderLayout.CENTER);
            content.add(reviewNotice);
            content.add(Box.createVerticalStrut(14));
        }

        DetailSection moment = new DetailSection("El momento");
        moment.addRow("Tipo de registro", detail.eventType());
        moment.addRow("Fecha", UiKit.date(detail.eventDate()));
        moment.addRow("Hora", UiKit.time(detail.eventTime()));
        moment.addRow("Lugar", detail.place());
        moment.addRow("Localidad", detail.locality());
        moment.addRow("Ubicación libre", detail.locationText());
        addSection(moment);

        DetailSection bird = new DetailSection("El ave");
        bird.addRow("Anilla", detail.ringNumber());
        bird.addWrappingRow("Especie", detail.species());
        bird.addRow("Sexo", UiKit.friendlyCode(detail.sexCode()));
        bird.addRow("Código de estado", statusCode(detail.status()));
        bird.addRow("Categoría de estado", statusCategory(detail.status()));
        bird.addWrappingRow("Estado del ave", statusDescription(detail.status()))
                .setName("birdStatusDescription");
        bird.addRow("Condición general", UiKit.friendlyCode(detail.birdCondition()));
        bird.addRow("Estado vital", detail.dead() ? "Fallecida" : "Viva");
        photoGallery = new EventPhotoGallery(detail.photoPaths(), null, index -> openPhotoViewer(detail, index));
        bird.addAside(photoGallery);
        addSection(bird);

        DetailSection followUp = new DetailSection("Seguimiento");
        followUp.addRow(
                "Reproducción",
                UiKit.friendlyCode(detail.reproductiveStatus())
        );
        followUp.addRow("Muda", UiKit.friendlyCode(detail.moultIntensity()));
        followUp.addRow(
                "Extensión de muda",
                UiKit.friendlyCode(detail.moultExtension())
        );
        followUp.addRow("Vuelta", UiKit.friendlyCode(detail.returnStatus()));
        followUp.addRow("Anillador", detail.ringerInitials());
        followUp.addWrappingRow(
                "Clasificación histórica",
                UiKit.friendlyCode(detail.captureType())
        );

        DetailSection measurements = new DetailSection("Medidas");
        measurements.addRow("Edad", detail.ageEuringCode());
        measurements.addRow("Ala", withUnit(detail.wing(), "mm"));
        measurements.addRow("P3", withUnit(detail.p3(), "mm"));
        measurements.addRow("Tarso", withUnit(detail.torso(), "mm"));
        measurements.addRow("Peso", withUnit(detail.weight(), "g"));
        measurements.addRow("Grasa", detail.fatScore());
        measurements.addRow("Músculo", detail.muscleScore());

        JPanel columns = UiKit.naturalGrid(2, 14);
        columns.setAlignmentX(Component.LEFT_ALIGNMENT);
        columns.add(followUp);
        columns.add(measurements);
        content.add(columns);
        content.add(Box.createVerticalStrut(14));

        DetailSection weather = new DetailSection("Condiciones");
        weather.addRow("Nubes", UiKit.friendlyCode(detail.clouds()));
        weather.addRow("Lluvia", UiKit.friendlyCode(detail.rain()));
        weather.addRow(
                "Sensación térmica",
                withTextUnit(detail.thermalSensation(), "°C")
        );
        weather.addRow("Viento", UiKit.friendlyCode(detail.wind()));
        weather.addRow(
                "Coordenadas",
                coordinates(detail.latitude(), detail.longitude())
        );
        if (LocationMapPanel.validCoordinates(detail.latitude(), detail.longitude())) {
            weather.addAside(new LocationMapPanel(
                    detail.latitude(),
                    detail.longitude(),
                    mapCacheDirectory
            ));
        }
        addSection(weather);

        DetailSection notes = new DetailSection("Notas de campo");
        notes.addTextBlock(detail.observations()).setName("fieldNotesText");
        addSection(notes);

        DetailSection archive = new DetailSection("Archivo y revisión");
        archive.addRow("Estado de revisión", UiKit.friendlyCode(detail.reviewStatus()));
        archive.addRow("Nota de revisión", detail.reviewNote());
        archive.addRow(
                "Fotografías",
                detail.photoPaths().isEmpty()
                        ? "Sin fotografías"
                        : detail.photoPaths().size()
        );
        addSection(archive);

        content.revalidate();
        content.repaint();
        scrollToTopAfterLayout();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationsEnabled = enabled;
        if (editButton != null) editButton.setEnabled(enabled);
    }

    private void openPhotoViewer(BirdEventDetail detail, int index) {
        closePhotoViewer(false);
        photoReturnPosition = scrollPane.getViewport().getViewPosition();
        photoViewer = UiKit.pagePanel();
        photoViewer.setName("eventPhotoViewer");
        photoViewer.setLayout(new BorderLayout(0, 24));
        JButton back = UiKit.secondaryButton("← Volver al registro");
        back.setName("backToPhotoRecord");
        back.addActionListener(event -> closePhotoViewer(true));
        photoViewer.add(new PageHeader(
                "FOTOGRAFÍAS DEL REGISTRO",
                "Anilla " + UiKit.display(detail.ringNumber()),
                detail.eventType() + " · " + UiKit.date(detail.eventDate()) + " · " + UiKit.display(detail.species()),
                back
        ), BorderLayout.NORTH);
        enlargedGallery = EventPhotoGallery.expanded(detail.photoPaths(), index);
        photoViewer.add(enlargedGallery, BorderLayout.CENTER);
        photoViewer.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "backToRecord");
        photoViewer.getActionMap().put("backToRecord", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { closePhotoViewer(true); }
        });
        views.add(photoViewer, "photos");
        viewLayout.show(views, "photos");
        revalidate();
        repaint();
        EventPhotoGallery opened = enlargedGallery;
        SwingUtilities.invokeLater(() -> {
            if (opened == enlargedGallery) opened.focusImage();
        });
    }

    private void closePhotoViewer(boolean restorePosition) {
        if (photoViewer == null) return;
        int selectedPhoto = enlargedGallery.selectedIndex();
        enlargedGallery.dispose();
        enlargedGallery = null;
        views.remove(photoViewer);
        photoViewer = null;
        viewLayout.show(views, "record");
        revalidate();
        repaint();
        if (restorePosition && photoGallery != null) {
            photoGallery.showPhoto(selectedPhoto);
            long generation = loadGeneration;
            Point position = photoReturnPosition;
            SwingUtilities.invokeLater(() -> {
                if (generation == loadGeneration && photoViewer == null) {
                    photoGallery.focusImage();
                    scrollPane.getViewport().setViewPosition(position);
                }
            });
        }
    }

    private void addSection(DetailSection section) {
        if (section.isEmpty()) {
            return;
        }
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(section);
        content.add(Box.createVerticalStrut(14));
    }

    private void renderMissing() {
        content.removeAll();
        JButton back = UiKit.secondaryButton("← Volver a registros");
        back.addActionListener(event -> onBack.run());
        content.add(new PageHeader(
                "ENTRADA DE CAMPO",
                "No encontramos este registro",
                "Puede que haya sido eliminada o que ya no esté disponible.",
                back
        ));
        content.add(Box.createVerticalStrut(20));
        content.add(new EmptyStatePanel(
                "Entrada no disponible",
                "Vuelve al diario para continuar explorando tus observaciones.",
                onBack
        ));
        content.revalidate();
        content.repaint();
        scrollToTopAfterLayout();
    }

    private void scrollToTopAfterLayout() {
        scrollToTop();
        SwingUtilities.invokeLater(this::scrollToTop);
    }

    private void scrollToTop() {
        scrollPane.getViewport().setViewPosition(new Point(0, 0));
        scrollPane.getVerticalScrollBar().setValue(0);
    }

    private static String withUnit(Object value, String unit) {
        String text = UiKit.display(value);
        return "—".equals(text) ? text : text + " " + unit;
    }

    private static String withTextUnit(String value, String unit) {
        String text = UiKit.display(value);
        return "—".equals(text) || text.endsWith(unit) ? text : text + " " + unit;
    }

    private static String displayLocation(BirdEventDetail detail) {
        if (detail.place() != null && !detail.place().isBlank()) {
            return detail.locality() == null || detail.locality().isBlank()
                    ? detail.place()
                    : detail.place() + " · " + detail.locality();
        }
        return UiKit.display(detail.locationText());
    }

    private static String birdHistorySummary(
            BirdEventDetail detail,
            int eventCount
    ) {
        return "Anilla " + UiKit.display(detail.ringNumber()) + " · "
                + eventCount + " " + (eventCount == 1 ? "registro" : "registros");
    }

    private static String selectedMomentSummary(BirdEventDetail detail) {
        String time = UiKit.time(detail.eventTime());
        String location = displayLocation(detail);
        if ("—".equals(time)) {
            return location;
        }
        if ("—".equals(location)) {
            return time;
        }
        return time + " · " + location;
    }

    private static List<BirdEventTimelineItem> normalizedHistory(
            BirdEventDetail detail,
            List<BirdEventTimelineItem> events
    ) {
        List<BirdEventTimelineItem> history = new ArrayList<>(events);
        if (history.stream().noneMatch(event -> event.id() == detail.id())) {
            history.add(timelineItem(detail));
        }
        return List.copyOf(history);
    }

    private static BirdEventTimelineItem timelineItem(BirdEventDetail detail) {
        return new BirdEventTimelineItem(
                detail.id(),
                detail.birdId(),
                detail.ringNumber(),
                detail.eventType(),
                detail.eventDate(),
                detail.eventTime(),
                detail.species(),
                displayLocation(detail),
                detail.observations(),
                detail.birdCondition(),
                detail.photoPaths().isEmpty() ? null : detail.photoPaths().get(0),
                detail.reviewStatus()
        );
    }

    private static String coordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude);
    }

    private static String displayBirdStatus(String value) {
        return BirdStatusCatalog.find(value)
                .map(BirdStatusCatalog.Entry::displayLabel)
                .orElseGet(() -> UiKit.friendlyCode(value));
    }

    private static String statusCode(String value) {
        return BirdStatusCatalog.find(value)
                .map(BirdStatusCatalog.Entry::code)
                .orElseGet(() -> UiKit.display(value));
    }

    private static String statusCategory(String value) {
        return BirdStatusCatalog.find(value)
                .map(BirdStatusCatalog.Entry::category)
                .orElse("—");
    }

    private static String statusDescription(String value) {
        return BirdStatusCatalog.find(value)
                .map(BirdStatusCatalog.Entry::description)
                .orElseGet(() -> UiKit.friendlyCode(value));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Path defaultMapCache() {
        return Path.of(System.getProperty("java.io.tmpdir"), "ringlog-map-cache");
    }

    private record LoadedHistory(
            BirdEventDetail detail,
            List<BirdEventTimelineItem> events
    ) {
    }
}

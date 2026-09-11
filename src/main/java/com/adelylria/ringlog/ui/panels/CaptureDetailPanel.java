package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.BirdStatusCatalog;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.DetailSection;
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
        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());

        content.setBorder(new javax.swing.border.EmptyBorder(34, 38, 34, 38));

        JScrollPane scroll = UiKit.scrollPane(content);
        scroll.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        add(scroll, BorderLayout.CENTER);
    }

    public void setEventId(long eventId) {
        content.removeAll();
        content.add(new JLabel("Cargando la entrada…"));
        content.revalidate();
        content.repaint();

        new SwingWorker<BirdEventDetail, Void>() {
            @Override
            protected BirdEventDetail doInBackground() {
                return eventRepository.findDetail(eventId).orElse(null);
            }

            @Override
            protected void done() {
                try {
                    BirdEventDetail detail = get();
                    if (detail == null) {
                        renderMissing();
                    } else {
                        render(detail);
                    }
                } catch (InterruptedException | ExecutionException exception) {
                    renderMissing();
                }
            }
        }.execute();
    }

    private void render(BirdEventDetail detail) {
        content.removeAll();

        JButton back = UiKit.secondaryButton("← Volver al diario");
        back.addActionListener(event -> onBack.run());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        if (canEdit) {
            JButton edit = UiKit.primaryButton("Editar entrada");
            edit.addActionListener(event -> onEdit.accept(detail));
            actions.add(edit);
        }
        actions.add(back);
        content.add(new PageHeader(
                "ENTRADA DE CAMPO",
                UiKit.display(detail.species()),
                UiKit.date(detail.eventDate())
                        + " · "
                        + UiKit.time(detail.eventTime())
                        + " · "
                        + displayLocation(detail),
                actions
        ));
        content.add(Box.createVerticalStrut(20));

        JPanel identity = UiKit.sectionPanel();
        JPanel identityCopy = new JPanel();
        identityCopy.setOpaque(false);
        identityCopy.setLayout(new BoxLayout(identityCopy, BoxLayout.Y_AXIS));
        JLabel ring = new JLabel("Anilla " + UiKit.display(detail.ringNumber()));
        ring.setFont(ring.getFont().deriveFont(Font.BOLD, 17f));
        identityCopy.add(ring);
        String eventSummary = detail.eventType().toString();
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

        addPhotoGallery(detail);

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
        bird.addRow("Especie", detail.species());
        bird.addRow("Sexo", UiKit.friendlyCode(detail.sexCode()));
        bird.addRow("Código de edad EURING", detail.ageEuringCode());
        bird.addRow("Código de estado", statusCode(detail.status()));
        bird.addRow("Categoría de estado", statusCategory(detail.status()));
        bird.addWrappingRow("Estado del ave", statusDescription(detail.status()))
                .setName("birdStatusDescription");
        bird.addRow("Condición general", UiKit.friendlyCode(detail.birdCondition()));
        bird.addRow(
                "Reproducción",
                UiKit.friendlyCode(detail.reproductiveStatus())
        );
        bird.addRow("Muda", UiKit.friendlyCode(detail.moultIntensity()));
        bird.addRow(
                "Extensión de muda",
                UiKit.friendlyCode(detail.moultExtension())
        );
        bird.addRow("Vuelta", UiKit.friendlyCode(detail.returnStatus()));
        bird.addRow("Anillador", detail.ringerInitials());
        bird.addRow(
                "Clasificación histórica",
                UiKit.friendlyCode(detail.captureType())
        );
        bird.addRow("Estado vital", detail.dead() ? "Fallecida" : "Viva");

        DetailSection measurements = new DetailSection("Medidas");
        measurements.addRow("Ala", withUnit(detail.wing(), "mm"));
        measurements.addRow("P3", withUnit(detail.p3(), "mm"));
        measurements.addRow("Tarso", withUnit(detail.torso(), "mm"));
        measurements.addRow("Peso", withUnit(detail.weight(), "g"));
        measurements.addRow("Grasa", detail.fatScore());
        measurements.addRow("Músculo", detail.muscleScore());

        JPanel columns = UiKit.naturalGrid(2, 14);
        columns.setAlignmentX(Component.LEFT_ALIGNMENT);
        columns.add(bird);
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
    }

    private void addPhotoGallery(BirdEventDetail detail) {
        if (detail.photoPaths().isEmpty()) {
            return;
        }

        JPanel gallery = UiKit.naturalGrid(2, 14);
        gallery.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String path : detail.photoPaths()) {
            if (path == null || !Files.isRegularFile(Path.of(path))) {
                continue;
            }
            try {
                var image = ImageIO.read(Path.of(path).toFile());
                if (image == null) {
                    continue;
                }
                double scale = Math.min(
                        1d,
                        Math.min(420d / image.getWidth(), 260d / image.getHeight())
                );
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                var scaled = image.getScaledInstance(
                        width,
                        height,
                        java.awt.Image.SCALE_SMOOTH
                );
                JLabel photo = new JLabel(new ImageIcon(scaled));
                photo.setBorder(UiKit.cardBorder());
                gallery.add(photo);
            } catch (IOException ignored) {
                // A missing or unreadable image should not make the diary unreadable.
            }
        }
        if (gallery.getComponentCount() > 0) {
            content.add(gallery);
            content.add(Box.createVerticalStrut(14));
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
        JButton back = UiKit.secondaryButton("← Volver al diario");
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
}

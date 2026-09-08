package com.adelylria.ringlog.ui.importexport;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.repository.MigrationConflictRepository;
import com.adelylria.ringlog.repository.MigrationConflictRepository.ConflictRecord;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.storage.AppPaths;

/** Card-based review of field conflicts left by an authoritative legacy import. */
public final class ConflictsPanel extends JPanel {

    private static final String LIST_VIEW = "list";
    private static final String REVIEW_VIEW = "review";

    private static final List<ResolutionRequest.Type> RESOLUTION_TYPES = List.of(
            ResolutionRequest.Type.CANONICAL,
            ResolutionRequest.Type.ALTERNATIVE,
            ResolutionRequest.Type.MANUAL_VALUE,
            ResolutionRequest.Type.PENDING
    );
    private static final List<String> RESOLUTION_LABELS = List.of(
            "Conservar el valor actual",
            "Usar el valor alternativo",
            "Escribir un valor manual",
            "Decidir más tarde"
    );

    private final MigrationConflictRepository repository;
    private final ConflictResolutionService resolutionService;
    private final Runnable dataChanged;
    private final JTextField searchField = new JTextField();
    private final JComboBox<String> fieldFilter = new JComboBox<>();
    private final JPanel cards = UiKit.verticalScrollPanel();
    private final JLabel status = UiKit.muted("Cargando revisiones…");
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private List<ConflictRecord> conflicts = List.of();
    private ConflictReviewPanel activeReview;
    private boolean mutationActionsEnabled = true;

    public ConflictsPanel(
            MigrationConflictRepository repository,
            ConflictResolutionService resolutionService,
            Runnable dataChanged
    ) {
        this.repository = repository;
        this.resolutionService = resolutionService;
        this.dataChanged = dataChanged == null ? () -> { } : dataChanged;
        initialize();
    }

    public static ConflictsPanel applicationDefault(Runnable dataChanged) {
        return application(AppPaths.production(), dataChanged);
    }

    public static ConflictsPanel application(AppPaths paths, Runnable dataChanged) {
        String database = paths.databasePath().toString();
        return new ConflictsPanel(
                new MigrationConflictRepository(database),
                new ConflictResolutionService(database),
                dataChanged
        );
    }

    public static List<ResolutionRequest.Type> resolutionTypes() {
        return RESOLUTION_TYPES;
    }

    public static List<String> resolutionLabels() {
        return RESOLUTION_LABELS;
    }

    private void initialize() {
        setLayout(new BorderLayout());
        views.setOpaque(false);
        JPanel page = UiKit.pagePanel();
        page.add(new PageHeader(
                "TRAZABILIDAD",
                "Revisiones",
                "Comprueba únicamente los campos que el migrador dejó pendientes.",
                null
        ), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.add(filters(), BorderLayout.NORTH);
        JScrollPane scroll = UiKit.scrollPane(cards);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        body.add(scroll, BorderLayout.CENTER);
        page.add(body, BorderLayout.CENTER);
        page.add(status, BorderLayout.SOUTH);
        views.add(page, LIST_VIEW);
        add(views, BorderLayout.CENTER);
    }

    private JPanel filters() {
        SurfacePanel panel = new SurfacePanel(new BorderLayout(12, 0));
        panel.setBorder(UiKit.cardBorder());
        searchField.putClientProperty(
                "JTextField.placeholderText", "Buscar por anilla, campo o fecha…"
        );
        searchField.getAccessibleContext().setAccessibleName("Buscar revisiones pendientes");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                renderFiltered();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                renderFiltered();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                renderFiltered();
            }
        });
        panel.add(searchField, BorderLayout.CENTER);
        fieldFilter.addItem("Todos los campos");
        fieldFilter.getAccessibleContext().setAccessibleName("Filtrar revisiones por campo");
        fieldFilter.addActionListener(event -> renderFiltered());
        panel.add(fieldFilter, BorderLayout.EAST);
        return panel;
    }

    public void refresh() {
        showList();
        status.setText("Cargando revisiones…");
        new SwingWorker<List<ConflictRecord>, Void>() {
            @Override
            protected List<ConflictRecord> doInBackground() {
                return repository.findPending();
            }

            @Override
            protected void done() {
                try {
                    conflicts = List.copyOf(get());
                    rebuildFieldFilter();
                    renderFiltered();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showLoadError(exception);
                } catch (ExecutionException | RuntimeException exception) {
                    Throwable cause = exception.getCause();
                    showLoadError(cause == null ? exception : cause);
                }
            }
        }.execute();
    }

    private void rebuildFieldFilter() {
        Object selected = fieldFilter.getSelectedItem();
        List<String> fields = conflicts.stream()
                .map(ConflictRecord::fieldName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        fieldFilter.removeAllItems();
        fieldFilter.addItem("Todos los campos");
        fields.forEach(field -> fieldFilter.addItem(fieldLabel(field)));
        if (selected != null) {
            fieldFilter.setSelectedItem(selected);
        }
    }

    private void renderFiltered() {
        if (cards == null) {
            return;
        }
        String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
        String selectedField = (String) fieldFilter.getSelectedItem();
        List<ConflictRecord> visible = conflicts.stream()
                .filter(conflict -> matches(conflict, query, selectedField))
                .toList();

        cards.removeAll();
        if (visible.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    conflicts.isEmpty() ? "Todo revisado" : "Sin coincidencias",
                    conflicts.isEmpty()
                            ? "No quedan campos pendientes de comprobar."
                            : "Prueba con otra anilla, fecha o campo.",
                    null
            ));
        } else {
            for (ConflictRecord conflict : visible) {
                cards.add(conflictCard(conflict));
                cards.add(Box.createVerticalStrut(10));
            }
        }
        status.setText(visible.size() + (visible.size() == 1
                ? " revisión pendiente" : " revisiones pendientes"));
        cards.revalidate();
        cards.repaint();
    }

    private JPanel conflictCard(ConflictRecord conflict) {
        SurfacePanel card = new SurfacePanel(new BorderLayout(18, 0));
        card.setBorder(UiKit.cardBorder());

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JPanel metadata = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        metadata.setOpaque(false);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        metadata.add(UiKit.chip(fieldLabel(conflict.fieldName())));
        metadata.add(UiKit.muted("Anilla " + UiKit.display(conflict.ringNumber())));
        metadata.add(UiKit.muted(UiKit.date(conflict.eventDate())
                + " · " + UiKit.time(conflict.eventTime())));
        copy.add(metadata);
        copy.add(Box.createVerticalStrut(9));
        JLabel title = UiKit.valueLabel(
                "Actual: " + UiKit.display(conflict.canonicalValue())
        );
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        copy.add(title);
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted(
                "Alternativa: " + UiKit.display(conflict.alternativeValue())
        ));
        card.add(copy, BorderLayout.CENTER);

        if (resolutionService != null) {
            JButton review = UiKit.primaryButton("Revisar");
            review.setToolTipText("Comparar el origen y resolver este campo");
            review.setEnabled(mutationActionsEnabled);
            review.addActionListener(event -> showReview(conflict));
            card.add(review, BorderLayout.EAST);
        }
        return card;
    }

    void showReview(ConflictRecord conflict) {
        if (resolutionService == null || !mutationActionsEnabled) {
            return;
        }
        if (activeReview != null) {
            views.remove(activeReview);
        }
        activeReview = new ConflictReviewPanel(
                conflict,
                request -> resolve(conflict, request),
                this::showList
        );
        views.add(activeReview, REVIEW_VIEW);
        viewLayout.show(views, REVIEW_VIEW);
        views.revalidate();
        views.repaint();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationActionsEnabled = enabled;
        if (activeReview != null && !enabled) {
            showList();
        }
        renderFiltered();
    }

    private void resolve(ConflictRecord conflict, ResolutionRequest request) {
        if (activeReview != null) {
            activeReview.setBusy(true);
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                resolutionService.resolve(conflict.id(), request);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    dataChanged.run();
                    showList();
                    refresh();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showResolutionError(exception);
                } catch (ExecutionException | RuntimeException exception) {
                    Throwable cause = exception.getCause();
                    showResolutionError(cause == null ? exception : cause);
                }
            }
        }.execute();
    }

    private void showList() {
        viewLayout.show(views, LIST_VIEW);
        views.revalidate();
        views.repaint();
    }

    private boolean matches(ConflictRecord conflict, String query, String selectedField) {
        boolean fieldMatches = selectedField == null
                || "Todos los campos".equals(selectedField)
                || selectedField.equals(fieldLabel(conflict.fieldName()));
        if (!fieldMatches || query.isEmpty()) {
            return fieldMatches;
        }
        String searchable = String.join(" ",
                safe(conflict.ringNumber()), safe(conflict.fieldName()),
                safe(conflict.eventDate()), safe(conflict.canonicalValue()),
                safe(conflict.alternativeValue())
        ).toLowerCase(Locale.ROOT);
        return searchable.contains(query);
    }

    private void showLoadError(Throwable exception) {
        conflicts = List.of();
        cards.removeAll();
        cards.add(new EmptyStatePanel(
                "No se pudieron cargar las revisiones",
                userMessage(exception), null
        ));
        status.setText("Revisiones no disponibles");
        cards.revalidate();
        cards.repaint();
    }

    private void showResolutionError(Throwable exception) {
        if (activeReview != null) {
            activeReview.showError(userMessage(exception));
        }
    }

    private static String userMessage(Throwable exception) {
        return exception == null || exception.getMessage() == null
                ? "Se produjo un error inesperado."
                : exception.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String fieldLabel(String field) {
        return switch (safe(field)) {
            case "event_date" -> "Fecha";
            case "event_time" -> "Hora";
            default -> safe(field).replace('_', ' ');
        };
    }

}

package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.PlaceForm;
import com.adelylria.ringlog.ui.components.TwoColumnCardPanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public class PlacePanel extends JPanel {

    private static final String CATALOG_VIEW = "catalog";
    private static final String EDITOR_VIEW = "editor";

    private final CatalogRepository catalogRepository;
    private final JPanel cards;
    private final boolean canManage;
    private final List<JButton> editButtons = new ArrayList<>();
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private JButton newPlaceButton;
    private JButton currentSaveButton;
    private JPanel currentEditor;
    private boolean mutationActionsEnabled = true;

    public PlacePanel(CatalogRepository catalogRepository) {
        this(catalogRepository, true);
    }

    public PlacePanel(CatalogRepository catalogRepository, boolean canManage) {
        this.catalogRepository = catalogRepository;
        this.canManage = canManage;
        this.cards = new TwoColumnCardPanel(14);
        initialize();
    }

    public PlacePanel() {
        this(new CatalogRepository());
    }

    private void initialize() {
        setLayout(new BorderLayout());
        views.setOpaque(false);
        views.add(createCatalogPage(), CATALOG_VIEW);
        add(views, BorderLayout.CENTER);
    }

    private JPanel createCatalogPage() {
        JPanel page = UiKit.pagePanel();
        JButton action = null;
        if (canManage) {
            newPlaceButton = UiKit.primaryButton("Nuevo lugar");
            newPlaceButton.setName("newPlaceButton");
            newPlaceButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newPlaceButton.addActionListener(event -> showEditor(null));
            action = newPlaceButton;
        }
        page.add(new PageHeader(
                "MAPA DE MEMORIA",
                "Lugares",
                "Lugares reutilizables para anillamientos, controles y recuperaciones.",
                action
        ), BorderLayout.NORTH);

        cards.setOpaque(false);
        JScrollPane scroll = UiKit.scrollPane(cards);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationActionsEnabled = enabled;
        if (newPlaceButton != null) {
            newPlaceButton.setEnabled(enabled);
        }
        if (currentSaveButton != null) {
            currentSaveButton.setEnabled(enabled);
        }
        editButtons.forEach(button -> button.setEnabled(enabled));
    }

    private void showEditor(PlaceSummary place) {
        if (!canManage || !mutationActionsEnabled) {
            return;
        }
        if (currentEditor != null) {
            views.remove(currentEditor);
        }
        currentEditor = createEditorPage(place);
        views.add(currentEditor, EDITOR_VIEW);
        viewLayout.show(views, EDITOR_VIEW);
        views.revalidate();
        views.repaint();
    }

    private JPanel createEditorPage(PlaceSummary place) {
        boolean editing = place != null;
        PlaceForm form = editing ? new PlaceForm(place) : new PlaceForm();
        form.setName("placeEditorForm");
        JLabel status = UiKit.muted("El nombre es obligatorio; el resto es opcional.");
        JButton cancelButton = UiKit.secondaryButton("Cancelar");
        cancelButton.setName("cancelPlaceEditorButton");
        cancelButton.addActionListener(event -> showCatalog());
        currentSaveButton = UiKit.primaryButton(
                editing ? "Guardar cambios" : "Guardar lugar"
        );
        currentSaveButton.setName("savePlaceButton");
        currentSaveButton.setEnabled(mutationActionsEnabled);

        JPanel page = UiKit.pagePanel();
        page.setName("placeEditorView");
        page.add(new PageHeader(
                editing ? "EDITAR LUGAR" : "NUEVO LUGAR",
                editing ? place.name() : "Añadir lugar",
                editing
                        ? "Actualiza sus datos sin perder los registros asociados."
                        : "Guárdalo una vez para reutilizarlo en tus registros.",
                cancelButton
        ), BorderLayout.NORTH);

        JPanel formCard = UiKit.sectionPanel();
        formCard.add(form, BorderLayout.CENTER);
        JPanel formColumn = UiKit.verticalScrollPanel();
        formColumn.add(formCard);
        JScrollPane scroll = UiKit.scrollPane(formColumn);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        page.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        footer.add(currentSaveButton, BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);

        JButton saveButton = currentSaveButton;
        saveButton.addActionListener(event -> savePlace(
                form, status, saveButton, editing ? place.id() : null
        ));
        return page;
    }

    private void savePlace(
            PlaceForm form,
            JLabel status,
            JButton saveButton,
            Long placeId
    ) {
        final PlaceInput input;
        try {
            input = form.input();
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
            return;
        }
        saveButton.setEnabled(false);
        status.setText("Guardando lugar…");
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() {
                return placeId == null
                        ? catalogRepository.insertPlace(input)
                        : catalogRepository.updatePlace(placeId, input);
            }

            @Override
            protected void done() {
                try {
                    get();
                    showCatalog();
                    refreshCards();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    saveButton.setEnabled(mutationActionsEnabled);
                    status.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    saveButton.setEnabled(mutationActionsEnabled);
                    Throwable cause = exception.getCause();
                    status.setText(cause instanceof IllegalArgumentException
                            ? cause.getMessage()
                            : "No se ha podido guardar el lugar.");
                }
            }
        }.execute();
    }

    public void refresh() {
        showCatalog();
        refreshCards();
    }

    private void showCatalog() {
        viewLayout.show(views, CATALOG_VIEW);
        currentSaveButton = null;
        views.revalidate();
        views.repaint();
    }

    private void refreshCards() {
        cards.removeAll();
        cards.add(UiKit.muted("Cargando lugares…"));
        cards.revalidate();
        cards.repaint();

        new SwingWorker<List<PlaceSummary>, Void>() {
            @Override
            protected List<PlaceSummary> doInBackground() {
                return catalogRepository.findPlaceSummaries();
            }

            @Override
            protected void done() {
                try {
                    render(get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    render(List.of());
                } catch (ExecutionException exception) {
                    render(List.of());
                }
            }
        }.execute();
    }

    private void render(List<PlaceSummary> places) {
        cards.removeAll();
        editButtons.clear();
        if (places.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    "Aún no hay lugares",
                    "Añade un lugar para reutilizarlo al crear tus registros.",
                    null
            ));
        } else {
            for (PlaceSummary item : places) {
                cards.add(placeCard(item));
            }
        }
        cards.revalidate();
        cards.repaint();
    }

    private JPanel placeCard(PlaceSummary item) {
        JPanel card = UiKit.sectionPanel();
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.valueLabel(item.name()));
        if (item.locality() != null && !item.locality().isBlank()) {
            copy.add(Box.createVerticalStrut(3));
            copy.add(UiKit.muted(item.locality()));
        }
        String region = regionDescription(item);
        if (!region.isBlank()) {
            copy.add(Box.createVerticalStrut(3));
            copy.add(UiKit.muted(region));
        }
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(placeDescription(item)));
        String flags = placeFlags(item);
        if (!flags.isBlank()) {
            copy.add(Box.createVerticalStrut(4));
            copy.add(UiKit.eyebrow(flags));
        }
        if (item.notes() != null && !item.notes().isBlank()) {
            copy.add(Box.createVerticalStrut(4));
            copy.add(UiKit.muted(item.notes()));
        }
        card.add(copy, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(UiKit.chip(Long.toString(item.eventCount())));
        if (canManage) {
            JButton edit = editButton("Editar " + item.name());
            edit.setName("editPlace-" + item.id());
            edit.setEnabled(mutationActionsEnabled);
            edit.addActionListener(event -> showEditor(item));
            editButtons.add(edit);
            actions.add(edit);
        }
        card.add(actions, BorderLayout.EAST);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    private static JButton editButton(String accessibleName) {
        JButton button = new JButton(new NavigationIcon(NavigationIcon.Kind.EDIT));
        button.setToolTipText(accessibleName);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setForeground(UiKit.accentColor());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(32, 32));
        return button;
    }

    private static String regionDescription(PlaceSummary item) {
        String community = item.autonomousCommunity();
        String country = item.country();
        if (community == null || community.isBlank()) {
            return country == null ? "" : country;
        }
        if (country == null || country.isBlank()) {
            return community;
        }
        return community + " · " + country;
    }

    private static String placeDescription(PlaceSummary item) {
        String count = item.eventCount() + " "
                + (item.eventCount() == 1 ? "registro" : "registros");
        if (item.latitude() == null || item.longitude() == null) {
            return count;
        }
        return String.format(
                java.util.Locale.ROOT,
                "%s · %.4f, %.4f",
                count,
                item.latitude(),
                item.longitude()
        );
    }

    private static String placeFlags(PlaceSummary item) {
        if (item.isDefault()) {
            return item.favorite() ? "PREDETERMINADO · FAVORITO" : "PREDETERMINADO";
        }
        return item.favorite() ? "FAVORITO" : "";
    }
}

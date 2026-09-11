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

import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.SpeciesForm;
import com.adelylria.ringlog.ui.components.TwoColumnCardPanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public class SpeciesPanel extends JPanel {

    private static final String CATALOG_VIEW = "catalog";
    private static final String EDITOR_VIEW = "editor";

    private final CatalogRepository catalogRepository;
    private final JPanel cards;
    private final boolean canManage;
    private final List<JButton> editButtons = new ArrayList<>();
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private JButton newSpeciesButton;
    private JButton currentSaveButton;
    private JPanel currentEditor;
    private boolean mutationActionsEnabled = true;

    public SpeciesPanel(CatalogRepository catalogRepository) {
        this(catalogRepository, true);
    }

    public SpeciesPanel(CatalogRepository catalogRepository, boolean canManage) {
        this.catalogRepository = catalogRepository;
        this.canManage = canManage;
        this.cards = new TwoColumnCardPanel(14);
        initialize();
    }

    public SpeciesPanel() {
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
            newSpeciesButton = UiKit.primaryButton("Nueva especie");
            newSpeciesButton.setName("newSpeciesButton");
            newSpeciesButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newSpeciesButton.addActionListener(event -> showEditor(null));
            action = newSpeciesButton;
        }
        page.add(new PageHeader(
                "CATÁLOGO VIVO",
                "Especies",
                "Las especies que han ido formando tu diario.",
                action
        ), BorderLayout.NORTH);

        cards.setOpaque(false);
        JScrollPane scroll = UiKit.scrollPane(cards);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationActionsEnabled = enabled;
        if (newSpeciesButton != null) {
            newSpeciesButton.setEnabled(enabled);
        }
        if (currentSaveButton != null) {
            currentSaveButton.setEnabled(enabled);
        }
        editButtons.forEach(button -> button.setEnabled(enabled));
    }

    private void showEditor(SpeciesSummary species) {
        if (!canManage || !mutationActionsEnabled) {
            return;
        }
        if (currentEditor != null) {
            views.remove(currentEditor);
        }
        currentEditor = createEditorPage(species);
        views.add(currentEditor, EDITOR_VIEW);
        viewLayout.show(views, EDITOR_VIEW);
        views.revalidate();
        views.repaint();
    }

    private JPanel createEditorPage(SpeciesSummary species) {
        boolean editing = species != null;
        SpeciesForm form = editing ? new SpeciesForm(species) : new SpeciesForm();
        form.setName("speciesEditorForm");
        JLabel status = UiKit.muted("El nombre científico es obligatorio.");
        JButton cancelButton = UiKit.secondaryButton("Cancelar");
        cancelButton.setName("cancelSpeciesEditorButton");
        cancelButton.addActionListener(event -> showCatalog());
        currentSaveButton = UiKit.primaryButton(
                editing ? "Guardar cambios" : "Guardar especie"
        );
        currentSaveButton.setName("saveSpeciesButton");
        currentSaveButton.setEnabled(mutationActionsEnabled);

        JPanel page = UiKit.pagePanel();
        page.setName("speciesEditorView");
        page.add(new PageHeader(
                editing ? "EDITAR ESPECIE" : "NUEVA ESPECIE",
                editing ? species.name() : "Añadir especie",
                editing
                        ? "Actualiza el catálogo sin perder las aves ni sus registros."
                        : "Añádela al catálogo para utilizarla en nuevos registros.",
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
        saveButton.addActionListener(event -> saveSpecies(
                form, status, saveButton, editing ? species.id() : null
        ));
        return page;
    }

    private void saveSpecies(
            SpeciesForm form,
            JLabel status,
            JButton saveButton,
            Long speciesId
    ) {
        final SpeciesInput input;
        try {
            input = form.input();
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
            return;
        }
        saveButton.setEnabled(false);
        status.setText("Guardando especie…");
        new SwingWorker<Long, Void>() {
            @Override
            protected Long doInBackground() {
                return speciesId == null
                        ? catalogRepository.insertSpecies(input)
                        : catalogRepository.updateSpecies(speciesId, input);
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
                            : "No se ha podido guardar la especie.");
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
        cards.add(UiKit.muted("Cargando especies…"));
        cards.revalidate();
        cards.repaint();

        new SwingWorker<List<SpeciesSummary>, Void>() {
            @Override
            protected List<SpeciesSummary> doInBackground() {
                return catalogRepository.findSpeciesSummaries();
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

    private void render(List<SpeciesSummary> species) {
        cards.removeAll();
        editButtons.clear();
        if (species.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    "Aún no hay especies",
                    "Añade una especie para utilizarla al crear tus registros.",
                    null
            ));
        } else {
            for (SpeciesSummary item : species) {
                cards.add(speciesCard(item));
            }
        }
        cards.revalidate();
        cards.repaint();
    }

    private JPanel speciesCard(SpeciesSummary item) {
        JPanel card = UiKit.sectionPanel();
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.valueLabel(item.name()));
        if (item.commonName() != null && !item.commonName().isBlank()
                && item.scientificName() != null && !item.scientificName().isBlank()) {
            copy.add(Box.createVerticalStrut(3));
            copy.add(UiKit.muted(item.scientificName()));
        }
        if (item.code() != null && !item.code().isBlank()) {
            copy.add(Box.createVerticalStrut(3));
            copy.add(UiKit.eyebrow(item.code()));
        }
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(item.eventCount() + " "
                + (item.eventCount() == 1 ? "registro" : "registros")));
        card.add(copy, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(UiKit.chip(Long.toString(item.eventCount())));
        if (canManage) {
            JButton edit = editButton("Editar " + item.name());
            edit.setName("editSpecies-" + item.id());
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
}

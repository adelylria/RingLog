package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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

    private final CatalogRepository catalogRepository;
    private final JPanel cards;
    private final boolean canManage;
    private JButton newSpeciesButton;

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
        JPanel page = UiKit.pagePanel();
        JButton action = null;
        if (canManage) {
            newSpeciesButton = UiKit.primaryButton("Nueva especie");
            newSpeciesButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newSpeciesButton.addActionListener(event -> openNewSpeciesDialog());
            action = newSpeciesButton;
        }
        page.add(new PageHeader(
                "CATÁLOGO VIVO",
                "Especies",
                "Las especies que han ido formando tu diario.",
                action
        ), BorderLayout.NORTH);

        cards.setOpaque(false);
        JScrollPane scroll = new JScrollPane(cards);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        page.add(scroll, BorderLayout.CENTER);
        add(page, BorderLayout.CENTER);
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (newSpeciesButton != null) {
            newSpeciesButton.setEnabled(enabled);
        }
    }

    private void openNewSpeciesDialog() {
        SpeciesForm form = new SpeciesForm();
        JLabel status = UiKit.muted("El nombre científico es obligatorio.");
        JButton saveButton = UiKit.primaryButton("Guardar especie");
        JButton cancelButton = UiKit.secondaryButton("Cancelar");
        JDialog dialog = createDialog("Nueva especie");

        JPanel content = UiKit.sectionPanel();
        content.add(form, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(cancelButton);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(saveButton);
        footer.add(actions, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(saveButton);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        cancelButton.addActionListener(event -> dialog.dispose());
        saveButton.addActionListener(event -> saveSpecies(form, dialog, status, saveButton));
        dialog.setVisible(true);
    }

    private JDialog createDialog(String title) {
        Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.getRootPane().setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18));
        return dialog;
    }

    private void saveSpecies(
            SpeciesForm form,
            JDialog dialog,
            JLabel status,
            JButton saveButton
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
                return catalogRepository.insertSpecies(input);
            }

            @Override
            protected void done() {
                try {
                    get();
                    dialog.dispose();
                    refresh();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    saveButton.setEnabled(true);
                    status.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    saveButton.setEnabled(true);
                    Throwable cause = exception.getCause();
                    status.setText(cause instanceof IllegalArgumentException
                            ? cause.getMessage()
                            : "No se ha podido guardar la especie.");
                }
            }
        }.execute();
    }

    public void refresh() {
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
                } catch (InterruptedException | ExecutionException exception) {
                    render(List.of());
                }
            }
        }.execute();
    }

    private void render(List<SpeciesSummary> species) {
        cards.removeAll();
        if (species.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    "Aún no hay especies",
                    "Las especies aparecerán cuando exista un registro asociado.",
                    null
            ));
        } else {
            for (SpeciesSummary item : species) {
                JPanel card = UiKit.sectionPanel();
                JPanel copy = new JPanel();
                copy.setOpaque(false);
                copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
                copy.add(UiKit.valueLabel(item.name()));
                copy.add(Box.createVerticalStrut(4));
                copy.add(UiKit.muted(item.eventCount() + " "
                        + (item.eventCount() == 1 ? "registro" : "registros")));
                card.add(copy, BorderLayout.CENTER);
                JPanel badge = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                badge.setOpaque(false);
                JLabel count = UiKit.chip(Long.toString(item.eventCount()));
                badge.add(count);
                card.add(badge, BorderLayout.EAST);
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                cards.add(card);
            }
        }
        cards.revalidate();
        cards.repaint();
    }
}

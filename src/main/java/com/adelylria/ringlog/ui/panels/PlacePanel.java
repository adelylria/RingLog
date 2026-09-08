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

    private final CatalogRepository catalogRepository;
    private final JPanel cards;
    private final boolean canManage;
    private JButton newPlaceButton;

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
        JPanel page = UiKit.pagePanel();
        JButton action = null;
        if (canManage) {
            newPlaceButton = UiKit.primaryButton("Nuevo lugar");
            newPlaceButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newPlaceButton.addActionListener(event -> openNewPlaceDialog());
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
        add(page, BorderLayout.CENTER);
    }

    public void setMutationActionsEnabled(boolean enabled) {
        if (newPlaceButton != null) {
            newPlaceButton.setEnabled(enabled);
        }
    }

    private void openNewPlaceDialog() {
        PlaceForm form = new PlaceForm();
        JLabel status = UiKit.muted("El nombre es obligatorio; el resto es opcional.");
        JButton saveButton = UiKit.primaryButton("Guardar lugar");
        JButton cancelButton = UiKit.secondaryButton("Cancelar");
        JDialog dialog = createDialog("Nuevo lugar");

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
        saveButton.addActionListener(event -> savePlace(form, dialog, status, saveButton));
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

    private void savePlace(
            PlaceForm form,
            JDialog dialog,
            JLabel status,
            JButton saveButton
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
                return catalogRepository.insertPlace(input);
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
                            : "No se ha podido guardar el lugar.");
                }
            }
        }.execute();
    }

    public void refresh() {
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
        if (places.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    "Aún no hay lugares",
                    "Los lugares aparecerán cuando exista un registro asociado.",
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

        JPanel badge = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        badge.setOpaque(false);
        badge.add(UiKit.chip(Long.toString(item.eventCount())));
        card.add(badge, BorderLayout.EAST);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
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

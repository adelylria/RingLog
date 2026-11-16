package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.view.EnvironmentOption;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Editable choices; events keep their own text so catalog changes preserve history. */
public final class EnvironmentPanel extends JPanel {
    private final CatalogRepository repository;
    private final boolean canManage;
    private final Runnable onChanged;
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private final JPanel cards = UiKit.verticalScrollPanel();
    private final JComboBox<EnvironmentCategory> category = new JComboBox<>(EnvironmentCategory.values());
    private final JLabel catalogStatus = UiKit.muted("");
    private final List<JButton> mutationButtons = new ArrayList<>();
    private List<EnvironmentOption> options = List.of();
    private JButton newButton;
    private JButton saveButton;
    private JButton cancelButton;
    private JTextField nameField;
    private JPanel editor;
    private FormChanges changes;
    private Predicate<JOptionPane> discardConfirmation;
    private boolean mutationsEnabled = true;
    private boolean saving;
    private boolean loading;
    private int loadGeneration;

    public EnvironmentPanel(CatalogRepository repository, boolean canManage, Runnable onChanged) {
        super(new BorderLayout());
        this.repository = java.util.Objects.requireNonNull(repository);
        this.canManage = canManage;
        this.onChanged = java.util.Objects.requireNonNull(onChanged);
        views.setOpaque(false);
        views.add(createCatalog(), "catalog");
        add(views, BorderLayout.CENTER);
    }

    public void setDiscardConfirmation(Predicate<JOptionPane> confirmation) {
        discardConfirmation = java.util.Objects.requireNonNull(confirmation);
        if (changes != null) changes.setConfirmation(confirmation);
    }

    public boolean hasUnsavedChanges() { return changes != null && changes.hasChanges(); }
    public boolean confirmLeave() { return !saving && (changes == null || changes.confirmDiscard()); }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationsEnabled = enabled;
        updateAvailability();
    }

    private void updateAvailability() {
        boolean enabled = canManage && mutationsEnabled && !saving && !loading;
        if (newButton != null) newButton.setEnabled(enabled);
        mutationButtons.forEach(button -> button.setEnabled(enabled
                && !Boolean.TRUE.equals(button.getClientProperty("environmentBoundary"))));
        if (saveButton != null) saveButton.setEnabled(enabled);
        if (cancelButton != null) cancelButton.setEnabled(!saving);
        if (nameField != null) nameField.setEnabled(enabled);
        category.setEnabled(!saving);
    }

    private JPanel createCatalog() {
        JPanel page = UiKit.pagePanel();
        if (canManage) {
            newButton = UiKit.primaryButton("Nueva opción");
            newButton.setName("newEnvironmentOptionButton");
            newButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newButton.addActionListener(event -> showEditor(null));
        }
        page.add(new PageHeader("CATÁLOGOS", "Entorno",
                "Personaliza y ordena las opciones. Desactivarlas conserva los registros anteriores.",
                newButton), BorderLayout.NORTH);
        category.setName("environmentCategorySelector");
        category.getAccessibleContext().setAccessibleName("Campo del entorno");
        category.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        value instanceof EnvironmentCategory item ? item.label() : value,
                        index, selected, focus);
            }
        });
        category.addActionListener(event -> render());
        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filter.setOpaque(false);
        JLabel caption = UiKit.muted("Campo");
        caption.setLabelFor(category);
        filter.add(caption);
        filter.add(category);
        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.add(filter, BorderLayout.NORTH);
        body.add(UiKit.scrollPane(cards), BorderLayout.CENTER);
        page.add(body, BorderLayout.CENTER);
        catalogStatus.setName("environmentCatalogStatus");
        page.add(catalogStatus, BorderLayout.SOUTH);
        return page;
    }

    public void refresh() {
        if (!confirmLeave()) return;
        showCatalog();
        loadOptions("");
    }

    private void showCatalog() {
        changes = null;
        nameField = null;
        saveButton = null;
        cancelButton = null;
        viewLayout.show(views, "catalog");
        views.revalidate();
        views.repaint();
    }

    private void loadOptions(String completion) {
        int generation = ++loadGeneration;
        loading = true;
        updateAvailability();
        catalogStatus.setText("Cargando opciones…");
        new SwingWorker<List<EnvironmentOption>, Void>() {
            @Override protected List<EnvironmentOption> doInBackground() {
                return repository.findEnvironmentOptions();
            }
            @Override protected void done() {
                if (generation != loadGeneration) return;
                try {
                    options = List.copyOf(get());
                    render();
                    catalogStatus.setText(completion);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    options = List.of();
                    render();
                    catalogStatus.setText("Se ha interrumpido la carga de opciones.");
                } catch (ExecutionException exception) {
                    options = List.of();
                    render();
                    catalogStatus.setText("No se han podido cargar las opciones. Vuelve a abrir Entorno para reintentarlo.");
                } finally {
                    loading = false;
                    updateAvailability();
                }
            }
        }.execute();
    }

    private void render() {
        cards.removeAll();
        mutationButtons.clear();
        List<EnvironmentOption> visible = options.stream()
                .filter(option -> option.category() == category.getSelectedItem()).toList();
        for (int index = 0; index < visible.size(); index++) {
            EnvironmentOption option = visible.get(index);
            JPanel card = UiKit.sectionPanel();
            card.setName("environmentOptionCard-" + option.id());
            card.add(UiKit.valueLabel(option.name()), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            actions.setOpaque(false);
            actions.add(UiKit.chip(option.active() ? "Activo" : "Inactivo"));
            if (canManage) {
                JButton up = actionButton("↑", "moveEnvironmentOptionUp-" + option.id(),
                        "Subir " + option.name(), () -> move(option, -1));
                up.putClientProperty("environmentBoundary", index == 0);
                actions.add(up);
                JButton down = actionButton("↓", "moveEnvironmentOptionDown-" + option.id(),
                        "Bajar " + option.name(), () -> move(option, 1));
                down.putClientProperty("environmentBoundary", index == visible.size() - 1);
                actions.add(down);
                actions.add(actionButton(option.active() ? "Desactivar" : "Activar",
                        "toggleEnvironmentOptionActive-" + option.id(),
                        option.active() ? "Ocultar en nuevas selecciones; conserva el historial."
                                : "Volver a ofrecer en nuevas selecciones.",
                        () -> changeOption(() -> repository.setEnvironmentOptionActive(option.id(), !option.active()),
                                option.active() ? "Opción desactivada; el historial se conserva." : "Opción activada.")));
                JButton edit = actionButton("", "editEnvironmentOption-" + option.id(),
                        "Editar " + option.name(), () -> showEditor(option));
                edit.setIcon(new NavigationIcon(NavigationIcon.Kind.EDIT));
                actions.add(edit);
            }
            card.add(actions, BorderLayout.EAST);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            cards.add(card);
            cards.add(Box.createVerticalStrut(8));
        }
        if (visible.isEmpty()) cards.add(UiKit.muted("Aún no hay opciones para este campo."));
        updateAvailability();
        cards.revalidate();
        cards.repaint();
    }

    private JButton actionButton(String text, String name, String description, Runnable action) {
        JButton button = UiKit.secondaryButton(text);
        button.setName(name);
        button.setMargin(new Insets(4, 8, 4, 8));
        button.putClientProperty("JButton.minimumWidth", 0);
        button.setToolTipText(description);
        button.getAccessibleContext().setAccessibleName(description);
        button.addActionListener(event -> action.run());
        mutationButtons.add(button);
        return button;
    }

    private void move(EnvironmentOption option, int direction) {
        changeOption(() -> repository.moveEnvironmentOption(option.id(), direction), "Orden actualizado.");
    }

    private void changeOption(Runnable mutation, String completion) {
        if (!canManage || !mutationsEnabled || saving || loading || changes != null) return;
        saving = true;
        loadGeneration++;
        updateAvailability();
        catalogStatus.setText("Guardando…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() { mutation.run(); return null; }
            @Override protected void done() {
                try {
                    get();
                    onChanged.run();
                    loadOptions(completion);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    catalogStatus.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    catalogStatus.setText(errorMessage(exception));
                } finally {
                    saving = false;
                    updateAvailability();
                }
            }
        }.execute();
    }

    private void showEditor(EnvironmentOption option) {
        if (!canManage || !mutationsEnabled || loading || !confirmLeave()) return;
        if (editor != null) views.remove(editor);
        EnvironmentCategory selected = option == null ? (EnvironmentCategory) category.getSelectedItem() : option.category();
        nameField = new JTextField(option == null ? "" : option.name(), 28);
        nameField.setName("environmentOptionName");
        nameField.getAccessibleContext().setAccessibleName("Nombre de la opción de " + selected.label());
        JPanel form = new JPanel(new BorderLayout(0, 6));
        form.setOpaque(false);
        JLabel caption = UiKit.muted(selected.label() + " · Nombre *");
        caption.setLabelFor(nameField);
        form.add(caption, BorderLayout.NORTH);
        form.add(nameField, BorderLayout.CENTER);
        changes = new FormChanges(form);
        if (discardConfirmation != null) changes.setConfirmation(discardConfirmation);
        changes.markClean();
        JLabel status = UiKit.muted("El nombre es obligatorio.");
        status.setName("environmentOptionStatus");
        cancelButton = UiKit.secondaryButton("Cancelar");
        cancelButton.setName("cancelEnvironmentOptionEditorButton");
        cancelButton.addActionListener(event -> { if (confirmLeave()) showCatalog(); });
        saveButton = UiKit.primaryButton(option == null ? "Guardar opción" : "Guardar cambios");
        saveButton.setName("saveEnvironmentOptionButton");
        saveButton.addActionListener(event -> saveOption(option, selected, status));
        nameField.addActionListener(event -> saveOption(option, selected, status));
        editor = UiKit.pagePanel();
        editor.setName("environmentOptionEditorView");
        editor.add(new PageHeader("CATÁLOGO DE ENTORNO", option == null ? "Nueva opción de " + selected.label() : "Editar opción",
                "Los valores guardados en registros anteriores se conservan.", cancelButton), BorderLayout.NORTH);
        JPanel card = UiKit.sectionPanel();
        card.add(form, BorderLayout.CENTER);
        JPanel column = UiKit.verticalScrollPanel();
        column.add(card);
        editor.add(UiKit.scrollPane(column), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        footer.add(saveButton, BorderLayout.EAST);
        editor.add(footer, BorderLayout.SOUTH);
        views.add(editor, "editor");
        viewLayout.show(views, "editor");
        updateAvailability();
        views.revalidate();
        views.repaint();
        nameField.requestFocusInWindow();
    }

    private void saveOption(EnvironmentOption option, EnvironmentCategory selected, JLabel status) {
        if (!canManage || !mutationsEnabled || saving || loading) return;
        String input = nameField.getText();
        if (input.isBlank()) { status.setText("Indica el nombre de la opción."); nameField.requestFocusInWindow(); return; }
        saving = true;
        loadGeneration++;
        changes.suspendEditing();
        updateAvailability();
        status.setText("Guardando opción…");
        new SwingWorker<Long, Void>() {
            @Override protected Long doInBackground() {
                return repository.saveEnvironmentOption(option == null ? null : option.id(), selected, input);
            }
            @Override protected void done() {
                changes.resumeEditing();
                try {
                    get();
                    changes.markClean();
                    showCatalog();
                    onChanged.run();
                    loadOptions("Opción guardada.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    status.setText(errorMessage(exception));
                } finally {
                    saving = false;
                    updateAvailability();
                }
            }
        }.execute();
    }

    private static String errorMessage(ExecutionException exception) {
        return exception.getCause() instanceof IllegalArgumentException validation
                ? validation.getMessage() : "No se ha podido guardar la opción. Inténtalo de nuevo.";
    }
}

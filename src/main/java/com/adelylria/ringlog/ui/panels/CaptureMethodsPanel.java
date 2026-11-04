package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.view.CaptureMethodOption;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.EmptyStatePanel;
import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.components.NavigationIcon;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.TwoColumnCardPanel;
import com.adelylria.ringlog.ui.theme.UiKit;

/** A reusable capture-method catalog with the same inline editing flow as places. */
public final class CaptureMethodsPanel extends JPanel {

    private static final String CATALOG_VIEW = "catalog";
    private static final String EDITOR_VIEW = "editor";

    private final CatalogRepository catalogRepository;
    private final boolean canManage;
    private final Runnable onChanged;
    private final JPanel cards = new TwoColumnCardPanel(14);
    private final CardLayout viewLayout = new CardLayout();
    private final JPanel views = new JPanel(viewLayout);
    private final List<JButton> mutationButtons = new ArrayList<>();
    private final JLabel catalogStatus = UiKit.muted("");
    private JButton newMethodButton;
    private JButton currentSaveButton;
    private JButton currentCancelButton;
    private JTextField currentName;
    private JPanel currentEditor;
    private FormChanges changes;
    private Predicate<JOptionPane> discardConfirmation;
    private boolean mutationActionsEnabled = true;
    private boolean saving;
    private int catalogLoadGeneration;

    public CaptureMethodsPanel(CatalogRepository catalogRepository) {
        this(catalogRepository, true, () -> { });
    }

    public CaptureMethodsPanel(CatalogRepository catalogRepository, boolean canManage, Runnable onChanged) {
        super(new BorderLayout());
        this.catalogRepository = java.util.Objects.requireNonNull(catalogRepository);
        this.canManage = canManage;
        this.onChanged = java.util.Objects.requireNonNull(onChanged);
        views.setOpaque(false);
        views.add(createCatalogPage(), CATALOG_VIEW);
        add(views, BorderLayout.CENTER);
    }

    public void setDiscardConfirmation(Predicate<JOptionPane> confirmation) {
        discardConfirmation = java.util.Objects.requireNonNull(confirmation);
        if (changes != null) changes.setConfirmation(confirmation);
    }

    public boolean hasUnsavedChanges() {
        return changes != null && changes.hasChanges();
    }

    public boolean confirmLeave() {
        if (saving) return false;
        return changes == null || changes.confirmDiscard();
    }

    public void setMutationActionsEnabled(boolean enabled) {
        mutationActionsEnabled = enabled;
        updateAvailability();
    }

    private void updateAvailability() {
        boolean enabled = canManage && mutationActionsEnabled && !saving;
        if (newMethodButton != null) newMethodButton.setEnabled(enabled);
        mutationButtons.forEach(button -> button.setEnabled(enabled));
        if (currentSaveButton != null) currentSaveButton.setEnabled(enabled);
        if (currentCancelButton != null) currentCancelButton.setEnabled(!saving);
        if (currentName != null) currentName.setEnabled(enabled);
    }

    private JPanel createCatalogPage() {
        JPanel page = UiKit.pagePanel();
        JButton action = null;
        if (canManage) {
            newMethodButton = UiKit.primaryButton("Nuevo método");
            newMethodButton.setName("newCaptureMethodButton");
            newMethodButton.setIcon(new NavigationIcon(NavigationIcon.Kind.ADD));
            newMethodButton.addActionListener(event -> showEditor(null));
            action = newMethodButton;
        }
        page.add(new PageHeader(
                "CATÁLOGO VIVO",
                "Métodos de captura",
                "Desactiva los métodos que ya no uses; sus registros anteriores se conservan.",
                action
        ), BorderLayout.NORTH);
        cards.setOpaque(false);
        page.add(UiKit.scrollPane(cards), BorderLayout.CENTER);
        catalogStatus.setName("captureMethodCatalogStatus");
        page.add(catalogStatus, BorderLayout.SOUTH);
        return page;
    }

    private void showEditor(CaptureMethodOption method) {
        if (!canManage || !mutationActionsEnabled || !confirmLeave()) return;
        if (currentEditor != null) views.remove(currentEditor);
        currentEditor = createEditorPage(method);
        views.add(currentEditor, EDITOR_VIEW);
        viewLayout.show(views, EDITOR_VIEW);
        views.revalidate();
        views.repaint();
        currentName.requestFocusInWindow();
    }

    private JPanel createEditorPage(CaptureMethodOption method) {
        boolean editing = method != null;
        JTextField name = new JTextField(editing ? method.name() : "", 28);
        name.setName("captureMethodName");
        name.getAccessibleContext().setAccessibleName("Nombre del método de captura");
        name.putClientProperty("JTextField.placeholderText", "Ej. FILATS");
        JPanel form = new JPanel(new BorderLayout(0, 6));
        form.setOpaque(false);
        JLabel caption = UiKit.muted("Nombre *");
        caption.setLabelFor(name);
        form.add(caption, BorderLayout.NORTH);
        form.add(name, BorderLayout.CENTER);
        changes = new FormChanges(form);
        if (discardConfirmation != null) changes.setConfirmation(discardConfirmation);
        changes.markClean();
        currentName = name;

        JLabel status = UiKit.muted("El nombre es obligatorio.");
        status.setName("captureMethodStatus");
        JButton cancel = UiKit.secondaryButton("Cancelar");
        cancel.setName("cancelCaptureMethodEditorButton");
        cancel.addActionListener(event -> { if (confirmLeave()) showCatalog(); });
        currentCancelButton = cancel;
        JButton save = UiKit.primaryButton(editing ? "Guardar cambios" : "Guardar método");
        save.setName("saveCaptureMethodButton");
        save.addActionListener(event -> saveMethod(name, status, editing ? method.id() : null));
        name.addActionListener(event -> saveMethod(name, status, editing ? method.id() : null));
        currentSaveButton = save;

        JPanel page = UiKit.pagePanel();
        page.setName("captureMethodEditorView");
        page.add(new PageHeader(
                editing ? "EDITAR MÉTODO" : "NUEVO MÉTODO",
                editing ? method.name() : "Añadir método de captura",
                editing
                        ? "Actualiza su nombre sin perder los registros asociados."
                        : "Guárdalo una vez para reutilizarlo en tus registros.",
                cancel
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
        footer.add(save, BorderLayout.EAST);
        page.add(footer, BorderLayout.SOUTH);
        updateAvailability();
        return page;
    }

    private void saveMethod(JTextField name, JLabel status, Long methodId) {
        if (!canManage || !mutationActionsEnabled || saving) return;
        String input = name.getText();
        if (input.isBlank()) {
            status.setText("Indica el nombre del método de captura.");
            name.requestFocusInWindow();
            return;
        }
        saving = true;
        catalogLoadGeneration++;
        changes.suspendEditing();
        updateAvailability();
        status.setText("Guardando método…");
        new SwingWorker<Long, Void>() {
            @Override protected Long doInBackground() {
                return catalogRepository.saveCaptureMethod(methodId, input);
            }

            @Override protected void done() {
                changes.resumeEditing();
                try {
                    get();
                    changes.markClean();
                    showCatalog();
                    onChanged.run();
                    refreshCards("Método guardado.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    status.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    status.setText(cause instanceof IllegalArgumentException
                            ? cause.getMessage() : "No se ha podido guardar el método. Inténtalo de nuevo.");
                } finally {
                    saving = false;
                    updateAvailability();
                }
            }
        }.execute();
    }

    private void toggleActive(CaptureMethodOption method) {
        if (!canManage || !mutationActionsEnabled || saving || changes != null) return;
        saving = true;
        catalogLoadGeneration++;
        updateAvailability();
        catalogStatus.setText("Guardando…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                catalogRepository.setCaptureMethodActive(method.id(), !method.active());
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                    onChanged.run();
                    refreshCards(method.active()
                            ? "Método desactivado; los registros anteriores se conservan." : "Método activado.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    catalogStatus.setText("Se ha interrumpido el guardado.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    catalogStatus.setText(cause instanceof IllegalArgumentException
                            ? cause.getMessage() : "No se ha podido cambiar el método. Inténtalo de nuevo.");
                } finally {
                    saving = false;
                    updateAvailability();
                }
            }
        }.execute();
    }

    public void refresh() {
        if (!confirmLeave()) return;
        showCatalog();
        refreshCards("");
    }

    private void showCatalog() {
        changes = null;
        currentName = null;
        currentSaveButton = null;
        currentCancelButton = null;
        viewLayout.show(views, CATALOG_VIEW);
        views.revalidate();
        views.repaint();
    }

    private void refreshCards(String completionMessage) {
        int generation = ++catalogLoadGeneration;
        cards.removeAll();
        mutationButtons.clear();
        cards.add(UiKit.muted("Cargando métodos…"));
        cards.revalidate();
        cards.repaint();
        catalogStatus.setText("");
        new SwingWorker<List<CaptureMethodOption>, Void>() {
            @Override protected List<CaptureMethodOption> doInBackground() {
                return catalogRepository.findCaptureMethods();
            }

            @Override protected void done() {
                if (generation != catalogLoadGeneration) return;
                try {
                    render(get());
                    catalogStatus.setText(completionMessage);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showCatalogError("Se ha interrumpido la carga de métodos.");
                } catch (ExecutionException exception) {
                    showCatalogError("No se han podido cargar los métodos. Vuelve a abrir esta sección para reintentarlo.");
                }
            }
        }.execute();
    }

    private void showCatalogError(String message) {
        cards.removeAll();
        mutationButtons.clear();
        cards.add(UiKit.muted(message));
        cards.revalidate();
        cards.repaint();
    }

    private void render(List<CaptureMethodOption> methods) {
        cards.removeAll();
        mutationButtons.clear();
        if (methods.isEmpty()) {
            cards.add(new EmptyStatePanel(
                    "Aún no hay métodos de captura",
                    "Añade un método para reutilizarlo al crear tus registros.",
                    null
            ));
        } else {
            methods.forEach(method -> cards.add(methodCard(method)));
        }
        updateAvailability();
        cards.revalidate();
        cards.repaint();
    }

    private JPanel methodCard(CaptureMethodOption method) {
        JPanel card = UiKit.sectionPanel();
        card.setName("captureMethodCard-" + method.id());
        card.add(UiKit.valueLabel(method.name()), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(UiKit.chip(method.active() ? "Activo" : "Inactivo"));
        if (canManage) {
            JButton toggle = UiKit.secondaryButton(method.active() ? "Desactivar" : "Activar");
            toggle.setMargin(new Insets(4, 8, 4, 8));
            toggle.putClientProperty("JButton.minimumWidth", 0);
            toggle.setPreferredSize(new Dimension(toggle.getPreferredSize().width, 32));
            toggle.setName("toggleCaptureMethodActive-" + method.id());
            String description = method.active()
                    ? "Dejar de ofrecer " + method.name() + " en nuevos registros; los anteriores se conservan."
                    : "Volver a ofrecer " + method.name() + " en nuevos registros.";
            toggle.setToolTipText(description);
            toggle.getAccessibleContext().setAccessibleName(
                    (method.active() ? "Desactivar " : "Activar ") + method.name());
            toggle.getAccessibleContext().setAccessibleDescription(description);
            toggle.addActionListener(event -> toggleActive(method));
            mutationButtons.add(toggle);
            actions.add(toggle);
            JButton edit = editButton("Editar " + method.name());
            edit.setName("editCaptureMethod-" + method.id());
            edit.addActionListener(event -> showEditor(method));
            mutationButtons.add(edit);
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

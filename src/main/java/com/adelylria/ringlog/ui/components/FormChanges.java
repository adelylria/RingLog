package com.adelylria.ringlog.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JCheckBox;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.text.JTextComponent;

/** Compares raw draft values, including invalid input, without writing or validating the form. EDT only. */
public final class FormChanges {
    private final Container form;
    private final Supplier<?> extra;
    private Map<Component, Object> baseline = new LinkedHashMap<>();
    private Object baselineExtra;
    private final Map<Component, Boolean> suspendedControls = new LinkedHashMap<>();
    private Predicate<JOptionPane> confirmation = this::showConfirmation;

    public FormChanges(Container form) { this(form, () -> null); }
    public FormChanges(Container form, Supplier<?> extra) {
        this.form = Objects.requireNonNull(form);
        this.extra = Objects.requireNonNull(extra);
    }
    public void markClean() { baseline = snapshot(); baselineExtra = extra.get(); }
    public boolean hasChanges() { return !baseline.equals(snapshot()) || !Objects.equals(baselineExtra, extra.get()); }
    public boolean isChanged(Component input) { return !Objects.equals(baseline.get(input), value(input)); }
    public void acceptValue(Component input) { baseline.put(input, value(input)); }
    public void setConfirmation(Predicate<JOptionPane> confirmation) { this.confirmation = Objects.requireNonNull(confirmation); }

    /** Freeze only interaction; retain the original enabled state and the dirty draft on failure. */
    public void suspendEditing() {
        if (suspendedControls.isEmpty()) suspend(form);
    }

    public void resumeEditing() {
        suspendedControls.forEach(Component::setEnabled);
        suspendedControls.clear();
    }

    private void suspend(Component component) {
        if (component instanceof AbstractButton || component instanceof JComboBox<?>
                || component instanceof JTextComponent || component instanceof javax.swing.JSpinner) {
            suspendedControls.put(component, component.isEnabled());
            component.setEnabled(false);
            return;
        }
        if (component instanceof Container parent) for (Component child : parent.getComponents()) suspend(child);
    }

    public boolean confirmDiscard() {
        if (!hasChanges()) return true;
        JOptionPane pane = new JOptionPane(
                "Tienes cambios sin guardar.\n¿Quieres salir y descartarlos?\n\nPara conservarlos, sigue editando y pulsa Guardar.",
                JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                new Object[]{"Seguir editando", "Descartar cambios"}, "Seguir editando");
        if (!confirmation.test(pane)) return false;
        markClean();
        return true;
    }
    private boolean showConfirmation(JOptionPane pane) {
        var dialog = pane.createDialog(form, "Cambios sin guardar");
        try { dialog.setVisible(true); } finally { dialog.dispose(); }
        return "Descartar cambios".equals(pane.getValue());
    }
    private Map<Component, Object> snapshot() {
        Map<Component, Object> state = new LinkedHashMap<>();
        collect(form, state);
        return state;
    }
    private static void collect(Component component, Map<Component, Object> state) {
        if (component instanceof JComboBox<?> || component instanceof JCheckBox
                || component instanceof JTextComponent text && text.isEditable()) {
            state.put(component, value(component));
            return; // Do not include a combo's internal editor or renderer.
        }
        if (component instanceof Container parent) for (Component child : parent.getComponents()) collect(child, state);
    }
    private static Object value(Component component) {
        if (component instanceof JTextComponent text) return text.getText();
        if (component instanceof JComboBox<?> combo) return combo.getSelectedItem();
        if (component instanceof JCheckBox check) return check.isSelected();
        throw new IllegalArgumentException("Not an editable form control");
    }
}

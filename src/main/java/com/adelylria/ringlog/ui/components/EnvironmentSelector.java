package com.adelylria.ringlog.ui.components;

import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.view.EnvironmentOption;

/** Selects current catalog values while retaining the text of an older event. EDT only. */
public final class EnvironmentSelector extends JComboBox<EnvironmentSelector.Choice> {
    private final EnvironmentCategory category;
    private List<String> activeNames = List.of();
    private String originalValue;

    public EnvironmentSelector(EnvironmentCategory category, String name) {
        this.category = Objects.requireNonNull(category);
        setName(name);
        setEditable(false);
        setMaximumRowCount(12);
        getAccessibleContext().setAccessibleName(category.label());
        setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                Choice choice = (Choice) value;
                setText(choice == null ? "Sin indicar" : choice.toString());
                setToolTipText(getText());
                return this;
            }
        });
        addActionListener(event -> updateTooltip());
        rebuild(null);
    }

    /** Loads a new event's value, discarding fallback values from the previous event. */
    public void setValue(String value) {
        originalValue = normalized(value);
        rebuild(originalValue);
    }

    public String value() {
        Choice choice = (Choice) getSelectedItem();
        return choice == null ? "" : choice.value();
    }

    /** Refreshing the catalog never replaces an event's selected text. */
    public void setOptions(List<EnvironmentOption> options) {
        List<String> names = options.stream()
                .filter(option -> option.category() == category && option.active())
                .map(EnvironmentOption::name).distinct().toList();
        if (activeNames.equals(names)) {
            updateTooltip();
            return;
        }
        activeNames = names;
        rebuild(normalized(value()));
    }

    private void rebuild(String selectedValue) {
        LinkedHashMap<String, Choice> choices = new LinkedHashMap<>();
        for (String name : activeNames) choices.putIfAbsent(name, new Choice(name, false));
        if (originalValue != null) choices.putIfAbsent(originalValue, new Choice(originalValue, true));
        if (selectedValue != null) choices.putIfAbsent(selectedValue, new Choice(selectedValue, true));
        DefaultComboBoxModel<Choice> model = new DefaultComboBoxModel<>();
        model.addElement(null);
        choices.values().forEach(model::addElement);
        model.setSelectedItem(selectedValue == null ? null : choices.get(selectedValue));
        setModel(model);
        updateTooltip();
    }

    private Choice findValue(String value) {
        for (int index = 1; index < getItemCount(); index++) {
            Choice choice = getItemAt(index);
            if (choice.value().equals(value)) return choice;
        }
        return null;
    }

    public boolean selectValue(String value) {
        String normalized = normalized(value);
        if (normalized == null) {
            setSelectedIndex(0);
            return true;
        }
        Choice choice = findValue(normalized);
        if (choice == null) return false;
        setSelectedItem(choice);
        return true;
    }

    private void updateTooltip() {
        Choice choice = (Choice) getSelectedItem();
        String tooltip = choice == null ? "Opciones gestionadas en Catálogos → Entorno"
                : choice.historical()
                        ? choice.value() + " · Valor anterior: puedes conservarlo o elegir una opción del catálogo."
                        : choice.value();
        if (!Objects.equals(getToolTipText(), tooltip)) setToolTipText(tooltip);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Choice(String value, boolean historical) {
        public Choice { Objects.requireNonNull(value); }
        @Override public String toString() { return value + (historical ? " (anterior)" : ""); }
        // Catalog refreshes may change presentation without changing the draft's stored value.
        @Override public boolean equals(Object other) {
            return other instanceof Choice choice && value.equals(choice.value);
        }
        @Override public int hashCode() { return value.hashCode(); }
    }
}

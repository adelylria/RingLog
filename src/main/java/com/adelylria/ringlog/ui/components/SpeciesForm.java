package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JPanel;
import javax.swing.JTextField;

import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.adelylria.ringlog.ui.theme.UiKit;

public class SpeciesForm extends JPanel {

    private final JTextField scientificNameField = new JTextField();
    private final JTextField commonNameField = new JTextField();
    private final JTextField codeField = new JTextField();

    public SpeciesForm() {
        super(new GridBagLayout());
        setOpaque(false);
        configureField(
                scientificNameField,
                "speciesScientificName",
                "Nombre científico, obligatorio",
                "Ej. Sylvia atricapilla"
        );
        configureField(
                commonNameField,
                "speciesCommonName",
                "Nombre común",
                "Ej. Curruca capirotada"
        );
        configureField(codeField, "speciesCode", "Código", "Opcional");
        addField(0, "Nombre científico *", scientificNameField);
        addField(1, "Nombre común", commonNameField);
        addField(2, "Código", codeField);
    }

    public SpeciesForm(SpeciesSummary species) {
        this();
        if (species == null) {
            throw new IllegalArgumentException("Falta la especie que quieres editar.");
        }
        scientificNameField.setText(text(species.scientificName()));
        commonNameField.setText(text(species.commonName()));
        codeField.setText(text(species.code()));
    }

    public SpeciesInput input() {
        if (scientificNameField.getText().isBlank()) {
            throw new IllegalArgumentException("Indica el nombre científico.");
        }
        return new SpeciesInput(
                codeField.getText(),
                scientificNameField.getText(),
                commonNameField.getText()
        );
    }

    private void addField(int row, String label, JTextField field) {
        JPanel item = new JPanel(new BorderLayout(0, 5));
        item.setOpaque(false);
        javax.swing.JLabel fieldLabel = UiKit.muted(label);
        fieldLabel.setLabelFor(field);
        item.add(fieldLabel, BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, row == 2 ? 0 : 12, 0);
        add(item, constraints);
    }

    private static void configureField(
            JTextField field,
            String name,
            String accessibleName,
            String placeholder
    ) {
        field.setName(name);
        field.getAccessibleContext().setAccessibleName(accessibleName);
        field.putClientProperty("JTextField.placeholderText", placeholder);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}

package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.ui.theme.UiKit;

public class PlaceForm extends JPanel {

    private final JTextField nameField = new JTextField();
    private final JTextField localityField = new JTextField();
    private final JTextField autonomousCommunityField = new JTextField();
    private final JTextField countryField = new JTextField();
    private final JTextField latitudeField = new JTextField();
    private final JTextField longitudeField = new JTextField();
    private final JTextArea notesArea = new JTextArea(4, 28);
    private final JCheckBox favoriteCheck = new JCheckBox("Marcar como favorito");
    private final JCheckBox defaultCheck = new JCheckBox("Usar como lugar predeterminado");

    public PlaceForm() {
        super(new GridBagLayout());
        setOpaque(false);
        configureField(nameField, "placeName", "Nombre del lugar, obligatorio",
                "Ej. Laguna norte");
        configureField(localityField, "placeLocality", "Localidad",
                "Ej. Pollença");
        configureField(autonomousCommunityField, "placeAutonomousCommunity",
                "Comunidad autónoma", "Ej. Illes Balears");
        configureField(countryField, "placeCountry", "País", "Ej. España");
        configureField(latitudeField, "placeLatitude", "Latitud",
                "Ej. 39,7500");
        configureField(longitudeField, "placeLongitude", "Longitud",
                "Ej. 2,8000");
        notesArea.setName("placeNotes");
        notesArea.getAccessibleContext().setAccessibleName("Notas del lugar");
        notesArea.putClientProperty(
                "JTextArea.placeholderText",
                "Acceso, referencias u otra información útil"
        );
        favoriteCheck.setName("placeFavorite");
        defaultCheck.setName("placeDefault");
        favoriteCheck.setOpaque(false);
        defaultCheck.setOpaque(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        addField(0, "Nombre *", nameField);
        addField(1, "Localidad", localityField);
        addField(2, "Comunidad autónoma", autonomousCommunityField);
        addField(3, "País", countryField);
        addCoordinates();
        addField(5, "Notas", new JScrollPane(notesArea));
        addField(6, "", favoriteCheck);
        addField(7, "", defaultCheck);
    }

    public PlaceForm(PlaceSummary place) {
        this();
        if (place == null) {
            throw new IllegalArgumentException("Falta el lugar que quieres editar.");
        }
        nameField.setText(text(place.name()));
        localityField.setText(text(place.locality()));
        autonomousCommunityField.setText(text(place.autonomousCommunity()));
        countryField.setText(text(place.country()));
        latitudeField.setText(number(place.latitude()));
        longitudeField.setText(number(place.longitude()));
        notesArea.setText(text(place.notes()));
        favoriteCheck.setSelected(place.favorite());
        defaultCheck.setSelected(place.isDefault());
    }

    public PlaceInput input() {
        if (nameField.getText().isBlank()) {
            throw new IllegalArgumentException("Indica el nombre del lugar.");
        }
        return new PlaceInput(
                nameField.getText(),
                localityField.getText(),
                autonomousCommunityField.getText(),
                countryField.getText(),
                optionalNumber(latitudeField.getText(), "La latitud debe ser un número válido."),
                optionalNumber(longitudeField.getText(), "La longitud debe ser un número válido."),
                notesArea.getText(),
                favoriteCheck.isSelected(),
                defaultCheck.isSelected()
        );
    }

    private void addCoordinates() {
        JPanel coordinates = new JPanel(new GridBagLayout());
        coordinates.setOpaque(false);
        addCoordinateField(coordinates, 0, "Latitud", latitudeField, 0, 8);
        addCoordinateField(coordinates, 1, "Longitud", longitudeField, 8, 0);
        addField(4, "Coordenadas", coordinates);
    }

    private void addCoordinateField(
            JPanel parent,
            int column,
            String label,
            JTextField field,
            int left,
            int right
    ) {
        JPanel item = new JPanel(new BorderLayout(0, 5));
        item.setOpaque(false);
        javax.swing.JLabel fieldLabel = UiKit.muted(label);
        fieldLabel.setLabelFor(field);
        item.add(fieldLabel, BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.weightx = 0.5;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, left, 0, right);
        parent.add(item, constraints);
    }

    private void addField(int row, String label, java.awt.Component field) {
        JPanel item = new JPanel(new BorderLayout(0, label.isBlank() ? 0 : 5));
        item.setOpaque(false);
        if (!label.isBlank()) {
            javax.swing.JLabel fieldLabel = UiKit.muted(label);
            fieldLabel.setLabelFor(field);
            item.add(fieldLabel, BorderLayout.NORTH);
        }
        item.add(field, BorderLayout.CENTER);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, row >= 6 ? 6 : 12, 0);
        add(item, constraints);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String number(Double value) {
        return value == null ? "" : Double.toString(value);
    }

    private static Double optionalNumber(String value, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message);
        }
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
}

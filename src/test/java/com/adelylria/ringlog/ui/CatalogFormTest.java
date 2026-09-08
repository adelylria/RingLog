package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.ui.components.PlaceForm;
import com.adelylria.ringlog.ui.components.SpeciesForm;
import com.adelylria.ringlog.ui.theme.ThemeManager;

public final class CatalogFormTest {

    private CatalogFormTest() {
    }

    public static void formsGuideInputAndAcceptDecimalComma() throws Exception {
        ThemeManager.setupLightTheme();
        SwingUtilities.invokeAndWait(() -> {
            SpeciesForm speciesForm = new SpeciesForm();
            JTextField scientificName = field(speciesForm, "speciesScientificName");
            JTextField commonName = field(speciesForm, "speciesCommonName");
            JTextField code = field(speciesForm, "speciesCode");

            require("Ej. Sylvia atricapilla".equals(
                            scientificName.getClientProperty("JTextField.placeholderText")),
                    "The scientific-name field should explain the expected value");
            require(scientificName.getAccessibleContext().getAccessibleName() != null,
                    "The required species field should have an accessible name");

            scientificName.setText("Sylvia atricapilla");
            commonName.setText("Curruca capirotada");
            code.setText("SYL-001");
            SpeciesInput species = speciesForm.input();
            require("Sylvia atricapilla".equals(species.scientificName()),
                    "The species form should collect its visible values");

            PlaceForm placeForm = new PlaceForm();
            JTextField placeName = field(placeForm, "placeName");
            JTextField latitude = field(placeForm, "placeLatitude");
            JTextField longitude = field(placeForm, "placeLongitude");
            require("Ej. Laguna norte".equals(
                            placeName.getClientProperty("JTextField.placeholderText")),
                    "The place name should include a helpful example");

            placeName.setText("Laguna norte");
            latitude.setText("39,75");
            longitude.setText("2.80");
            PlaceInput place = placeForm.input();
            require(Double.valueOf(39.75).equals(place.latitude()),
                    "Latitude should accept the decimal comma used in Spanish");
            require(Double.valueOf(2.80).equals(place.longitude()),
                    "Longitude should accept a decimal point too");
        });
    }

    private static JTextField field(Container root, String name) {
        Component component = find(root, name);
        require(component instanceof JTextField,
                "Missing named text field: " + name);
        return (JTextField) component;
    }

    private static Component find(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent swingComponent
                    && name.equals(swingComponent.getName())) {
                return component;
            }
            if (component instanceof Container nested) {
                Component found = find(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        formsGuideInputAndAcceptDecimalComma();
        System.out.println("CatalogFormTest: PASS");
    }
}

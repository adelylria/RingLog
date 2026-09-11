package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.SpeciesSummary;
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

            SpeciesForm speciesEditForm = new SpeciesForm(new SpeciesSummary(
                    8L,
                    "Curruca capirotada",
                    "SYL-001",
                    "Sylvia atricapilla",
                    "Curruca capirotada",
                    14L
            ));
            SpeciesInput editedSpecies = speciesEditForm.input();
            require("SYL-001".equals(editedSpecies.code())
                            && "Sylvia atricapilla".equals(editedSpecies.scientificName())
                            && "Curruca capirotada".equals(editedSpecies.commonName()),
                    "The edit form must preload every species field");

            PlaceForm placeForm = new PlaceForm();
            JTextField placeName = field(placeForm, "placeName");
            JTextField autonomousCommunity = field(placeForm, "placeAutonomousCommunity");
            JTextField country = field(placeForm, "placeCountry");
            JTextField latitude = field(placeForm, "placeLatitude");
            JTextField longitude = field(placeForm, "placeLongitude");
            require("Ej. Laguna norte".equals(
                            placeName.getClientProperty("JTextField.placeholderText")),
                    "The place name should include a helpful example");

            placeName.setText("Laguna norte");
            autonomousCommunity.setText("Illes Balears");
            country.setText("España");
            latitude.setText("39,75");
            longitude.setText("2.80");
            PlaceInput place = placeForm.input();
            require(Double.valueOf(39.75).equals(place.latitude()),
                    "Latitude should accept the decimal comma used in Spanish");
            require(Double.valueOf(2.80).equals(place.longitude()),
                    "Longitude should accept a decimal point too");
            require("Illes Balears".equals(place.autonomousCommunity())
                            && "España".equals(place.country()),
                    "The place form should collect its administrative location");

            PlaceForm editForm = new PlaceForm(new com.adelylria.ringlog.model.view.PlaceSummary(
                    7L, "Els Rafals", "Pollença", "Illes Balears", "España",
                    39.85, 2.98, "Referencia", true, false, 12L
            ));
            PlaceInput edited = editForm.input();
            require("Els Rafals".equals(edited.name())
                            && "Illes Balears".equals(edited.autonomousCommunity())
                            && "España".equals(edited.country())
                            && edited.favorite(),
                    "The edit form must preload every place field");
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

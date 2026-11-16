package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.components.TemperatureInput;

public final class TemperatureInputTest {

    private TemperatureInputTest() {
    }

    public static void optionalValuesIncludeNegativeTemperaturesAndZero() {
        TemperatureInput input = new TemperatureInput();
        require(input.value() == null && input.validateValue(),
                "An unspecified ambient temperature must remain null");
        for (int value : new int[] {-99, -12, -1, 0, 1, 18, 99}) {
            input.setValue(value);
            require(Integer.valueOf(value).equals(input.value()) && input.validateValue(),
                    "The complete signed two-digit range must remain valid: " + value);
        }
        input.setValue(null);
        require(input.field().getText().isEmpty() && input.value() == null,
                "Clearing an optional temperature must not invent zero");
        input.setValue(18);
        for (int invalid : new int[] {-100, 100, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            requireInvalid(() -> input.setValue(invalid),
                    "Programmatic values outside -99..99 must fail");
            require(Integer.valueOf(18).equals(input.value()),
                    "A rejected programmatic value must not overwrite the current temperature");
        }
    }

    public static void typingAndPastingPreserveTheWholeDraft() {
        TemperatureInput input = new TemperatureInput();
        JTextField field = input.field();
        field.replaceSelection("-");
        require("-".equals(field.getText()), "Typing the minus sign must be possible before its digits");
        require(!input.validateValue() && !input.errorMessage().isBlank(),
                "A minus sign alone is a draft, not a saved temperature");
        requireInvalid(input::value, "An unfinished negative temperature must not save");
        require("-".equals(field.getText()), "Validation must preserve unfinished input for correction");
        field.setCaretPosition(field.getDocument().getLength());
        field.replaceSelection("9");
        require(Integer.valueOf(-9).equals(input.value()), "Typing digits after a minus must work");
        field.replaceSelection("9");
        require(Integer.valueOf(-99).equals(input.value()), "Two negative digits must fit");
        replaceAll(field, "18");
        require(Integer.valueOf(18).equals(input.value()),
                "Replacing a selected negative number with a positive pasted value must work");
        replaceAll(field, "-12");
        require(Integer.valueOf(-12).equals(input.value()),
                "A complete signed value must also be accepted through the paste/replacement path");
        replaceAll(field, "0");
        require(Integer.valueOf(0).equals(input.value()), "A pasted zero must not become null");
        replaceAll(field, "");
        require(field.getText().isEmpty() && input.value() == null && input.validateValue(),
                "Selecting everything and deleting must restore an unspecified temperature");

        for (String invalid : List.of("123", "-100", "abc", "2.5", "2,5", "18°", "NaN", "Infinity")) {
            input.setValue(18);
            replaceAll(field, invalid);
            require("18".equals(field.getText()),
                    "Rejected pasted input must neither erase nor truncate the current value: " + invalid);
            require(!input.errorMessage().isBlank()
                            && "error".equals(field.getClientProperty("JComponent.outline")),
                    "Rejected input needs visible, local feedback: " + invalid);
        }
        input.setValue(99);
        field.setCaretPosition(field.getDocument().getLength());
        field.replaceSelection("9");
        require("99".equals(field.getText()) && !input.errorMessage().isBlank(),
                "Typing a third digit must not silently change the saved number");
        replaceAll(field, "-5");
        require(input.validateValue() && input.errorMessage().isEmpty()
                        && Integer.valueOf(-5).equals(input.value()),
                "A valid correction must clear the error and preserve its sign");
    }

    public static void degreesStayOutsideTheAccessibleCompactField() {
        TemperatureInput input = new TemperatureInput();
        JTextField field = input.field();
        JLabel unit = findNamed(input, "temperatureCelsiusUnit", JLabel.class);
        JLabel error = findNamed(input, "temperatureCelsiusError", JLabel.class);
        require("temperatureCelsiusField".equals(field.getName()),
                "The input needs a stable field name for form integration");
        require(unit != null && "°".equals(unit.getText()) && error != null,
                "The degrees symbol and validation feedback must be separate components");
        require(field.getAccessibleContext().getAccessibleName() != null
                        && field.getAccessibleContext().getAccessibleName().toLowerCase(java.util.Locale.ROOT)
                                .contains("temperatura"),
                "The small input must still identify the measured quantity to assistive technology");
        input.setValue(-99);
        input.setSize(500, 100);
        layout(input);
        require(field.getWidth() > 0 && field.getWidth() < 110,
                "A wide form must not stretch the two-digit input across its column");
        require(!field.getText().contains("°") && "-99".equals(field.getText()),
                "The minus sign must fit while the degree symbol remains outside the editable text");
    }

    public static void unfinishedTemperaturesParticipateInUnsavedChanges() {
        JPanel form = new JPanel();
        TemperatureInput input = new TemperatureInput();
        form.add(input);
        FormChanges changes = new FormChanges(form);
        changes.markClean();
        input.field().replaceSelection("-");
        require(changes.hasChanges(), "Even an unfinished temperature must be protected as an edit");
        changes.setConfirmation(pane -> false);
        require(!changes.confirmDiscard() && "-".equals(input.field().getText()),
                "Continuing to edit must preserve the exact temperature draft");
        changes.suspendEditing();
        require(!input.field().isEnabled(), "Saving must freeze the new temperature field too");
        changes.resumeEditing();
        require(input.field().isEnabled() && changes.hasChanges(),
                "Resuming after a failed save must retain the unfinished temperature");
        replaceAll(input.field(), "");
        input.clearError();
        require(!changes.hasChanges(), "Returning to the original blank value must clear dirty state");
    }

    private static void replaceAll(JTextField field, String value) {
        field.selectAll();
        field.replaceSelection(value);
    }

    private static void requireInvalid(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Invalid values fail explicitly without coercing or silently truncating them.
        }
    }

    private static <T extends Component> T findNamed(Component component, String name, Class<T> type) {
        if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) {
                T found = findNamed(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            optionalValuesIncludeNegativeTemperaturesAndZero();
            typingAndPastingPreserveTheWholeDraft();
            degreesStayOutsideTheAccessibleCompactField();
            unfinishedTemperaturesParticipateInUnsavedChanges();
        });
        System.out.println("TemperatureInputTest: PASS");
    }
}

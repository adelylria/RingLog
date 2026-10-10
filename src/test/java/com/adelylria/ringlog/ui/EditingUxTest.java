package com.adelylria.ringlog.ui;

import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import com.adelylria.ringlog.ui.components.FormChanges;
import com.adelylria.ringlog.ui.components.MeasurementInput;
import com.adelylria.ringlog.ui.components.WrappingPanel;

public final class EditingUxTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel form = new JPanel();
            JTextField text = new JTextField("Original");
            JComboBox<String> choice = new JComboBox<>(new String[]{"A", "B"});
            JCheckBox check = new JCheckBox();
            form.add(text); form.add(choice); form.add(check);
            FormChanges changes = new FormChanges(form);
            AtomicInteger prompts = new AtomicInteger();
            AtomicBoolean discard = new AtomicBoolean();
            changes.setConfirmation(pane -> {
                prompts.incrementAndGet();
                require("Seguir editando".equals(pane.getInitialValue()), "Keep editing must be the safe default");
                return discard.get();
            });
            changes.markClean();
            require(changes.confirmDiscard() && prompts.get() == 0, "Unchanged forms must not prompt");
            text.setText("even invalid input counts");
            require(changes.hasChanges() && !changes.confirmDiscard(), "Declining preserves unsaved input");
            require(text.getText().equals("even invalid input counts"), "Prompt must never alter the draft");
            text.setText("Original");
            require(!changes.hasChanges(), "Returning to original values must clear dirty state");
            choice.setSelectedIndex(1);
            require(changes.hasChanges(), "Selections are edits too");
            changes.acceptValue(choice);
            require(!changes.hasChanges(), "Async catalog defaults can be accepted independently");
            text.setText("My notes");
            choice.setSelectedIndex(0); changes.acceptValue(choice);
            require(changes.hasChanges(), "Catalog loading must not erase user text edits");
            discard.set(true);
            require(changes.confirmDiscard() && !changes.hasChanges(), "Explicit discard is acknowledged once");
            check.setSelected(true); require(changes.hasChanges(), "Checkbox edits must be protected");
            choice.setEnabled(false);
            changes.suspendEditing();
            require(!text.isEnabled() && !check.isEnabled(), "Prevent new input during an asynchronous save");
            changes.suspendEditing();
            changes.resumeEditing();
            require(text.isEnabled() && check.isEnabled() && !choice.isEnabled(), "Restore the previous availability, not every control");
            require(changes.hasChanges(), "A failed save must retain the dirty draft after unlocking");

            MeasurementInput weight = new MeasurementInput("Peso", "weight", "g", false);
            for (String invalid : List.of("abc", "-1", "NaN", "Infinity")) {
                weight.field().setText(invalid);
                require(!weight.validateValue() && !weight.errorMessage().isBlank(), "Explain invalid measurement beside its field: " + invalid);
                require("error".equals(weight.field().getClientProperty("JComponent.outline")), "Show visual error on the offending input");
            }
            weight.field().setText("21,5");
            require(weight.validateValue() && weight.errorMessage().isEmpty(), "Comma decimals must remain supported");
            weight.field().setText(""); require(weight.validateValue(), "Optional blank measurements remain valid");
            MeasurementInput score = new MeasurementInput("Grasa", "fat", null, true);
            score.field().setText("2.5"); require(!score.validateValue(), "Scores must be whole numbers");
            score.field().setText("2"); require(score.validateValue(), "Whole scores accepted");
            JPanel measurementGrid = new JPanel(new GridLayout(1, 2));
            measurementGrid.add(weight); measurementGrid.add(score);
            weight.field().setText("invalid");weight.validateValue();
            measurementGrid.setSize(360,100);layout(measurementGrid);
            require(weight.field().getHeight() == score.field().getHeight(), "Inline errors must not distort adjacent input heights");

            WrappingPanel row = new WrappingPanel(10, 8);
            for (int i=0; i<5; i++) { JButton b=new JButton("Control " + i); b.setPreferredSize(new Dimension(170,38)); row.add(b); }
            row.setSize(600,1); int compact = row.getPreferredSize().height;
            row.setSize(600,compact); row.doLayout();
            for(Component child:row.getComponents()) require(child.getX()>=0 && child.getY()>=0
                    && child.getX()+child.getWidth()<=row.getWidth() && child.getY()+child.getHeight()<=row.getHeight(), "Wrapped controls must stay in their container");
            row.setSize(1000,1); require(row.getPreferredSize().height < compact, "Height must shrink again when widened");
        });
        System.out.println("EditingUxTest: PASS");
    }
    private static void layout(Container parent) { parent.doLayout();for(Component child:parent.getComponents())if(child instanceof Container nested)layout(nested); }
    private static void require(boolean condition, String message) { if(!condition)throw new AssertionError(message); }
}

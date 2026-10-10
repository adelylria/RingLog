package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Optional measurement with an explicit unit and local, non-destructive validation feedback. */
public final class MeasurementInput extends JPanel {
    private final JTextField field = new JTextField(7);
    private final JLabel error = new JLabel("");
    private final boolean integer;
    private boolean validationShown;

    public MeasurementInput(String label, String key, String unit, boolean integer) {
        super(new BorderLayout(0, 3));
        this.integer = integer;
        setOpaque(false);
        setName(key + "Measurement");
        JLabel caption = UiKit.muted(label + (unit == null ? "" : " · " + unit));
        caption.setLabelFor(field);
        field.setName(key + "Field");
        field.getAccessibleContext().setAccessibleName(caption.getText());
        field.setToolTipText(integer ? "Opcional · número entero" : "Opcional · " + unit + " · admite coma o punto decimal");
        error.setName(key + "Error");
        error.setFont(caption.getFont().deriveFont(11f));
        error.setVerticalAlignment(JLabel.TOP);
        error.setVisible(false);
        JPanel input = new JPanel(new BorderLayout(0, 3));
        input.setOpaque(false);
        input.add(caption, BorderLayout.NORTH);
        input.add(field, BorderLayout.CENTER);
        add(input, BorderLayout.NORTH);
        add(error, BorderLayout.CENTER);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { validateValue(); }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { if (validationShown) validateValue(); }
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
    }
    public JTextField field() { return field; }
    public String errorMessage() { return error.getText(); }
    public void clearError() { validationShown = false; displayError(""); }
    public boolean validateValue() {
        validationShown = true;
        String text = field.getText().trim();
        String message = "";
        if (!text.isEmpty()) {
            try {
                if (integer) Integer.parseInt(text);
                else {
                    double value = Double.parseDouble(text.replace(',', '.'));
                    if (!Double.isFinite(value) || value < 0) throw new NumberFormatException();
                }
            } catch (NumberFormatException failure) {
                message = integer ? "Usa un número entero." : "Número válido ≥ 0.";
            }
        }
        displayError(message);
        return message.isEmpty();
    }
    private void displayError(String message) {
        error.setText(message);
        Color background = UIManager.getColor("Panel.background");
        boolean dark = background != null
                && (background.getRed() + background.getGreen() + background.getBlue()) / 3 < 128;
        error.setForeground(dark ? new Color(244, 146, 139) : new Color(173, 38, 38));
        error.setVisible(!message.isEmpty());
        field.putClientProperty("JComponent.outline", message.isEmpty() ? null : "error");
        field.getAccessibleContext().setAccessibleDescription(message.isEmpty() ? field.getToolTipText() : message);
        revalidate();
        repaint();
    }
}

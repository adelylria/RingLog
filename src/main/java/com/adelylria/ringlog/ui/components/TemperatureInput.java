package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Optional ambient temperature: at most two digits, with an external degree symbol. */
public final class TemperatureInput extends JPanel {
    private final JTextField field = new JTextField(3);
    private final JLabel error = new JLabel("");
    private boolean validationShown;

    public TemperatureInput() {
        super(new BorderLayout(0, 3));
        setOpaque(false);
        setName("temperatureCelsiusInput");
        field.setName("temperatureCelsiusField");
        field.setHorizontalAlignment(JTextField.RIGHT);
        field.setToolTipText("Opcional · entero de −99 a 99 grados Celsius");
        field.getAccessibleContext().setAccessibleName("Temperatura ambiental en grados Celsius");
        JLabel unit = UiKit.muted("°");
        unit.setName("temperatureCelsiusUnit");
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(unit, BorderLayout.EAST);
        JPanel compact = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        compact.setOpaque(false);
        compact.add(row);
        add(compact, BorderLayout.NORTH);
        error.setName("temperatureCelsiusError");
        error.setFont(unit.getFont().deriveFont(11f));
        error.setVisible(false);
        add(error, BorderLayout.CENTER);
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass bypass, int offset, String text,
                    AttributeSet attributes) throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }
            @Override public void remove(FilterBypass bypass, int offset, int length)
                    throws BadLocationException {
                replace(bypass, offset, length, "", null);
            }
            @Override public void replace(FilterBypass bypass, int offset, int length,
                    String text, AttributeSet attributes) throws BadLocationException {
                String previous = bypass.getDocument().getText(0, bypass.getDocument().getLength());
                String replacement = text == null ? "" : text;
                String candidate = previous.substring(0, offset) + replacement
                        + previous.substring(offset + length);
                if (candidate.matches("-?[0-9]{0,2}")) {
                    bypass.replace(offset, length, replacement, attributes);
                } else {
                    validationShown = true;
                    displayError("Usa hasta dos cifras enteras (−99 a 99).");
                }
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { if (validationShown) validateValue(); }
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent event) { validateValue(); }
        });
        clearError();
    }

    public JTextField field() { return field; }
    public String errorMessage() { return error.getText(); }
    public void clearError() { validationShown = false; displayError(""); }
    public void setValue(Integer value) {
        if (value != null && (value < -99 || value > 99)) {
            throw new IllegalArgumentException("La temperatura debe estar entre −99 y 99 grados Celsius.");
        }
        field.setText(value == null ? "" : value.toString());
        clearError();
    }
    public boolean validateValue() {
        validationShown = true;
        String text = field.getText();
        boolean valid = text.isEmpty() || text.matches("-?[0-9]{1,2}");
        displayError(valid ? "" : "Completa la temperatura o deja la casilla vacía.");
        return valid;
    }
    public Integer value() {
        if (!validateValue()) throw new IllegalArgumentException(errorMessage());
        return field.getText().isEmpty() ? null : Integer.valueOf(field.getText());
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

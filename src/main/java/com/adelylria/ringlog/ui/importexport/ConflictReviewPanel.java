package com.adelylria.ringlog.ui.importexport;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.repository.MigrationConflictRepository.ConflictRecord;
import com.adelylria.ringlog.ui.components.DateSelector;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.TimeSelector;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

/** In-application, field-level comparison used to resolve one migration conflict. */
public final class ConflictReviewPanel extends JPanel {

    private static final String CHOICE_TEXT = "RingLog.reviewChoiceText";

    private final ConflictRecord conflict;
    private final Consumer<ResolutionRequest> onSubmit;
    private final Runnable onBack;
    private final List<JButton> choiceButtons = new ArrayList<>();
    private final JTextArea notes = new JTextArea(3, 40);
    private final JLabel feedback = UiKit.muted("Elige el dato que debe quedar en el registro.");
    private final JButton saveButton = UiKit.primaryButton("Guardar decisión");
    private final JButton backButton = UiKit.secondaryButton("Volver a revisiones");
    private final JButton laterButton = UiKit.secondaryButton("Decidir más tarde");
    private final JPanel manualEditor = new JPanel(new BorderLayout());
    private DateSelector dateSelector;
    private TimeSelector timeSelector;
    private JTextField manualField;
    private ResolutionRequest.Type selection;
    private boolean busy;

    public ConflictReviewPanel(
            ConflictRecord conflict,
            Consumer<ResolutionRequest> onSubmit,
            Runnable onBack
    ) {
        if (conflict == null) {
            throw new IllegalArgumentException("Indica la revisión pendiente.");
        }
        this.conflict = conflict;
        this.onSubmit = onSubmit == null ? ignored -> { } : onSubmit;
        this.onBack = onBack == null ? () -> { } : onBack;
        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());
        setName("embeddedConflictReview");
        getAccessibleContext().setAccessibleName("Revisión integrada de un dato importado");

        JPanel page = UiKit.pagePanel();
        backButton.addActionListener(event -> onBack.run());
        page.add(new PageHeader(
                "REVISIÓN PENDIENTE",
                "Revisar " + fieldLabel(conflict.fieldName()).toLowerCase(Locale.ROOT),
                headerSubtitle(),
                backButton
        ), BorderLayout.NORTH);

        JPanel content = UiKit.verticalScrollPanel();
        content.add(explanationSection());
        content.add(Box.createVerticalStrut(14));
        content.add(comparisonSection());
        content.add(Box.createVerticalStrut(14));
        content.add(manualSection());
        content.add(Box.createVerticalStrut(14));
        content.add(notesSection());
        content.add(Box.createVerticalStrut(10));
        content.add(technicalDetailsSection());

        JScrollPane scroll = UiKit.scrollPane(content);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        page.add(scroll, BorderLayout.CENTER);
        page.add(actions(), BorderLayout.SOUTH);
        add(page, BorderLayout.CENTER);

        saveButton.setEnabled(false);
        saveButton.addActionListener(event -> submitSelection());
        laterButton.addActionListener(event -> submitLater());
    }

    private JPanel explanationSection() {
        SurfacePanel section = new SurfacePanel(new BorderLayout(0, 8));
        section.add(UiKit.valueLabel("¿Por qué hay que revisar este dato?"), BorderLayout.NORTH);
        section.add(paragraph(explanation()), BorderLayout.CENTER);
        return section;
    }

    private JPanel comparisonSection() {
        JPanel comparison = UiKit.naturalGrid(2, 14);
        comparison.add(valueCard(
                "DATO GUARDADO",
                "Es el valor que RingLog conserva ahora",
                conflict.canonicalValue(),
                conflict.canonicalSourceReference(),
                "Conservar esta " + fieldNoun(),
                ResolutionRequest.Type.CANONICAL
        ));
        comparison.add(valueCard(
                "OTRA FUENTE",
                "Se encontró un valor diferente para el mismo evento",
                conflict.alternativeValue(),
                conflict.alternativeSourceReference(),
                "Usar esta " + fieldNoun(),
                ResolutionRequest.Type.ALTERNATIVE
        ));
        return comparison;
    }

    private JPanel valueCard(
            String eyebrow,
            String description,
            String value,
            String source,
            String action,
            ResolutionRequest.Type type
    ) {
        SurfacePanel card = new SurfacePanel(new BorderLayout(0, 14));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.eyebrow(eyebrow));
        copy.add(Box.createVerticalStrut(5));
        copy.add(paragraph(description));
        copy.add(Box.createVerticalStrut(14));

        JLabel displayedValue = UiKit.valueLabel(displayValue(value));
        displayedValue.setFont(displayedValue.getFont().deriveFont(Font.BOLD, 24f));
        copy.add(displayedValue);
        copy.add(Box.createVerticalStrut(7));

        JLabel sourceLabel = UiKit.muted(sourceLabel(source));
        sourceLabel.setToolTipText("Referencia de importación: " + UiKit.display(source));
        copy.add(sourceLabel);
        card.add(copy, BorderLayout.CENTER);

        JButton choice = UiKit.secondaryButton(action);
        choice.putClientProperty(CHOICE_TEXT, action);
        choice.getAccessibleContext().setAccessibleDescription(
                action + ": " + displayValue(value)
        );
        choice.addActionListener(event -> select(type, choice));
        choiceButtons.add(choice);
        card.add(choice, BorderLayout.SOUTH);
        return card;
    }

    private JPanel manualSection() {
        SurfacePanel section = new SurfacePanel(new BorderLayout(16, 10));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.valueLabel("¿Ninguna de las dos es correcta?"));
        copy.add(Box.createVerticalStrut(4));
        copy.add(UiKit.muted("Puedes indicar otro valor sin escribir formatos técnicos."));
        section.add(copy, BorderLayout.CENTER);

        String action = "Elegir otra " + fieldNoun();
        JButton manualChoice = UiKit.secondaryButton(action);
        manualChoice.putClientProperty(CHOICE_TEXT, action);
        manualChoice.addActionListener(event -> {
            manualEditor.setVisible(true);
            select(ResolutionRequest.Type.MANUAL_VALUE, manualChoice);
            revalidate();
            repaint();
        });
        choiceButtons.add(manualChoice);
        section.add(manualChoice, BorderLayout.EAST);

        manualEditor.setOpaque(false);
        manualEditor.setBorder(new EmptyBorder(6, 0, 0, 0));
        manualEditor.add(createManualEditor(), BorderLayout.WEST);
        manualEditor.setVisible(false);
        section.add(manualEditor, BorderLayout.SOUTH);
        return section;
    }

    private Component createManualEditor() {
        if ("event_date".equals(conflict.fieldName())) {
            dateSelector = new DateSelector(initialDate());
            dateSelector.getAccessibleContext().setAccessibleName("Nueva fecha validada");
            return dateSelector;
        }
        if ("event_time".equals(conflict.fieldName())) {
            timeSelector = new TimeSelector(initialTime());
            timeSelector.getAccessibleContext().setAccessibleName("Nueva hora validada");
            return timeSelector;
        }
        manualField = new JTextField(28);
        manualField.putClientProperty("JTextField.placeholderText", "Escribe el valor correcto");
        manualField.getAccessibleContext().setAccessibleName("Nuevo valor validado");
        return manualField;
    }

    private JPanel notesSection() {
        SurfacePanel section = new SurfacePanel(new BorderLayout(0, 9));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(UiKit.valueLabel("Notas de la decisión"));
        heading.add(Box.createVerticalStrut(4));
        heading.add(UiKit.muted("Opcional. Úsalo para recordar cómo comprobaste el dato."));
        section.add(heading, BorderLayout.NORTH);

        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.putClientProperty("JTextArea.placeholderText", "Ej. Confirmado en la ficha de campo");
        notes.getAccessibleContext().setAccessibleName("Notas de la decisión");
        JScrollPane notesScroll = new JScrollPane(notes);
        notesScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        section.add(notesScroll, BorderLayout.CENTER);
        return section;
    }

    private JPanel technicalDetailsSection() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setOpaque(false);
        JButton toggle = UiKit.secondaryButton("Ver detalles de las fuentes");
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setVisible(false);

        toggle.addActionListener(event -> {
            if (details.getComponentCount() == 0) {
                details.add(UiKit.muted(
                        "Referencia del dato guardado: "
                                + UiKit.display(conflict.canonicalSourceReference())
                ));
                details.add(Box.createVerticalStrut(4));
                details.add(UiKit.muted(
                        "Referencia de la otra fuente: "
                                + UiKit.display(conflict.alternativeSourceReference())
                ));
                if (conflict.originalNote() != null && !conflict.originalNote().isBlank()) {
                    details.add(Box.createVerticalStrut(4));
                    details.add(paragraph("Nota original del migrador: " + conflict.originalNote()));
                }
            }
            boolean visible = !details.isVisible();
            details.setVisible(visible);
            toggle.setText(visible ? "Ocultar detalles de las fuentes" : "Ver detalles de las fuentes");
            wrapper.revalidate();
            wrapper.repaint();
        });
        wrapper.add(toggle, BorderLayout.WEST);
        wrapper.add(details, BorderLayout.SOUTH);
        return wrapper;
    }

    private JPanel actions() {
        JPanel actions = new JPanel(new BorderLayout(16, 0));
        actions.setOpaque(false);
        actions.setBorder(new EmptyBorder(2, 0, 0, 0));
        actions.add(feedback, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(laterButton);
        buttons.add(saveButton);
        actions.add(buttons, BorderLayout.EAST);
        return actions;
    }

    private void select(ResolutionRequest.Type type, JButton selectedButton) {
        selection = type;
        for (JButton button : choiceButtons) {
            String original = (String) button.getClientProperty(CHOICE_TEXT);
            boolean selected = button == selectedButton;
            button.setText(selected ? "✓ " + original : original);
            button.setBackground(selected ? UiKit.accentSoftColor() : UIManager.getColor("Button.background"));
            button.setForeground(selected ? UiKit.textColor() : UIManager.getColor("Button.foreground"));
        }
        if (type != ResolutionRequest.Type.MANUAL_VALUE) {
            manualEditor.setVisible(false);
        }
        feedback.setText(selectionMessage(type));
        saveButton.setEnabled(!busy);
    }

    private void submitSelection() {
        if (selection == null || busy) {
            return;
        }
        try {
            ResolutionRequest request = switch (selection) {
                case CANONICAL -> ResolutionRequest.canonical(notes.getText());
                case ALTERNATIVE -> ResolutionRequest.alternative(notes.getText());
                case MANUAL_VALUE -> ResolutionRequest.manual(manualValue(), notes.getText());
                case PENDING -> ResolutionRequest.pending(notes.getText());
            };
            setBusy(true);
            onSubmit.accept(request);
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void submitLater() {
        if (busy) {
            return;
        }
        setBusy(true);
        onSubmit.accept(ResolutionRequest.pending(notes.getText()));
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        backButton.setEnabled(!busy);
        laterButton.setEnabled(!busy);
        choiceButtons.forEach(button -> button.setEnabled(!busy));
        saveButton.setEnabled(!busy && selection != null);
        if (busy) {
            feedback.setText("Guardando la decisión…");
        }
    }

    public void showError(String message) {
        setBusy(false);
        feedback.setText(message == null || message.isBlank()
                ? "No se pudo guardar la revisión." : message);
        Color error = UIManager.getColor("Actions.Red");
        if (error != null) {
            feedback.setForeground(error);
        }
    }

    private String manualValue() {
        if (dateSelector != null) {
            return dateSelector.getIsoDate();
        }
        if (timeSelector != null) {
            return timeSelector.getIsoTime();
        }
        return manualField == null ? null : manualField.getText();
    }

    private String headerSubtitle() {
        return "Anilla " + UiKit.display(conflict.ringNumber())
                + " · " + eventTypeLabel(conflict.eventType())
                + " · " + UiKit.date(conflict.eventDate())
                + " a las " + UiKit.time(conflict.eventTime());
    }

    private String explanation() {
        return "Se encontraron dos " + pluralFieldNoun()
                + " diferentes para el mismo evento. Elige cuál debe verse en el diario; "
                + "la otra seguirá conservada en el historial de importación.";
    }

    private String displayValue(String value) {
        return switch (safe(conflict.fieldName())) {
            case "event_date" -> UiKit.date(value);
            case "event_time" -> UiKit.time(value);
            default -> UiKit.display(value);
        };
    }

    private String fieldNoun() {
        return switch (safe(conflict.fieldName())) {
            case "event_date" -> "fecha";
            case "event_time" -> "hora";
            default -> "opción";
        };
    }

    private String pluralFieldNoun() {
        return switch (safe(conflict.fieldName())) {
            case "event_date" -> "fechas";
            case "event_time" -> "horas";
            default -> "valores";
        };
    }

    private static String fieldLabel(String field) {
        return switch (safe(field)) {
            case "event_date" -> "Fecha";
            case "event_time" -> "Hora";
            default -> safe(field).replace('_', ' ');
        };
    }

    private static String sourceLabel(String source) {
        String normalized = safe(source).toUpperCase(Locale.ROOT);
        if (normalized.startsWith("DOCX:")) {
            return "Documento de recuperaciones";
        }
        if (normalized.startsWith("XLSX:") || normalized.startsWith("XLS:")) {
            return "Excel histórico";
        }
        if (normalized.startsWith("ACCESS:") || normalized.startsWith("MDB:")) {
            return "Base de datos histórica";
        }
        if (normalized.startsWith("RINGLOG:")) {
            return "RingLog";
        }
        return "Fuente histórica conservada";
    }

    private static String eventTypeLabel(String eventType) {
        return switch (safe(eventType).toUpperCase(Locale.ROOT)) {
            case "RINGING" -> "Anillamiento";
            case "CONTROL" -> "Control";
            case "RECOVERY" -> "Recuperación";
            default -> UiKit.display(eventType);
        };
    }

    private String selectionMessage(ResolutionRequest.Type type) {
        return switch (type) {
            case CANONICAL -> "Se conservará " + displayValue(conflict.canonicalValue()) + ".";
            case ALTERNATIVE -> "El registro cambiará a "
                    + displayValue(conflict.alternativeValue()) + ".";
            case MANUAL_VALUE -> "Se guardará el valor que indiques manualmente.";
            case PENDING -> "La revisión seguirá pendiente.";
        };
    }

    private LocalDate initialDate() {
        try {
            return LocalDate.parse(conflict.canonicalValue());
        } catch (DateTimeParseException ignored) {
            return LocalDate.now();
        }
    }

    private LocalTime initialTime() {
        try {
            return LocalTime.parse(UiKit.time(conflict.canonicalValue()));
        } catch (DateTimeParseException ignored) {
            return LocalTime.now().withSecond(0).withNano(0);
        }
    }

    private static JTextArea paragraph(String text) {
        JTextArea paragraph = new JTextArea(text);
        paragraph.setEditable(false);
        paragraph.setFocusable(false);
        paragraph.setLineWrap(true);
        paragraph.setWrapStyleWord(true);
        paragraph.setOpaque(false);
        paragraph.setBorder(null);
        paragraph.setForeground(UiKit.mutedColor());
        paragraph.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, 14f));
        paragraph.setAlignmentX(Component.LEFT_ALIGNMENT);
        return paragraph;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

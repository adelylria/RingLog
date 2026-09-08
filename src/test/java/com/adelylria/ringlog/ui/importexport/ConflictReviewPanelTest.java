package com.adelylria.ringlog.ui.importexport;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JLabel;

import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.repository.MigrationConflictRepository;
import com.adelylria.ringlog.repository.MigrationConflictRepository.ConflictRecord;
import com.adelylria.ringlog.ui.components.DateSelector;

public final class ConflictReviewPanelTest {

    private ConflictReviewPanelTest() {
    }

    public static void reviewIsAnEmbeddedApplicationView() {
        ConflictRecord conflict = dateConflict();
        ConflictsPanel reviews = new ConflictsPanel(
                new MigrationConflictRepository("unused-review-test.db"),
                new ConflictResolutionService("unused-review-test.db"),
                () -> { }
        );

        reviews.showReview(conflict);

        require(descendants(reviews, ConflictReviewPanel.class).size() == 1,
                "Reviewing a conflict must replace the list with an embedded application view");
        require(button(reviews, "Volver a revisiones") != null,
                "The embedded view must return to the review list without closing a window");
    }

    public static void comparisonUsesFriendlyChoicesAndPreservesTheDecision() {
        AtomicReference<ResolutionRequest> submitted = new AtomicReference<>();
        ConflictReviewPanel review = new ConflictReviewPanel(
                dateConflict(), submitted::set, () -> { }
        );

        List<String> labels = descendants(review, JLabel.class).stream()
                .map(JLabel::getText)
                .filter(text -> text != null)
                .toList();
        require(labels.stream().anyMatch(text -> text.contains("3 ene 2026")),
                "The current date must be presented in a format people can read naturally");
        require(labels.stream().anyMatch(text -> text.contains("1 mar 2026")),
                "The alternative date must be presented in a format people can read naturally");
        require(labels.stream().anyMatch(text -> text.contains("Documento de recuperaciones")),
                "The canonical source must have a friendly name");
        require(labels.stream().anyMatch(text -> text.contains("Excel histórico")),
                "The alternative source must have a friendly name");
        require(labels.stream().noneMatch(text -> text.contains("DOCX:") || text.contains("XLSX:")),
                "Raw migration references must not dominate the comparison");

        JButton save = button(review, "Guardar decisión");
        require(!save.isEnabled(), "Saving must wait for an explicit choice");
        button(review, "Usar esta fecha").doClick();
        require(save.isEnabled(), "Choosing the alternative must enable saving");
        save.doClick();
        require(submitted.get() != null
                        && submitted.get().type() == ResolutionRequest.Type.ALTERNATIVE,
                "The friendly alternative choice must submit an ALTERNATIVE resolution");
    }

    public static void manualDateUsesTheSameGuidedSelectorAsNewEntries() {
        AtomicReference<ResolutionRequest> submitted = new AtomicReference<>();
        ConflictReviewPanel review = new ConflictReviewPanel(
                dateConflict(), submitted::set, () -> { }
        );

        button(review, "Elegir otra fecha").doClick();
        DateSelector selector = descendants(review, DateSelector.class).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Manual date review must use RingLog's guided date selector"
                ));
        selector.setDate(LocalDate.of(2025, 12, 29));
        button(review, "Guardar decisión").doClick();

        require(submitted.get() != null
                        && submitted.get().type() == ResolutionRequest.Type.MANUAL_VALUE
                        && "2025-12-29".equals(submitted.get().value()),
                "The guided date selector must submit the ISO value expected by the database");
    }

    private static ConflictRecord dateConflict() {
        return new ConflictRecord(
                7L,
                "conflict-v25943-date",
                42L,
                "V25943",
                "FIELD_MISMATCH",
                "RECOVERY",
                "event_date",
                "DOCX:RECOVERY:6:V25943",
                "2026-01-03",
                "XLSX:2025-26:38:AUX:RECOVERY:2",
                "2026-03-01",
                "{\"event_date\":\"2026-01-03\"}",
                "{\"event_date\":\"2026-03-01\"}",
                "Fechas de recuperación contradictorias.",
                "PENDING_REVIEW",
                "2026-01-03",
                "17:35:00"
        );
    }

    private static JButton button(Container root, String text) {
        return descendants(root, JButton.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    private static <T extends Component> List<T> descendants(
            Container root,
            Class<T> type
    ) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
            if (component instanceof Container child) {
                result.addAll(descendants(child, type));
            }
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        reviewIsAnEmbeddedApplicationView();
        comparisonUsesFriendlyChoicesAndPreservesTheDecision();
        manualDateUsesTheSameGuidedSelectorAsNewEntries();
        System.out.println("ConflictReviewPanelTest: PASS");
    }
}

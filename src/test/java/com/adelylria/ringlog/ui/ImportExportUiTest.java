package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;

import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.Sidebar;
import com.adelylria.ringlog.ui.importexport.ConflictsPanel;
import com.adelylria.ringlog.ui.importexport.ImportAnalysis;
import com.adelylria.ringlog.ui.importexport.ImportExportController;
import com.adelylria.ringlog.ui.panels.SettingsPanel;

public final class ImportExportUiTest {

    private static final Path LEGACY_FIXTURE = LegacyV52TestFixture.path();

    private ImportExportUiTest() {
    }

    public static void previewUsesTheAuthoritativeValidatedDataset() throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Legacy import UI preview integration")) {
            return;
        }
        require(Files.isRegularFile(LEGACY_FIXTURE),
                "The approved v5.2 fixture is required for the UI preview test");
        Path root = Files.createTempDirectory("ringlog-ui-preview-");
        ImportExportController controller = new ImportExportController(
                root.resolve("ringlog.db").toString(), root.resolve("media")
        );
        try (ImportAnalysis analysis = controller.analyze(LEGACY_FIXTURE)) {
            require(analysis.format() == ImportFormat.LEGACY_V5,
                    "The preview must identify Legacy Migrator v5/v5.2");
            require(analysis.speciesCount() == 4 && analysis.birdsCount() == 271,
                    "The preview must report authoritative species and bird counts");
            require(analysis.eventsCount() == 289 && analysis.placesCount() == 4,
                    "The preview must report authoritative event and place counts");
            require(analysis.pendingConflictCount() == 7,
                    "The preview must announce all pending field conflicts");
            require(analysis.requiresConflictReview(),
                    "Legacy imports with pending conflicts must lead to review");
        }
    }

    public static void reviewWorkflowOnlyOffersFieldLevelChoices() {
        List<ResolutionRequest.Type> choices = ConflictsPanel.resolutionTypes();
        require(choices.equals(List.of(
                        ResolutionRequest.Type.CANONICAL,
                        ResolutionRequest.Type.ALTERNATIVE,
                        ResolutionRequest.Type.MANUAL_VALUE,
                        ResolutionRequest.Type.PENDING
                )),
                "The review must expose only the four approved field decisions");
        require(ConflictsPanel.resolutionLabels().stream()
                        .noneMatch(label -> label.toLowerCase().contains("eventos diferentes")),
                "The v5.2 review must never recreate an alternative event");
    }

    public static void navigationAndSettingsExposeTheSharedWorkflow() {
        Sidebar sidebar = new Sidebar(ignored -> { });
        require(descendants(sidebar, JButton.class).stream()
                        .anyMatch(button -> "Revisiones".equals(button.getText())),
                "Pending conflicts need a first-class navigation destination");
        require("CONFLICTS".equals(MainFrame.CONFLICTS),
                "The review destination must be stable");

        SettingsPanel settings = new SettingsPanel();
        JButton importButton = button(settings, "Importar historial");
        JButton exportButton = button(settings, "Exportar copia");
        require(importButton.getAccessibleContext().getAccessibleName().contains("ZIP"),
                "The import action must communicate native ZIP support");
        require(exportButton.getToolTipText().contains("ZIP")
                        && exportButton.getToolTipText().contains("Excel"),
                "The export action must explain its automatic package choice");
    }

    public static void diaryCardsMarkEventsThatNeedReview() {
        BirdEventCard card = new BirdEventCard(
                new BirdEventTimelineItem(
                        1L, 2L, "V25943", EventType.CONTROL,
                        "2026-02-09", "18:35:00", "TURDUS PHILOMELOS",
                        "ELS RAFALS", null, null, null, "REVIEW"
                ),
                ignored -> { }
        );
        require(descendants(card, JLabel.class).stream()
                        .map(JLabel::getText)
                        .anyMatch("Revisar"::equals),
                "A pending conflict must remain visible in the diary timeline");
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

    public static void main(String[] args) throws Exception {
        previewUsesTheAuthoritativeValidatedDataset();
        reviewWorkflowOnlyOffersFieldLevelChoices();
        navigationAndSettingsExposeTheSharedWorkflow();
        diaryCardsMarkEventsThatNeedReview();
        System.out.println("ImportExportUiTest: PASS");
    }
}

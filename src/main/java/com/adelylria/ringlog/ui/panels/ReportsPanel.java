package com.adelylria.ringlog.ui.panels;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;

import com.adelylria.ringlog.model.view.DashboardStats;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.ReportSummary;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.ui.components.PageHeader;
import com.adelylria.ringlog.ui.components.StatCard;
import com.adelylria.ringlog.ui.theme.UiKit;

public class ReportsPanel extends JPanel {

    private final BirdEventRepository eventRepository;
    private final CatalogRepository catalogRepository;
    private final JPanel content;

    public ReportsPanel(
            BirdEventRepository eventRepository,
            CatalogRepository catalogRepository
    ) {
        this.eventRepository = eventRepository;
        this.catalogRepository = catalogRepository;
        this.content = new JPanel();
        initialize();
    }

    public ReportsPanel() {
        this(new BirdEventRepository(), new CatalogRepository());
    }

    private void initialize() {
        setLayout(new BorderLayout());
        JPanel page = UiKit.pagePanel();
        page.add(new PageHeader(
                "LECTURA DEL DIARIO",
                "Informes",
                "Una vista breve para entender cómo va creciendo tu archivo.",
                null
        ), BorderLayout.NORTH);
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JScrollPane scroll = UiKit.scrollPane(content);
        page.add(scroll, BorderLayout.CENTER);
        add(page, BorderLayout.CENTER);
    }

    public void refresh() {
        content.removeAll();
        content.add(UiKit.muted("Preparando el resumen…"));
        content.revalidate();
        content.repaint();

        new SwingWorker<ReportSummary, Void>() {
            @Override
            protected ReportSummary doInBackground() {
                DashboardStats stats = eventRepository.findDashboardStats();
                List<SpeciesSummary> species = catalogRepository.findSpeciesSummaries();
                List<PlaceSummary> places = catalogRepository.findPlaceSummaries();
                return new ReportSummary(
                        stats,
                        species.stream()
                                .max(Comparator.comparingLong(SpeciesSummary::eventCount))
                                .orElse(null),
                        places.stream()
                                .max(Comparator.comparingLong(PlaceSummary::eventCount))
                                .orElse(null)
                );
            }

            @Override
            protected void done() {
                try {
                    render(get());
                } catch (InterruptedException | ExecutionException exception) {
                    content.removeAll();
                    content.add(UiKit.muted("No se ha podido preparar el informe."));
                    content.revalidate();
                    content.repaint();
                }
            }
        }.execute();
    }

    private void render(ReportSummary report) {
        content.removeAll();
        JPanel stats = UiKit.statGrid();
        stats.add(new StatCard(
                "REGISTROS",
                Long.toString(report.stats().eventCount()),
                "eventos guardados"
        ));
        stats.add(new StatCard(
                "ESPECIES",
                Long.toString(report.stats().speciesCount()),
                "especies distintas"
        ));
        stats.add(new StatCard(
                "ÚLTIMO DÍA",
                UiKit.date(report.stats().latestEventDate()),
                "última entrada"
        ));
        content.add(stats);
        content.add(Box.createVerticalStrut(16));

        JPanel insights = UiKit.naturalGrid(2, 14);
        insights.add(insightCard(
                "ESPECIE MÁS FRECUENTE",
                report.mostFrequentSpecies() == null
                        ? "—"
                        : report.mostFrequentSpecies().name(),
                "La especie que más páginas ocupa en tu diario."
        ));
        insights.add(insightCard(
                "LUGAR MÁS VISITADO",
                report.mostFrequentPlace() == null
                        ? "—"
                        : report.mostFrequentPlace().name(),
                "El escenario al que más veces has regresado."
        ));
        content.add(insights);
        content.add(Box.createVerticalStrut(16));

        JPanel hint = UiKit.sectionPanel();
        hint.add(UiKit.eyebrow("UNA LECTURA SENCILLA"), BorderLayout.NORTH);
        hint.add(UiKit.muted(
                "Cada número aquí representa una historia que has decidido conservar. "
                        + "Vuelve al Diario para leerlas con calma."
        ), BorderLayout.CENTER);
        content.add(hint);
        content.revalidate();
        content.repaint();
    }

    private JPanel insightCard(String label, String value, String caption) {
        JPanel card = UiKit.sectionPanel();
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiKit.eyebrow(label));
        copy.add(Box.createVerticalStrut(7));
        JLabel valueLabel = UiKit.valueLabel(value);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 18f));
        copy.add(valueLabel);
        copy.add(Box.createVerticalStrut(5));
        copy.add(UiKit.muted(caption));
        card.add(copy, BorderLayout.CENTER);
        return card;
    }
}

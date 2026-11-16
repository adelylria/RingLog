package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.panels.CaptureListPanel;

public final class CaptureListPositionTest {
    private CaptureListPositionTest() { }

    private static void readOnlyReturnKeepsCardsFiltersLimitAndViewport() throws Exception {
        Fixture fixture = fixture(230);
        expandAndScroll(fixture);
        List<BirdEventCard> before = edt(() -> cards(fixture.panel));
        Point position = edt(() -> scroll(fixture.panel).getViewport().getViewPosition());
        edt(() -> { fixture.panel.rememberListPosition(); fixture.panel.resumeAfterDetail(false); return null; });
        flush();
        edt(() -> {
            require(position.equals(scroll(fixture.panel).getViewport().getViewPosition()), "Read-only return must keep the exact viewport");
            require(cards(fixture.panel).get(0) == before.get(0) && cards(fixture.panel).size() == 130,
                    "Cancel/back must reuse existing cards and the expanded page");
            require(privateField(fixture.panel, "searchField", JTextField.class).getText().equals("PN"),
                    "Search must survive return");
            return null;
        });
        require(fixture.repository.reads.get() == 1, "Read-only return must not query SQLite again");
    }

    private static void editedReturnKeepsAnchorAndOnlyLoadsOnce() throws Exception {
        Fixture fixture = fixture(230);
        expandAndScroll(fixture);
        Anchor before = edt(() -> anchor(fixture.panel));
        edt(() -> { fixture.panel.rememberListPosition(); return null; });
        fixture.repository.rows = fixture.repository.rows.subList(1, fixture.repository.rows.size());
        edt(() -> { fixture.panel.resumeAfterDetail(true); return null; });
        await(() -> settled(fixture.panel), "Edited archive must finish loading");
        flush();
        edt(() -> {
            layout(fixture.panel);
            Anchor after = anchor(fixture.panel);
            require(after.id == before.id && Math.abs(after.offset - before.offset) <= 1,
                    "A removed earlier record must not move the anchored entry within the viewport: " + before + " -> " + after);
            require(cards(fixture.panel).size() == 130, "Saved edits must retain the expanded result limit");
            return null;
        });
        require(fixture.repository.reads.get() == 2, "Saving must reload exactly once");
    }

    private static void removedLastFilterValueStaysSelectedAndClampsEmptyList() throws Exception {
        Fixture fixture = fixture(1);
        AtomicReference<Object> month = new AtomicReference<>();
        edt(() -> {
            privateField(fixture.panel, "speciesCombo", JComboBox.class).setSelectedItem("Species");
            privateField(fixture.panel, "placeCombo", JComboBox.class).setSelectedItem("Place");
            privateField(fixture.panel, "dateModeCombo", JComboBox.class).setSelectedItem("Mes");
            month.set(privateField(fixture.panel, "datePeriodCombo", JComboBox.class).getSelectedItem());
            fixture.panel.rememberListPosition();
            return null;
        });
        await(() -> settled(fixture.panel), "Filter must settle");
        edt(() -> { fixture.panel.rememberListPosition(); return null; });
        fixture.repository.rows = List.of();
        edt(() -> { fixture.panel.resumeAfterDetail(true); return null; });
        await(() -> settled(fixture.panel), "Empty filtered archive must settle");
        flush();
        edt(() -> {
            require("Species".equals(privateField(fixture.panel, "speciesCombo", JComboBox.class).getSelectedItem())
                            && "Place".equals(privateField(fixture.panel, "placeCombo", JComboBox.class).getSelectedItem())
                            && month.get().equals(privateField(fixture.panel, "datePeriodCombo", JComboBox.class).getSelectedItem()),
                    "Editing away the final matching value must preserve the chosen filters, not widen the query");
            require(cards(fixture.panel).isEmpty() && scroll(fixture.panel).getViewport().getViewPosition().y == 0,
                    "An empty archive must clamp the restored viewport safely");
            return null;
        });
    }

    private static void savedObservationUpdatesTheExistingCard() throws Exception {
        Fixture fixture = fixture(230);
        expandAndScroll(fixture);
        edt(() -> { fixture.panel.rememberListPosition(); return null; });
        fixture.repository.rows = fixture.repository.rows.stream().map(item -> item.id() == 21
                ? new BirdEventTimelineItem(item.id(), item.birdId(), item.ringNumber(), item.eventType(),
                        item.eventDate(), item.eventTime(), item.species(), item.place(), "Updated note",
                        item.birdCondition(), item.photoPath(), item.reviewStatus(), item.historyEventTypes()) : item).toList();
        edt(() -> { fixture.panel.resumeAfterDetail(true); return null; });
        await(() -> settled(fixture.panel) && descendants(fixture.panel, JLabel.class).stream()
                .anyMatch(label -> "Updated note".equals(label.getText())),
                "Saved observations must replace the old text on the same timeline card");
        require(fixture.repository.reads.get() == 2, "An edited card must require exactly one additional archive read");
    }

    private static void failedReloadKeepsContextAndNextReturnRetries() throws Exception {
        Fixture fixture = fixture(230);
        expandAndScroll(fixture);
        Point position = edt(() -> scroll(fixture.panel).getViewport().getViewPosition());
        BirdEventCard first = edt(() -> cards(fixture.panel).get(0));
        fixture.repository.failNext = true;
        edt(() -> { fixture.panel.rememberListPosition(); fixture.panel.resumeAfterDetail(true); return null; });
        await(() -> settled(fixture.panel) && privateField(fixture.panel, "statusLabel", JLabel.class)
                .getText().contains("No se han podido"), "Failed read must finish with a retryable error");
        flush();
        edt(() -> {
            require(cards(fixture.panel).get(0) == first && position.equals(scroll(fixture.panel).getViewport().getViewPosition()),
                    "A failed refresh must retain the visible context, not replace it with an empty archive");
            fixture.panel.rememberListPosition();
            fixture.panel.resumeAfterDetail(false);
            return null;
        });
        await(() -> settled(fixture.panel) && !privateField(fixture.panel, "statusLabel", JLabel.class)
                .getText().contains("No se han podido"), "A later return must retry the failed load");
        flush();
        require(fixture.repository.reads.get() == 3, "Initial read, failed reload and successful retry must each load once");
        edt(() -> {
            require(position.equals(scroll(fixture.panel).getViewport().getViewPosition()) && cards(fixture.panel).size() == 130,
                    "Retry must preserve the same expanded page and viewport");
            return null;
        });
    }

    private static void staleReloadAndChangedFilterCannotRestoreOldPosition() throws Exception {
        Fixture fixture = fixture(230);
        expandAndScroll(fixture);
        Gate gate = new Gate();
        fixture.repository.gate = gate;
        edt(() -> { fixture.panel.rememberListPosition(); fixture.panel.resumeAfterDetail(true); return null; });
        require(gate.entered.await(5, TimeUnit.SECONDS), "Slow reload must start");
        fixture.repository.rows = List.of(event(999));
        edt(() -> { fixture.panel.resumeAfterDetail(true); return null; });
        await(() -> settled(fixture.panel) && cards(fixture.panel).size() == 1, "Newer reload must win");
        gate.release.countDown();
        Thread.sleep(100);
        flush();
        edt(() -> {
            require(cardId(cards(fixture.panel).get(0)) == 999, "Late old response must not replace the current archive");
            fixture.panel.rememberListPosition();
            fixture.panel.resumeAfterDetail(false);
            privateField(fixture.panel, "searchField", JTextField.class).setText("missing");
            scroll(fixture.panel).getViewport().setViewPosition(new Point(0, 0));
            return null;
        });
        await(() -> settled(fixture.panel), "Changed query must settle");
        flush();
        edt(() -> {
            require(cards(fixture.panel).isEmpty() && scroll(fixture.panel).getViewport().getViewPosition().y == 0,
                    "A deferred restoration from a different filter must never scroll the new query");
            return null;
        });
    }

    private static Fixture fixture(int count) throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.rows = IntStream.rangeClosed(1, count).mapToObj(CaptureListPositionTest::event).toList();
        CaptureListPanel panel = edt(() -> new CaptureListPanel(repository, ignored -> { }, () -> { }));
        edt(() -> { panel.setSize(1000, 650); panel.refresh(); return null; });
        await(() -> settled(panel), "Archive must load");
        edt(() -> { for (int pass = 0; pass < 5; pass++) layout(panel); return null; });
        return new Fixture(panel, repository);
    }

    private static void expandAndScroll(Fixture fixture) throws Exception {
        edt(() -> { privateField(fixture.panel, "searchField", JTextField.class).setText("PN"); return null; });
        await(() -> settled(fixture.panel), "Search must settle");
        edt(() -> { privateField(fixture.panel, "showMoreButton", JButton.class).doClick(); return null; });
        await(() -> settled(fixture.panel) && cards(fixture.panel).size() == 130, "Second filtered page must expand");
        edt(() -> {
            for (int pass = 0; pass < 5; pass++) layout(fixture.panel);
            scroll(fixture.panel).getViewport().setViewPosition(new Point(0, 4000));
            require(scroll(fixture.panel).getViewport().getViewPosition().y == 4000, "Fixture must scroll away from filters");
            return null;
        });
    }

    private static Anchor anchor(CaptureListPanel panel) {
        int y = scroll(panel).getViewport().getViewPosition().y;
        for (BirdEventCard card : cards(panel)) {
            Rectangle bounds = SwingUtilities.convertRectangle(card.getParent(), card.getBounds(), scroll(panel).getViewport().getView());
            if (bounds.y + bounds.height > y) return new Anchor(cardId(card), y - bounds.y);
        }
        throw new AssertionError("No visible anchor");
    }
    private static long cardId(BirdEventCard card) {
        return descendants(card, JLabel.class).stream().map(JLabel::getText)
                .filter(text -> text != null && text.startsWith("Anilla PN")).mapToLong(text -> Long.parseLong(text.substring(9))).findFirst().orElseThrow();
    }
    private static BirdEventTimelineItem event(int id) {
        return new BirdEventTimelineItem(id, id, "PN" + id, EventType.RINGING,
                "2026-01-01", "12:00", "Species", "Place", "Note", "GOOD", null);
    }
    private static boolean settled(CaptureListPanel panel) { return Boolean.FALSE.equals(panel.getClientProperty("RingLog.filterPending")); }
    private static JScrollPane scroll(CaptureListPanel panel) { return privateField(panel, "archiveScroll", JScrollPane.class); }
    private static List<BirdEventCard> cards(CaptureListPanel panel) { return descendants(panel, BirdEventCard.class); }
    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> values = new ArrayList<>();
        if (type.isInstance(root)) values.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) values.addAll(descendants(child, type));
        return values;
    }
    private static <T> T privateField(Object root, String name, Class<T> type) {
        try { Field field = root.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(root)); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container); }
    private static void flush() throws Exception { edt(() -> null); edt(() -> null); edt(() -> null); }
    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        throw new AssertionError(message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Fixture(CaptureListPanel panel, MemoryRepository repository) { }
    private record Anchor(long id, int offset) { }
    private static final class Gate { final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); }
    private static final class MemoryRepository extends BirdEventRepository {
        volatile List<BirdEventTimelineItem> rows;
        volatile Gate gate;
        volatile boolean failNext;
        final AtomicInteger reads = new AtomicInteger();
        @Override public List<BirdEventTimelineItem> findTimeline() {
            require(!SwingUtilities.isEventDispatchThread(), "Timeline reads must run outside the EDT");
            reads.incrementAndGet();
            if (failNext) { failNext = false; throw new IllegalStateException("Synthetic read failure"); }
            List<BirdEventTimelineItem> snapshot = rows;
            Gate held = gate;
            if (held != null) {
                gate = null;
                held.entered.countDown();
                try { require(held.release.await(5, TimeUnit.SECONDS), "Slow read must finish"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }
            return snapshot;
        }
    }
    public static void main(String[] args) throws Exception {
        readOnlyReturnKeepsCardsFiltersLimitAndViewport();
        editedReturnKeepsAnchorAndOnlyLoadsOnce();
        savedObservationUpdatesTheExistingCard();
        removedLastFilterValueStaysSelectedAndClampsEmptyList();
        failedReloadKeepsContextAndNextReturnRetries();
        staleReloadAndChangedFilterCannotRestoreOldPosition();
        System.out.println("CaptureListPositionTest: PASS");
    }
}

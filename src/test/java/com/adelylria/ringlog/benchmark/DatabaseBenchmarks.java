package com.adelylria.ringlog.benchmark;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.CaptureMethodOption;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;

/** Exercises the public database/repository paths against the isolated benchmark fixture. */
public final class DatabaseBenchmarks {

    private DatabaseBenchmarks() { }

    public static void run(BenchmarkContext ctx) throws Exception {
        BirdEventRepository events = ctx.events();
        CatalogRepository catalog = ctx.catalog();
        String database = ctx.databaseFile();

        ctx.measure("database", "open-connection-and-select-one", () -> {
            try (Connection connection = Database.getConnection(database);
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT 1")) {
                ctx.require(row.next() && row.getInt(1) == 1, "A writable connection must execute SQL");
                return row.getInt(1);
            }
        });
        ctx.measure("database", "open-read-only-connection", () -> {
            try (Connection connection = Database.getReadOnlyConnection(database);
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("PRAGMA query_only")) {
                ctx.require(row.next() && row.getInt(1) == 1, "Read-only connections must remain query-only");
                return row.getInt(1);
            }
        });
        ctx.measure("database", "initialize-existing-including-integrity-checks", () -> {
            Database.initialize(database);
            return Files.size(Path.of(database));
        });
        ctx.measure("database", "validate-existing-schema-and-integrity", () -> {
            Database.validate(database);
            return Database.SCHEMA_VERSION;
        });
        AtomicInteger freshId = new AtomicInteger();
        Path freshRoot = ctx.workDirectory().resolve("fresh-databases")
                .resolve(java.util.UUID.randomUUID().toString());
        ctx.measure("database", "initialize-new-database", () -> {
            Path fresh = freshRoot.resolve("sample-" + freshId.incrementAndGet()).resolve("ringlog.db");
            Database.initialize(fresh.toString());
            return Files.size(fresh);
        });

        ctx.measure("repository", "timeline-all", () -> {
            var rows = events.findTimeline();
            ctx.require(rows.size() == ctx.eventCount(), "The timeline must return every synthetic record");
            return rows.stream().mapToLong(row -> row.id() + row.historyEventTypes().size()).sum();
        });
        ctx.measure("repository", "timeline-recent-24", () -> {
            var rows = events.findRecentTimeline(24);
            ctx.require(rows.size() == Math.min(24, ctx.eventCount()), "The recent timeline must respect its limit");
            return rows.stream().mapToLong(row -> row.id() + row.historyEventTypes().size()).sum();
        });
        ctx.measure("repository", "timeline-single-bird-history", () -> {
            var rows = events.findEventsByBird(ctx.birdId());
            ctx.require(!rows.isEmpty() && rows.stream().allMatch(row -> row.birdId() == ctx.birdId()),
                    "The history query must only return the selected bird");
            return rows.stream().mapToLong(row -> row.id() + row.historyEventTypes().size()).sum();
        });
        ctx.measure("repository", "detail-one-with-photo-references", () -> {
            BirdEventDetail detail = events.findDetail(ctx.eventId()).orElseThrow();
            return detail.id() + detail.photoPaths().size();
        });
        List<Long> detailIds = ctx.reportIds(Math.min(10, ctx.eventCount()));
        ctx.measure("repository", "detail-ten-sequential", () -> {
            long checksum = 0;
            for (long id : detailIds) checksum += events.findDetail(id).orElseThrow().id();
            return checksum;
        });
        BirdEventDetail sample = events.findDetail(ctx.eventId()).orElseThrow();
        ctx.measure("repository", "ring-lookup-existing", () -> {
            var bird = events.findBirdByRingNumber(sample.ringNumber()).orElseThrow();
            ctx.require(bird.id() == ctx.birdId(), "Lookup must resolve the fixture's selected bird");
            return bird.id() + bird.eventCount();
        });
        ctx.measure("repository", "ring-lookup-missing", () -> {
            var missing = events.findBirdByRingNumber("BENCHMARK-RING-NOT-PRESENT");
            ctx.require(missing.isEmpty(), "Unknown rings must not resolve a bird");
            return missing.isEmpty() ? 1 : 0;
        });
        ctx.measure("repository", "dashboard-statistics", () -> {
            var stats = events.findDashboardStats();
            ctx.require(stats.eventCount() == ctx.eventCount(), "Dashboard totals must match the fixture");
            return stats.eventCount() + stats.speciesCount() + stats.placeCount();
        });

        ctx.measure("catalog", "species-options", () -> catalog.findSpeciesOptions().size());
        ctx.measure("catalog", "species-summaries-with-event-counts", () -> catalog.findSpeciesSummaries()
                .stream().mapToLong(row -> row.id() + row.eventCount()).sum());
        ctx.measure("catalog", "place-summaries-with-event-counts", () -> catalog.findPlaceSummaries()
                .stream().mapToLong(row -> row.id() + row.eventCount()).sum());
        ctx.measure("catalog", "bird-options", () -> catalog.findBirdOptions().size());
        ctx.measure("catalog", "capture-method-options", () -> catalog.findCaptureMethodOptions().size());
        ctx.measure("catalog", "capture-method-management-list", () -> catalog.findCaptureMethods().size());

        for (int maximum : new LinkedHashSet<>(List.of(24, 100,
                Math.min(ctx.reportLimit(), ctx.eventCount())))) {
            List<Long> ids = ctx.reportIds(maximum);
            ctx.measure("repository", "report-projection-" + ids.size(), () -> {
                var rows = events.findReportRows(ids);
                ctx.require(rows.size() == ids.size(), "A report projection must preserve the complete selection");
                for (int index = 0; index < ids.size(); index++) {
                    ctx.require(rows.get(index).id() == ids.get(index), "A report projection must preserve selection order");
                }
                return rows.stream().mapToLong(row -> row.id() + row.photoCount()).sum();
            });
        }

        measureTimelineReferences(ctx);
        writeQueryPlans(ctx);
        measureMutations(ctx, sample);
    }

    private static void measureTimelineReferences(BenchmarkContext ctx) throws Exception {
        String order = constant("TIMELINE_ORDER");
        String correlated = constant("TIMELINE_SELECT") + order;
        String grouped = constant("TIMELINE_ARCHIVE_SELECT") + order;
        long[] expectedChecksum = new long[1];
        boolean[] referenceReady = new boolean[1];
        ctx.measure("sql-reference", "full-timeline-correlated", () -> {
            long checksum = timelineChecksum(ctx, correlated);
            if (referenceReady[0]) {
                ctx.require(checksum == expectedChecksum[0], "The correlated timeline reference must remain stable");
            } else {
                expectedChecksum[0] = checksum;
                referenceReady[0] = true;
            }
            return checksum;
        });
        ctx.measure("sql-reference", "full-timeline-grouped", () -> {
            long checksum = timelineChecksum(ctx, grouped);
            ctx.require(referenceReady[0] && checksum == expectedChecksum[0],
                    "Grouped and correlated timelines must return the same ordered IDs, photos and journeys");
            return checksum;
        });
    }

    private static long timelineChecksum(BenchmarkContext ctx, String sql) throws Exception {
        long checksum = 1;
        int count = 0;
        // Use the production connection policy, including its default SQLite temp storage.
        try (Connection connection = Database.getConnection(ctx.databaseFile());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
                checksum = 31 * checksum + rows.getLong("id");
                checksum = 31 * checksum + rows.getLong("bird_id");
                checksum = 31 * checksum + java.util.Objects.hashCode(rows.getString("photo_path"));
                String journey = rows.getString("history_event_types");
                int phases = 0;
                if (journey != null) {
                    // GROUP_CONCAT order is unspecified; the public projection orders the enum phases.
                    for (String code : journey.split(",")) {
                        EventType type = EventType.fromDatabase(code.trim());
                        phases |= 1 << type.ordinal();
                    }
                }
                checksum = 31 * checksum + phases;
            }
        }
        ctx.require(count == ctx.eventCount(), "Each SQL reference must consume the complete synthetic timeline");
        return 31 * checksum + count;
    }

    private static void measureMutations(BenchmarkContext ctx, BirdEventDetail sample) throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        try {
            ctx.measure("mutation", "update-existing-event", () -> {
                int version = sequence.incrementAndGet();
                ctx.events().update(sample.id(), eventInput(sample, sample.eventType(),
                        "Synthetic benchmark update " + (version % 2)));
                return sample.id();
            });
        } finally {
            ctx.events().update(sample.id(), eventInput(sample, sample.eventType(), sample.observations()));
        }
        ctx.require(java.util.Objects.equals(sample.observations(),
                        ctx.events().findDetail(sample.id()).orElseThrow().observations()),
                "Update benchmarks must restore the fixture's original event values");

        ctx.measure("mutation", "insert-control-event-and-clean-up", () -> {
            long created = ctx.events().insert(eventInput(sample, EventType.CONTROL,
                    "Synthetic benchmark temporary control"));
            try {
                ctx.require(created > 0, "The public insert operation must create the control record");
                return 1;
            } finally {
                // Only the synthetic row returned by this exact operation is removed.
                try (Connection connection = Database.getConnection(ctx.databaseFile());
                     PreparedStatement statement = connection.prepareStatement(
                             "DELETE FROM bird_event WHERE id = ? AND bird_id = ?")) {
                    statement.setLong(1, created);
                    statement.setLong(2, sample.birdId());
                    ctx.require(statement.executeUpdate() == 1, "The temporary benchmark control must be cleaned up");
                }
            }
        });

        CaptureMethodOption method = ctx.catalog().findCaptureMethods().stream().findFirst().orElseThrow();
        try {
            ctx.measure("mutation", "rename-capture-method", () -> {
                String name = "Synthetic benchmark method " + (sequence.incrementAndGet() % 2);
                return ctx.catalog().saveCaptureMethod(method.id(), name);
            });
            ctx.measure("mutation", "archive-or-reactivate-capture-method", () -> {
                ctx.catalog().setCaptureMethodActive(method.id(), sequence.incrementAndGet() % 2 == 0);
                return method.id();
            });
        } finally {
            ctx.catalog().saveCaptureMethod(method.id(), method.name());
            ctx.catalog().setCaptureMethodActive(method.id(), method.active());
        }

        PlaceSummary place = ctx.catalog().findPlaceSummaries().stream().findFirst().orElseThrow();
        try {
            ctx.measure("mutation", "update-existing-place", () -> ctx.catalog().updatePlace(place.id(),
                    placeInput(place, "Synthetic benchmark place note " + (sequence.incrementAndGet() % 2))));
        } finally {
            ctx.catalog().updatePlace(place.id(), placeInput(place, place.notes()));
        }
        ctx.require(ctx.events().findDashboardStats().eventCount() == ctx.eventCount(),
                "Mutation benchmarks must leave the synthetic event count unchanged");
    }

    private static BirdEventInput eventInput(BirdEventDetail detail, EventType type, String observations) {
        return new BirdEventInput(detail.ringNumber(), detail.speciesId(), type, detail.eventDate(),
                detail.eventTime(), detail.placeId(), detail.locationText(), detail.sexCode(),
                detail.ageEuringCode(), detail.fatScore(), detail.muscleScore(), detail.ringerInitials(),
                detail.status(), detail.reproductiveStatus(), detail.moultIntensity(), detail.moultExtension(),
                detail.birdCondition(), detail.returnStatus(), detail.wing(), detail.p3(), detail.torso(),
                detail.weight(), observations, detail.clouds(), detail.rain(), detail.thermalSensation(),
                detail.wind(), detail.captureType(), detail.dead(), detail.captureMethodId());
    }

    private static PlaceInput placeInput(PlaceSummary place, String notes) {
        return new PlaceInput(place.name(), place.locality(), place.autonomousCommunity(), place.country(),
                place.latitude(), place.longitude(), notes, place.favorite(), place.isDefault());
    }

    private static void writeQueryPlans(BenchmarkContext ctx) throws Exception {
        String timeline = constant("TIMELINE_SELECT");
        String archive;
        try {
            archive = constant("TIMELINE_ARCHIVE_SELECT");
        } catch (NoSuchFieldException originalQuery) {
            archive = timeline;
        }
        String order = constant("TIMELINE_ORDER");
        List<String[]> queries = new ArrayList<>();
        queries.add(new String[]{"timeline-all", archive + order});
        queries.add(new String[]{"timeline-recent-24", timeline + order + " LIMIT 24"});
        queries.add(new String[]{"timeline-single-bird", timeline + " WHERE e.bird_id = " + ctx.birdId() + " " + order});
        String ids = ctx.reportIds(Math.min(500, ctx.reportLimit())).stream()
                .map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        queries.add(new String[]{"report-projection", constant("REPORT_SELECT") + " WHERE e.id IN (" + ids + ")"});
        StringBuilder output = new StringBuilder("SQLite EXPLAIN QUERY PLAN; synthetic fixture only.\n");
        try (Connection connection = Database.getReadOnlyConnection(ctx.databaseFile())) {
            for (String[] query : queries) {
                output.append('\n').append(query[0]).append('\n').append(query[1]).append('\n');
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query[1])) {
                    while (rows.next()) {
                        output.append(rows.getInt("id")).append(" | ")
                                .append(rows.getInt("parent")).append(" | ")
                                .append(rows.getString("detail")).append('\n');
                    }
                }
            }
        }
        Files.createDirectories(ctx.workDirectory());
        Files.writeString(ctx.workDirectory().resolve("database-query-plans.txt"), output, StandardCharsets.UTF_8);
    }

    private static String constant(String name) throws Exception {
        Field field = BirdEventRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}

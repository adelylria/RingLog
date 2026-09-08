package com.adelylria.ringlog.importexport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.UUID;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.repository.MigrationConflictRepository;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

/** Field-level resolution semantics and immutable evidence coverage. */
public final class ConflictResolutionServiceTest {

    private static final Path REAL_FIXTURE = LegacyV52TestFixture.path();

    private ConflictResolutionServiceTest() {
    }

    public static void resolvesCanonicalAlternativeManualAndPendingWithoutLosingEvidence()
            throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Conflict resolution legacy integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-conflict-resolution-");
        Path database = root.resolve("ringlog.db");
        try {
            try (ImportPlan plan = new ImportCoordinator().analyze(REAL_FIXTURE)) {
                new ImportTransactionService(database.toString(), root.resolve("media"))
                        .execute(plan);
            }
            ConflictResolutionService service = new ConflictResolutionService(database.toString());
            MigrationConflictRepository repository = new MigrationConflictRepository(
                    database.toString()
            );
            require(repository.findPending().size() == 7,
                    "The repository must expose all pending conflicts to the UI");

            Conflict canonical = conflict(database, "V6336", "event_time");
            String canonicalEventValue = eventValue(database, canonical.eventId(), "event_time");
            service.resolve(canonical.id(), ResolutionRequest.canonical("Confirmado en campo"));
            require(canonicalEventValue.equals(eventValue(
                            database, canonical.eventId(), "event_time")),
                    "CANONICAL must keep the current operational value");
            requireResolved(database, canonical, "CANONICAL", canonical.canonicalValue());
            require("OK".equals(eventValue(database, canonical.eventId(), "review_status")),
                    "Resolving the last pending conflict must mark the event OK");
            require(repository.findPending().size() == 6,
                    "Resolved conflicts must disappear from the pending repository view");

            Conflict alternative = conflict(database, "V6494", "event_time");
            service.resolve(alternative.id(), ResolutionRequest.alternative(null));
            require(alternative.alternativeValue().equals(eventValue(
                            database, alternative.eventId(), "event_time")),
                    "ALTERNATIVE must update the mapped operational field");
            requireResolved(database, alternative, "ALTERNATIVE", alternative.alternativeValue());

            Conflict manual = conflict(database, "V33923", "event_date");
            service.resolve(manual.id(), ResolutionRequest.manual("2024-12-29", "Acta revisada"));
            require("2024-12-29".equals(eventValue(
                            database, manual.eventId(), "event_date")),
                    "MANUAL_VALUE must store the validated manual date");
            requireResolved(database, manual, "MANUAL_VALUE", "2024-12-29");

            Conflict pending = conflict(database, "V25993", "event_date");
            String pendingValue = eventValue(database, pending.eventId(), "event_date");
            service.resolve(pending.id(), ResolutionRequest.pending("Lo revisaré después"));
            require(pendingValue.equals(eventValue(database, pending.eventId(), "event_date")),
                    "PENDING must not alter the event");
            try (Connection connection = Database.getConnection(database.toString());
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT status, resolution_type, resolution_value, resolved_at
                         FROM migration_conflict WHERE id = ?
                         """)) {
                statement.setLong(1, pending.id());
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next()
                                    && "PENDING_REVIEW".equals(result.getString(1))
                                    && result.getString(2) == null
                                    && result.getString(3) == null
                                    && result.getString(4) == null,
                            "PENDING must preserve unresolved workflow state");
                }
            }
            require("REVIEW".equals(eventValue(database, pending.eventId(), "review_status")),
                    "A pending conflict must keep its event under review");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void eventStaysReviewWhileAnotherConflictIsPending() throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Multi-conflict legacy integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-conflict-multiple-");
        Path database = root.resolve("ringlog.db");
        try {
            try (ImportPlan plan = new ImportCoordinator().analyze(REAL_FIXTURE)) {
                new ImportTransactionService(database.toString(), root.resolve("media"))
                        .execute(plan);
            }
            Conflict conflict = conflict(database, "V25943", "event_date");
            try (Connection connection = Database.getConnection(database.toString());
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO migration_conflict(
                             stable_key, conflict_key, import_batch_id, event_id, ring_number,
                             conflict_type, event_type, field_name, canonical_value,
                             alternative_value, status
                         ) SELECT ?, ?, import_batch_id, event_id, ring_number,
                                  conflict_type, event_type, 'event_time', '10:00', '10:05',
                                  'PENDING_REVIEW'
                           FROM migration_conflict WHERE id = ?
                         """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, "TEST-SECOND-PENDING");
                statement.setLong(3, conflict.id());
                require(statement.executeUpdate() == 1, "Expected a second pending field conflict");
            }

            new ConflictResolutionService(database.toString()).resolve(
                    conflict.id(), ResolutionRequest.canonical(null)
            );
            require("REVIEW".equals(eventValue(database, conflict.eventId(), "review_status")),
                    "An event must stay REVIEW while any conflict remains pending");
        } finally {
            deleteDirectory(root);
        }
    }

    private static void requireResolved(
            Path database,
            Conflict original,
            String type,
            String value
    ) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, resolution_type, resolution_value, resolved_at,
                            canonical_value, alternative_value,
                            canonical_source_reference, alternative_source_reference,
                            canonical_snapshot, alternative_snapshot, original_note
                     FROM migration_conflict WHERE id = ?
                     """)) {
            statement.setLong(1, original.id());
            try (ResultSet result = statement.executeQuery()) {
                require(result.next()
                                && "RESOLVED".equals(result.getString(1))
                                && type.equals(result.getString(2))
                                && value.equals(result.getString(3))
                                && result.getString(4) != null,
                        "Resolution workflow fields must be persisted");
                require(equal(original.canonicalValue(), result.getString(5))
                                && equal(original.alternativeValue(), result.getString(6))
                                && equal(original.canonicalSource(), result.getString(7))
                                && equal(original.alternativeSource(), result.getString(8))
                                && equal(original.canonicalSnapshot(), result.getString(9))
                                && equal(original.alternativeSnapshot(), result.getString(10))
                                && equal(original.note(), result.getString(11)),
                        "Resolving must never modify original conflict evidence");
            }
        }
    }

    private static Conflict conflict(Path database, String ring, String field) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, event_id, canonical_value, alternative_value,
                            canonical_source_reference, alternative_source_reference,
                            canonical_snapshot, alternative_snapshot, original_note
                     FROM migration_conflict
                     WHERE ring_number = ? AND field_name = ? AND status = 'PENDING_REVIEW'
                     """)) {
            statement.setString(1, ring);
            statement.setString(2, field);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing pending conflict for " + ring + " / " + field);
                return new Conflict(
                        result.getLong(1), result.getLong(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8), result.getString(9)
                );
            }
        }
    }

    private static String eventValue(Path database, long eventId, String column) throws Exception {
        require(java.util.Set.of("event_date", "event_time", "review_status").contains(column),
                "Unsafe test column");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT " + column + " FROM bird_event WHERE id = " + eventId
             )) {
            require(result.next(), "Missing event " + eventId);
            return result.getString(1);
        }
    }

    private static boolean equal(String left, String right) {
        return java.util.Objects.equals(left, right);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Conflict(
            long id,
            long eventId,
            String canonicalValue,
            String alternativeValue,
            String canonicalSource,
            String alternativeSource,
            String canonicalSnapshot,
            String alternativeSnapshot,
            String note
    ) {
    }

    public static void main(String[] args) throws Exception {
        resolvesCanonicalAlternativeManualAndPendingWithoutLosingEvidence();
        eventStaysReviewWhileAnotherConflictIsPending();
        System.out.println("ConflictResolutionServiceTest: PASS");
    }
}

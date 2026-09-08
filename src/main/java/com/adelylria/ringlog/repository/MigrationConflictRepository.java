package com.adelylria.ringlog.repository;

import java.sql.SQLException;
import java.util.List;

import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.database.entity.MigrationConflictEntity;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.support.DatabaseResults;

/** Read-only conflict projection used by the review workflow. */
public final class MigrationConflictRepository {

    private final DatabaseAccess databaseAccess;

    public MigrationConflictRepository(String databaseFile) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos de RingLog.");
        }
        this.databaseAccess = new WritableDatabaseAccess(databaseFile);
    }

    public MigrationConflictRepository(DatabaseAccess databaseAccess) {
        this.databaseAccess = java.util.Objects.requireNonNull(databaseAccess, "databaseAccess");
    }

    public List<ConflictRecord> findPending() {
        String sql = """
                SELECT c.id, c.stable_key, c.event_id, c.ring_number,
                       c.conflict_type, c.event_type, c.field_name,
                       c.canonical_source_reference, c.canonical_value,
                       c.alternative_source_reference, c.alternative_value,
                       c.canonical_snapshot, c.alternative_snapshot,
                       c.original_note, c.status,
                       e.event_date, e.event_time
                FROM migration_conflict c
                LEFT JOIN bird_event e ON e.id = c.event_id
                WHERE c.status = 'PENDING_REVIEW'
                ORDER BY e.event_date DESC, e.event_time DESC,
                         c.ring_number COLLATE NOCASE, c.field_name
                """;
        try {
            return databaseAccess.withOrm(source -> {
                Dao<MigrationConflictEntity, Long> dao = DaoManager.createDao(
                        source, MigrationConflictEntity.class
                );
                return OrmQuery.list(dao, sql, MigrationConflictRepository::map);
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando los conflictos pendientes", exception);
        }
    }

    private static ConflictRecord map(DatabaseResults result) throws SQLException {
        return new ConflictRecord(
                getLong(result, "id"),
                getString(result, "stable_key"),
                getNullableLong(result, "event_id"),
                getString(result, "ring_number"),
                getString(result, "conflict_type"),
                getString(result, "event_type"),
                getString(result, "field_name"),
                getString(result, "canonical_source_reference"),
                getString(result, "canonical_value"),
                getString(result, "alternative_source_reference"),
                getString(result, "alternative_value"),
                getString(result, "canonical_snapshot"),
                getString(result, "alternative_snapshot"),
                getString(result, "original_note"),
                getString(result, "status"),
                getString(result, "event_date"),
                getString(result, "event_time")
        );
    }

    private static String getString(DatabaseResults result, String column) throws SQLException {
        return result.getString(result.findColumn(column));
    }

    private static long getLong(DatabaseResults result, String column) throws SQLException {
        return result.getLong(result.findColumn(column));
    }

    private static Long getNullableLong(DatabaseResults result, String column) throws SQLException {
        int index = result.findColumn(column);
        long value = result.getLong(index);
        return result.wasNull(index) ? null : value;
    }

    public record ConflictRecord(
            long id,
            String stableKey,
            Long eventId,
            String ringNumber,
            String conflictType,
            String eventType,
            String fieldName,
            String canonicalSourceReference,
            String canonicalValue,
            String alternativeSourceReference,
            String alternativeValue,
            String canonicalSnapshot,
            String alternativeSnapshot,
            String originalNote,
            String status,
            String eventDate,
            String eventTime
    ) {
    }
}

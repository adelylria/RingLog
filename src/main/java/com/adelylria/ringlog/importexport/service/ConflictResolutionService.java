package com.adelylria.ringlog.importexport.service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.MigrationConflictEntity;
import com.adelylria.ringlog.importexport.ImportExecutionException;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.support.ConnectionSource;

/** Resolves one authoritative field conflict while retaining all original evidence. */
public final class ConflictResolutionService {

    private final String databaseFile;
    private final DataMutationCoordinator mutationCoordinator;

    public ConflictResolutionService(String databaseFile) {
        this(databaseFile, new DataMutationCoordinator());
    }

    public ConflictResolutionService(
            String databaseFile,
            DataMutationCoordinator mutationCoordinator
    ) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos de RingLog.");
        }
        this.databaseFile = databaseFile;
        this.mutationCoordinator = java.util.Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
    }

    public void resolve(long conflictId, ResolutionRequest request)
            throws ImportExecutionException {
        if (conflictId <= 0 || request == null) {
            throw new IllegalArgumentException("Indica un conflicto y una resolución válidos.");
        }
        try (DataMutationCoordinator.Lease ignored =
                     mutationCoordinator.acquireMutation()) {
            Database.initialize(databaseFile);
            Database.withOrm(databaseFile, source ->
                    TransactionManager.callInTransaction(source, () -> {
                        resolveInTransaction(source, conflictId, request);
                        return null;
                    })
            );
        } catch (SQLException exception) {
            throw new ImportExecutionException(
                    "No se pudo guardar la resolución del conflicto.", exception
            );
        }
    }

    private static void resolveInTransaction(
            ConnectionSource source,
            long conflictId,
            ResolutionRequest request
    ) throws SQLException {
        Dao<MigrationConflictEntity, Long> conflictDao = DaoManager.createDao(
                source, MigrationConflictEntity.class
        );
        MigrationConflictEntity conflict = conflictDao.queryForId(conflictId);
        if (conflict == null) {
            throw new SQLException("El conflicto ya no existe.");
        }
        if (!"PENDING_REVIEW".equals(conflict.getStatus())) {
            throw new SQLException("El conflicto ya había sido resuelto.");
        }
        if (conflict.getEventId() == null) {
            throw new SQLException("El conflicto no está vinculado a un evento.");
        }

        Dao<BirdEventEntity, Long> eventDao = DaoManager.createDao(
                source, BirdEventEntity.class
        );
        BirdEventEntity event = eventDao.queryForId(conflict.getEventId());
        if (event == null) {
            throw new SQLException("El evento del conflicto ya no existe.");
        }

        if (request.type() == ResolutionRequest.Type.PENDING) {
            event.setReviewStatus("REVIEW");
            eventDao.update(event);
            return;
        }

        String value = switch (request.type()) {
            case CANONICAL -> validated(conflict.getFieldName(), conflict.getCanonicalValue());
            case ALTERNATIVE -> validated(
                    conflict.getFieldName(), conflict.getAlternativeValue()
            );
            case MANUAL_VALUE -> validated(conflict.getFieldName(), request.value());
            case PENDING -> throw new IllegalStateException("PENDING ya fue tratado.");
        };

        if (request.type() != ResolutionRequest.Type.CANONICAL) {
            apply(event, conflict.getFieldName(), value);
            eventDao.update(event);
        }
        conflict.setStatus("RESOLVED");
        conflict.setResolutionType(request.type().name());
        conflict.setResolutionValue(value);
        conflict.setResolvedAt(Instant.now().toString());
        conflict.setResolutionNotes(request.notes());
        conflictDao.update(conflict);

        QueryBuilder<MigrationConflictEntity, Long> pending = conflictDao.queryBuilder();
        pending.where().eq("event_id", event.id()).and().eq("status", "PENDING_REVIEW");
        pending.setCountOf(true);
        event.setReviewStatus(conflictDao.countOf(pending.prepare()) == 0 ? "OK" : "REVIEW");
        eventDao.update(event);
    }

    private static String validated(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El valor elegido está vacío.");
        }
        try {
            return switch (field) {
                case "event_date" -> LocalDate.parse(value.trim()).toString();
                case "event_time" -> LocalTime.parse(value.trim()).toString();
                default -> throw new IllegalArgumentException(
                        "RingLog todavía no permite resolver el campo: " + field
                );
            };
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException(
                    "El valor no tiene un formato válido para " + field + ".", exception
            );
        }
    }

    private static void apply(BirdEventEntity event, String field, String value) {
        switch (field) {
            case "event_date" -> event.setEventDate(value);
            case "event_time" -> event.setEventTime(value);
            default -> throw new IllegalArgumentException(
                    "RingLog todavía no permite resolver el campo: " + field
            );
        }
    }
}

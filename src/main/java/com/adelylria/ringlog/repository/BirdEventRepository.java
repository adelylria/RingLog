package com.adelylria.ringlog.repository;

import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.entity.BirdEntity;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.EventPhotoEntity;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventListItem;
import com.adelylria.ringlog.model.view.BirdEventReportRow;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.model.view.BirdLookup;
import com.adelylria.ringlog.model.view.DashboardStats;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.DataPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.support.DatabaseResults;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class BirdEventRepository {

    private static final String TIMELINE_SELECT = """
            SELECT
                e.id,
                e.bird_id,
                b.ring_number,
                e.event_type,
                e.event_date,
                e.event_time,
                COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS species,
                COALESCE(p.name, e.location_text) AS place,
                e.observations,
                e.bird_condition,
                e.review_status,
                (
                    SELECT ep.file_path
                    FROM event_photo ep
                    WHERE ep.event_id = e.id
                    ORDER BY ep.id
                    LIMIT 1
                ) AS photo_path
            FROM bird_event e
            JOIN bird b ON b.id = e.bird_id
            JOIN species s ON s.id = b.species_id
            LEFT JOIN place p ON p.id = e.place_id
            """;

    private static final String TIMELINE_ORDER = """
            ORDER BY e.event_date DESC,
                     e.event_time DESC,
                     e.id DESC
            """;
    private static final int REPORT_QUERY_BATCH = 500;
    private static final String REPORT_SELECT = """
            SELECT
                e.id,
                e.bird_id,
                b.ring_number,
                COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS species,
                s.code AS species_code,
                s.scientific_name AS species_scientific_name,
                s.common_name AS species_common_name,
                e.event_type,
                e.event_date,
                e.event_time,
                p.name AS place,
                p.locality,
                e.location_text,
                e.capture_type,
                e.sex_code,
                e.age_euring_code,
                e.fat_score,
                e.muscle_score,
                e.ringer_initials,
                e.status,
                e.reproductive_status,
                e.moult_intensity,
                e.moult_extension,
                e.bird_condition,
                e.return_status,
                e.wing,
                e.p3,
                e.torso,
                e.weight,
                e.clouds,
                e.rain,
                e.thermal_sensation,
                e.wind,
                p.latitude,
                p.longitude,
                e.observations,
                e.is_dead,
                e.source_name,
                e.source_reference,
                (SELECT COUNT(*) FROM event_photo ep WHERE ep.event_id = e.id)
                    AS photo_count
            FROM bird_event e
            JOIN bird b ON b.id = e.bird_id
            JOIN species s ON s.id = b.species_id
            LEFT JOIN place p ON p.id = e.place_id
            """;

    private final DatabaseAccess databaseAccess;
    private final MediaPathResolver mediaResolver;
    private final DataMutationCoordinator mutationCoordinator;

    public BirdEventRepository() {
        this(AppPaths.production());
    }

    public BirdEventRepository(AppPaths paths) {
        this(
                new WritableDatabaseAccess(paths.databasePath()),
                new MediaPathResolver(paths),
                new DataMutationCoordinator()
        );
    }

    public BirdEventRepository(DataPaths paths, DatabaseAccess databaseAccess) {
        this(paths, databaseAccess, new DataMutationCoordinator());
    }

    public BirdEventRepository(
            DataPaths paths,
            DatabaseAccess databaseAccess,
            DataMutationCoordinator mutationCoordinator
    ) {
        this(databaseAccess, new MediaPathResolver(paths), mutationCoordinator);
    }

    public BirdEventRepository(String databaseFile) {
        this(
                new WritableDatabaseAccess(databaseFile),
                new MediaPathResolver(AppPaths.forDatabase(java.nio.file.Path.of(databaseFile))),
                new DataMutationCoordinator()
        );
    }

    public BirdEventRepository(String databaseFile, MediaPathResolver mediaResolver) {
        this(
                new WritableDatabaseAccess(databaseFile),
                mediaResolver,
                new DataMutationCoordinator()
        );
    }

    public BirdEventRepository(DatabaseAccess databaseAccess, MediaPathResolver mediaResolver) {
        this(databaseAccess, mediaResolver, new DataMutationCoordinator());
    }

    public BirdEventRepository(
            DatabaseAccess databaseAccess,
            MediaPathResolver mediaResolver,
            DataMutationCoordinator mutationCoordinator
    ) {
        this.databaseAccess = java.util.Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.mediaResolver = java.util.Objects.requireNonNull(mediaResolver, "mediaResolver");
        this.mutationCoordinator = java.util.Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
    }

    public List<BirdEventTimelineItem> findTimeline() {
        return loadTimeline(null, null);
    }

    public List<BirdEventTimelineItem> findRecentTimeline(int maximumItems) {
        if (maximumItems < 1) {
            throw new IllegalArgumentException(
                    "El número de eventos recientes debe ser mayor que cero."
            );
        }
        return loadTimeline(null, maximumItems);
    }

    public List<BirdEventTimelineItem> findEventsByBird(long birdId) {
        return loadTimeline(birdId, null);
    }

    public List<BirdEventListItem> findAll() {
        return findTimeline().stream()
                .map(item -> new BirdEventListItem(
                        item.id(),
                        item.ringNumber(),
                        item.eventDate(),
                        item.eventType(),
                        item.species(),
                        item.place()
                ))
                .toList();
    }

    public Optional<BirdLookup> findBirdByRingNumber(String ringNumber) {
        if (ringNumber == null || ringNumber.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT
                    b.id,
                    b.ring_number,
                    b.species_id,
                    COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS species,
                    COUNT(e.id) AS event_count
                FROM bird b
                JOIN species s ON s.id = b.species_id
                LEFT JOIN bird_event e ON e.bird_id = b.id
                WHERE b.ring_number = ? COLLATE NOCASE
                GROUP BY b.id, b.ring_number, b.species_id, species
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<BirdEntity, Long> dao = birdDao(source);
                return OrmQuery.list(
                        dao,
                        sql,
                        result -> new BirdLookup(
                                getLong(result, "id"),
                                getString(result, "ring_number"),
                                getLong(result, "species_id"),
                                getString(result, "species"),
                                getLong(result, "event_count")
                        ),
                        ringNumber.trim()
                ).stream().findFirst();
            });
        } catch (SQLException exception) {
            throw databaseError("Error buscando la anilla", exception);
        }
    }

    public Optional<BirdEventDetail> findDetail(long eventId) {
        String sql = """
                SELECT
                    e.id,
                    e.bird_id,
                    b.species_id,
                    e.place_id,
                    b.ring_number,
                    COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS species,
                    e.event_type,
                    e.event_date,
                    e.event_time,
                    p.name AS place,
                    p.locality,
                    e.location_text,
                    e.capture_type,
                    e.sex_code,
                    e.age_euring_code,
                    e.fat_score,
                    e.muscle_score,
                    e.ringer_initials,
                    e.status,
                    e.reproductive_status,
                    e.moult_intensity,
                    e.moult_extension,
                    e.bird_condition,
                    e.return_status,
                    e.wing,
                    e.p3,
                    e.torso,
                    e.weight,
                    e.clouds,
                    e.rain,
                    e.thermal_sensation,
                    e.wind,
                    p.latitude,
                    p.longitude,
                    e.observations,
                    e.is_dead,
                    e.review_status,
                    e.review_note
                FROM bird_event e
                JOIN bird b ON b.id = e.bird_id
                JOIN species s ON s.id = b.species_id
                LEFT JOIN place p ON p.id = e.place_id
                WHERE e.id = ?
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<BirdEventEntity, Long> eventDao = eventDao(source);
                Dao<EventPhotoEntity, Long> photoDao = photoDao(source);
                List<String> photos = findPhotoPaths(photoDao, eventId);
                return OrmQuery.list(
                        eventDao,
                        sql,
                        result -> readDetail(result, photos),
                        Long.toString(eventId)
                ).stream().findFirst();
            });
        } catch (SQLException exception) {
            throw databaseError("Error cargando el detalle del evento", exception);
        }
    }

    public List<BirdEventReportRow> findReportRows(List<Long> eventIds) {
        if (eventIds == null) {
            throw new IllegalArgumentException("Falta la selección que se quiere exportar.");
        }
        List<Long> requestedIds = List.copyOf(eventIds);
        if (requestedIds.isEmpty()) {
            return List.of();
        }
        if (requestedIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("La selección contiene un registro no válido.");
        }

        try (Connection connection = databaseAccess.openConnection()) {
            connection.setAutoCommit(false);
            Map<Long, BirdEventReportRow> rowsById = new LinkedHashMap<>();
            try {
                for (int offset = 0; offset < requestedIds.size();
                     offset += REPORT_QUERY_BATCH) {
                    int end = Math.min(requestedIds.size(), offset + REPORT_QUERY_BATCH);
                    loadReportBatch(
                            connection,
                            requestedIds.subList(offset, end),
                            rowsById
                    );
                }

                List<BirdEventReportRow> ordered = new ArrayList<>(requestedIds.size());
                for (long eventId : requestedIds) {
                    BirdEventReportRow row = rowsById.get(eventId);
                    if (row == null) {
                        throw new SQLException(
                                "El registro " + eventId + " ya no está disponible."
                        );
                    }
                    ordered.add(row);
                }
                connection.rollback();
                return List.copyOf(ordered);
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError("Error preparando los registros del informe", exception);
        }
    }

    private static void loadReportBatch(
            Connection connection,
            List<Long> eventIds,
            Map<Long, BirdEventReportRow> destination
    ) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        String sql = REPORT_SELECT + "WHERE e.id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < eventIds.size(); index++) {
                statement.setLong(index + 1, eventIds.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    BirdEventDetail detail = readReportDetail(result);
                    destination.put(detail.id(), new BirdEventReportRow(
                            detail,
                            result.getString("species_code"),
                            result.getString("species_scientific_name"),
                            result.getString("species_common_name"),
                            result.getString("source_name"),
                            result.getString("source_reference"),
                            result.getInt("photo_count")
                    ));
                }
            }
        }
    }

    private static BirdEventDetail readReportDetail(ResultSet result)
            throws SQLException {
        return new BirdEventDetail(
                result.getLong("id"),
                result.getLong("bird_id"),
                result.getString("ring_number"),
                result.getString("species"),
                EventType.fromDatabase(result.getString("event_type")),
                result.getString("event_date"),
                result.getString("event_time"),
                result.getString("place"),
                result.getString("locality"),
                result.getString("location_text"),
                result.getString("capture_type"),
                result.getString("sex_code"),
                result.getString("age_euring_code"),
                nullableJdbcInteger(result, "fat_score"),
                nullableJdbcInteger(result, "muscle_score"),
                result.getString("ringer_initials"),
                result.getString("status"),
                result.getString("reproductive_status"),
                result.getString("moult_intensity"),
                result.getString("moult_extension"),
                result.getString("bird_condition"),
                result.getString("return_status"),
                nullableJdbcDouble(result, "wing"),
                nullableJdbcDouble(result, "p3"),
                nullableJdbcDouble(result, "torso"),
                nullableJdbcDouble(result, "weight"),
                result.getString("clouds"),
                result.getString("rain"),
                result.getString("thermal_sensation"),
                result.getString("wind"),
                nullableJdbcDouble(result, "latitude"),
                nullableJdbcDouble(result, "longitude"),
                result.getString("observations"),
                result.getInt("is_dead") == 1,
                List.of()
        );
    }

    private static Integer nullableJdbcInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Double nullableJdbcDouble(ResultSet result, String column)
            throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    public DashboardStats findDashboardStats() {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM bird_event) AS event_count,
                    (
                        SELECT COUNT(DISTINCT b.species_id)
                        FROM bird_event e
                        JOIN bird b ON b.id = e.bird_id
                    ) AS species_count,
                    (
                        SELECT COUNT(DISTINCT place_id)
                        FROM bird_event
                        WHERE place_id IS NOT NULL
                    ) AS place_count,
                    MAX(event_date) AS latest_event_date
                FROM bird_event
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<BirdEventEntity, Long> dao = eventDao(source);
                return OrmQuery.list(dao, sql, result -> new DashboardStats(
                        getLong(result, "event_count"),
                        getLong(result, "species_count"),
                        getLong(result, "place_count"),
                        getString(result, "latest_event_date")
                )).stream().findFirst().orElseGet(
                        () -> new DashboardStats(0, 0, 0, null)
                );
            });
        } catch (SQLException exception) {
            throw databaseError("Error cargando las estadísticas", exception);
        }
    }

    public long insert(BirdEventInput input) {
        validateInput(input);

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source ->
                    TransactionManager.callInTransaction(source, () -> {
                        Dao<BirdEntity, Long> birds = birdDao(source);
                        Dao<BirdEventEntity, Long> events = eventDao(source);
                        long birdId = resolveBirdId(birds, input);
                        BirdEventEntity event = new BirdEventEntity(birdId, input);
                        events.create(event);
                        return event.id();
                    })
            );
        } catch (SQLException exception) {
            IllegalArgumentException validation = findValidationError(exception);
            if (validation != null) {
                throw validation;
            }
            throw databaseError("Error guardando el evento", exception);
        }
    }

    public void update(long eventId, BirdEventInput input) {
        validateInput(input);
        if (eventId < 1) {
            throw new IllegalArgumentException("El registro que quieres editar no es válido.");
        }
        if (input.speciesId() == null) {
            throw new IllegalArgumentException("Selecciona la especie del ave.");
        }

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            databaseAccess.withOrm(source ->
                    TransactionManager.callInTransaction(source, () -> {
                        Dao<BirdEntity, Long> birds = birdDao(source);
                        Dao<BirdEventEntity, Long> events = eventDao(source);
                        BirdEventEntity event = events.queryForId(eventId);
                        if (event == null) {
                            throw new IllegalArgumentException(
                                    "El registro ya no está disponible."
                            );
                        }
                        BirdEntity bird = birds.queryForId(event.getBirdId());
                        if (bird == null) {
                            throw new IllegalArgumentException(
                                    "No encontramos el ave asociada al registro."
                            );
                        }

                        String ringNumber = normalizeRing(input.ringNumber());
                        Optional<Long> existingBird = findBirdId(birds, ringNumber);
                        if (existingBird.isPresent() && existingBird.get() != bird.id()) {
                            throw new IllegalArgumentException(
                                    "Ya existe otra ave con ese número de anilla."
                            );
                        }

                        bird.setRingNumber(ringNumber);
                        bird.setSpeciesId(input.speciesId());
                        birds.update(bird);
                        event.apply(input);
                        events.update(event);
                        return null;
                    })
            );
        } catch (SQLException exception) {
            IllegalArgumentException validation = findValidationError(exception);
            if (validation != null) {
                throw validation;
            }
            throw databaseError("Error actualizando el evento", exception);
        }
    }

    private List<BirdEventTimelineItem> loadTimeline(
            Long birdId,
            Integer maximumItems
    ) {
        String sql = TIMELINE_SELECT
                + (birdId == null ? "" : "WHERE e.bird_id = ?\n")
                + TIMELINE_ORDER
                + (maximumItems == null ? "" : "LIMIT ?\n");
        List<String> arguments = new ArrayList<>();
        if (birdId != null) {
            arguments.add(Long.toString(birdId));
        }
        if (maximumItems != null) {
            arguments.add(Integer.toString(maximumItems));
        }

        try {
            return databaseAccess.withOrm(source -> {
                Dao<BirdEventEntity, Long> dao = eventDao(source);
                return OrmQuery.list(
                        dao,
                        sql,
                        result -> new BirdEventTimelineItem(
                                getLong(result, "id"),
                                getLong(result, "bird_id"),
                                getString(result, "ring_number"),
                                EventType.fromDatabase(getString(result, "event_type")),
                                getString(result, "event_date"),
                                getString(result, "event_time"),
                                getString(result, "species"),
                                getString(result, "place"),
                                getString(result, "observations"),
                                getString(result, "bird_condition"),
                                resolvedPhoto(getString(result, "photo_path")),
                                getString(result, "review_status")
                        ),
                        arguments.toArray(String[]::new)
                );
            });
        } catch (SQLException exception) {
            throw databaseError("Error cargando los eventos", exception);
        }
    }

    private long resolveBirdId(Dao<BirdEntity, Long> dao, BirdEventInput input)
            throws SQLException {
        Optional<Long> existingBird = findBirdId(dao, input.ringNumber());
        if (existingBird.isPresent()) {
            if (input.eventType() == EventType.RINGING) {
                throw new IllegalArgumentException(
                        "La anilla ya existe; elige Control o Recuperación."
                );
            }
            return existingBird.get();
        }

        if (input.eventType() != EventType.RINGING) {
            throw new IllegalArgumentException(
                    "La anilla no existe; primero debe registrarse un anillamiento."
            );
        }
        if (input.speciesId() == null) {
            throw new IllegalArgumentException(
                    "Selecciona la especie para la nueva anilla."
            );
        }
        BirdEntity bird = new BirdEntity(
                normalizeRing(input.ringNumber()),
                input.speciesId()
        );
        dao.create(bird);
        return bird.id();
    }

    private Optional<Long> findBirdId(Dao<BirdEntity, Long> dao, String ringNumber)
            throws SQLException {
        QueryBuilder<BirdEntity, Long> query = dao.queryBuilder();
        query.selectColumns(BirdEntity.ID);
        query.where().eq(BirdEntity.RING_NUMBER, normalizeRing(ringNumber));
        BirdEntity bird = dao.queryForFirst(query.prepare());
        return bird == null ? Optional.empty() : Optional.of(bird.id());
    }

    private List<String> findPhotoPaths(
            Dao<EventPhotoEntity, Long> dao,
            long eventId
    ) throws SQLException {
        QueryBuilder<EventPhotoEntity, Long> query = dao.queryBuilder();
        query.selectColumns(
                EventPhotoEntity.ID,
                EventPhotoEntity.EVENT_ID,
                EventPhotoEntity.FILE_PATH
        );
        query.where().eq(EventPhotoEntity.EVENT_ID, eventId);
        query.orderBy(EventPhotoEntity.ID, true);
        return dao.query(query.prepare()).stream()
                .map(EventPhotoEntity::filePath)
                .map(this::resolvedPhoto)
                .toList();
    }

    private String resolvedPhoto(String reference) {
        return reference == null ? null
                : mediaResolver.resolveEventPhoto(reference).toString();
    }

    private static Dao<BirdEntity, Long> birdDao(ConnectionSource source)
            throws SQLException {
        return DaoManager.createDao(source, BirdEntity.class);
    }

    private static Dao<BirdEventEntity, Long> eventDao(ConnectionSource source)
            throws SQLException {
        return DaoManager.createDao(source, BirdEventEntity.class);
    }

    private static Dao<EventPhotoEntity, Long> photoDao(ConnectionSource source)
            throws SQLException {
        return DaoManager.createDao(source, EventPhotoEntity.class);
    }

    private static void validateInput(BirdEventInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Faltan los datos del evento.");
        }
        if (input.ringNumber() == null || input.ringNumber().isBlank()) {
            throw new IllegalArgumentException("Indica el número de anilla.");
        }
        if (input.eventType() == null) {
            throw new IllegalArgumentException("Selecciona el tipo de evento.");
        }
        if (input.eventDate() == null || input.eventDate().isBlank()) {
            throw new IllegalArgumentException("Indica la fecha del evento.");
        }
        validateMeasurement(input.wing(), "ala");
        validateMeasurement(input.p3(), "P3");
        validateMeasurement(input.torso(), "torso");
        validateMeasurement(input.weight(), "peso");
    }

    private static void validateMeasurement(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(
                    "La medida de " + name + " debe ser un número positivo."
            );
        }
    }

    private static String normalizeRing(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static IllegalArgumentException findValidationError(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException validation) {
                return validation;
            }
        }
        return null;
    }

    private BirdEventDetail readDetail(
            DatabaseResults result,
            List<String> photoPaths
    ) throws SQLException {
        return new BirdEventDetail(
                getLong(result, "id"),
                getLong(result, "bird_id"),
                getString(result, "ring_number"),
                getString(result, "species"),
                EventType.fromDatabase(getString(result, "event_type")),
                getString(result, "event_date"),
                getString(result, "event_time"),
                getString(result, "place"),
                getString(result, "locality"),
                getString(result, "location_text"),
                getString(result, "capture_type"),
                getString(result, "sex_code"),
                getString(result, "age_euring_code"),
                getInteger(result, "fat_score"),
                getInteger(result, "muscle_score"),
                getString(result, "ringer_initials"),
                getString(result, "status"),
                getString(result, "reproductive_status"),
                getString(result, "moult_intensity"),
                getString(result, "moult_extension"),
                getString(result, "bird_condition"),
                getString(result, "return_status"),
                getDouble(result, "wing"),
                getDouble(result, "p3"),
                getDouble(result, "torso"),
                getDouble(result, "weight"),
                getString(result, "clouds"),
                getString(result, "rain"),
                getString(result, "thermal_sensation"),
                getString(result, "wind"),
                getDouble(result, "latitude"),
                getDouble(result, "longitude"),
                getString(result, "observations"),
                getInt(result, "is_dead") == 1,
                getString(result, "review_status"),
                getString(result, "review_note"),
                photoPaths,
                getLong(result, "species_id"),
                getIntegerLong(result, "place_id")
        );
    }

    private static String getString(DatabaseResults result, String column)
            throws SQLException {
        return result.getString(result.findColumn(column));
    }

    private static int getInt(DatabaseResults result, String column)
            throws SQLException {
        return result.getInt(result.findColumn(column));
    }

    private static long getLong(DatabaseResults result, String column)
            throws SQLException {
        return result.getLong(result.findColumn(column));
    }

    private static Integer getInteger(DatabaseResults result, String column)
            throws SQLException {
        int index = result.findColumn(column);
        int value = result.getInt(index);
        return result.wasNull(index) ? null : value;
    }

    private static Double getDouble(DatabaseResults result, String column)
            throws SQLException {
        int index = result.findColumn(column);
        double value = result.getDouble(index);
        return result.wasNull(index) ? null : value;
    }

    private static Long getIntegerLong(DatabaseResults result, String column)
            throws SQLException {
        int index = result.findColumn(column);
        long value = result.getLong(index);
        return result.wasNull(index) ? null : value;
    }

    private static RepositoryException databaseError(String message, SQLException cause) {
        return new RepositoryException(message, cause);
    }
}

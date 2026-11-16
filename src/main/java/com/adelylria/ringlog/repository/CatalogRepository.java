package com.adelylria.ringlog.repository;

import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.database.entity.BirdEntity;
import com.adelylria.ringlog.database.entity.CaptureMethodEntity;
import com.adelylria.ringlog.database.entity.EnvironmentOptionEntity;
import com.adelylria.ringlog.database.entity.PlaceEntity;
import com.adelylria.ringlog.database.entity.SpeciesEntity;
import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.BirdOption;
import com.adelylria.ringlog.model.view.CaptureMethodOption;
import com.adelylria.ringlog.model.EnvironmentCategory;
import com.adelylria.ringlog.model.view.EnvironmentOption;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.model.view.SpeciesSummary;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.support.DatabaseResults;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

public class CatalogRepository {

    private final DatabaseAccess databaseAccess;
    private final DataMutationCoordinator mutationCoordinator;
    private final Object environmentCatalogLock = new Object();
    private List<EnvironmentOption> environmentOptions;
    private long environmentCatalogGeneration;
    private boolean environmentCatalogLoading;

    public CatalogRepository() {
        this(AppPaths.production().databasePath().toString());
    }

    public CatalogRepository(String databaseFile) {
        this(new WritableDatabaseAccess(databaseFile), new DataMutationCoordinator());
    }

    public CatalogRepository(DatabaseAccess databaseAccess) {
        this(databaseAccess, new DataMutationCoordinator());
    }

    public CatalogRepository(
            DatabaseAccess databaseAccess,
            DataMutationCoordinator mutationCoordinator
    ) {
        this.databaseAccess = java.util.Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.mutationCoordinator = java.util.Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
    }

    public List<SpeciesSummary> findSpeciesSummaries() {
        String sql = """
                SELECT
                    s.id,
                    COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS name,
                    s.code,
                    s.scientific_name,
                    s.common_name,
                    COUNT(e.id) AS event_count
                FROM species s
                LEFT JOIN bird b ON b.species_id = s.id
                LEFT JOIN bird_event e ON e.bird_id = b.id
                WHERE s.active = 1
                GROUP BY s.id, s.code, s.common_name, s.scientific_name
                ORDER BY event_count DESC, name ASC
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<SpeciesEntity, Long> dao = DaoManager.createDao(
                        source,
                        SpeciesEntity.class
                );
                return OrmQuery.list(dao, sql, result -> new SpeciesSummary(
                        getLong(result, "id"),
                        getString(result, "name"),
                        getString(result, "code"),
                        getString(result, "scientific_name"),
                        getString(result, "common_name"),
                        getLong(result, "event_count")
                ));
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando las especies", exception);
        }
    }

    public long insertSpecies(SpeciesInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Indica los datos de la especie.");
        }
        String scientificName = required(input.scientificName(),
                "Indica el nombre científico.");
        SpeciesEntity species = new SpeciesEntity(
                optional(input.code()),
                scientificName,
                optional(input.commonName())
        );

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source -> {
                Dao<SpeciesEntity, Long> dao = DaoManager.createDao(
                        source,
                        SpeciesEntity.class
                );
                dao.create(species);
                return species.id();
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new IllegalArgumentException(
                        "Ya existe una especie con ese nombre científico o código."
                );
            }
            throw new RepositoryException("Error guardando la especie", exception);
        }
    }

    public long updateSpecies(long speciesId, SpeciesInput input) {
        if (speciesId < 1) {
            throw new IllegalArgumentException("Falta la especie que quieres editar.");
        }
        if (input == null) {
            throw new IllegalArgumentException("Indica los datos de la especie.");
        }
        String scientificName = required(input.scientificName(),
                "Indica el nombre científico.");

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source -> {
                Dao<SpeciesEntity, Long> dao = DaoManager.createDao(
                        source,
                        SpeciesEntity.class
                );
                SpeciesEntity species = dao.queryForId(speciesId);
                if (species == null || !species.isActive()) {
                    throw new IllegalArgumentException(
                            "La especie ya no está disponible para editarla."
                    );
                }
                species.setCode(optional(input.code()));
                species.setScientificName(scientificName);
                species.setCommonName(optional(input.commonName()));
                dao.update(species);
                return species.id();
            });
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new IllegalArgumentException(
                        "Ya existe una especie con ese nombre científico o código."
                );
            }
            throw new RepositoryException("Error actualizando la especie", exception);
        }
    }

    public List<BirdOption> findBirdOptions() {
        String sql = """
                SELECT
                    b.id,
                    b.ring_number,
                    b.species_id,
                    COALESCE(NULLIF(s.common_name, ''), s.scientific_name) AS species
                FROM bird b
                JOIN species s ON s.id = b.species_id
                ORDER BY b.ring_number
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<BirdEntity, Long> dao = DaoManager.createDao(
                        source,
                        BirdEntity.class
                );
                return OrmQuery.list(dao, sql, result -> new BirdOption(
                        getLong(result, "id"),
                        getString(result, "ring_number"),
                        getLong(result, "species_id"),
                        getString(result, "species")
                ));
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando las aves", exception);
        }
    }

    public List<SpeciesOption> findSpeciesOptions() {
        try {
            return databaseAccess.withOrm(source -> {
                Dao<SpeciesEntity, Long> dao = DaoManager.createDao(
                        source,
                        SpeciesEntity.class
                );
                QueryBuilder<SpeciesEntity, Long> query = dao.queryBuilder();
                query.where().eq(SpeciesEntity.ACTIVE, true);
                return dao.query(query.prepare()).stream()
                        .map(species -> new SpeciesOption(
                                species.id(),
                                species.displayName()
                        ))
                        .sorted(Comparator.comparing(
                                SpeciesOption::name,
                                String.CASE_INSENSITIVE_ORDER
                        ))
                        .toList();
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando las especies", exception);
        }
    }

    public List<CaptureMethodOption> findCaptureMethodOptions() {
        return findCaptureMethods().stream().filter(CaptureMethodOption::active).toList();
    }

    public List<CaptureMethodOption> findCaptureMethods() {
        try {
            return databaseAccess.withOrm(source -> {
                Dao<CaptureMethodEntity, Long> dao = DaoManager.createDao(source, CaptureMethodEntity.class);
                return dao.queryForAll().stream()
                        .map(method -> new CaptureMethodOption(method.id(), method.getName(), method.isActive()))
                        .sorted(Comparator.comparing(CaptureMethodOption::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando los métodos de captura", exception);
        }
    }

    public long saveCaptureMethod(Long id, String name) {
        String normalized = required(name, "Indica el nombre del método de captura.")
                .replaceAll("\\s+", " ");
        if (id != null && id < 1) {
            throw new IllegalArgumentException("Falta el método de captura que quieres editar.");
        }
        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source -> TransactionManager.callInTransaction(source, () -> {
                Dao<CaptureMethodEntity, Long> dao = DaoManager.createDao(source, CaptureMethodEntity.class);
                for (CaptureMethodEntity other : dao.queryForAll()) {
                    if ((id == null || other.id() != id) && normalized.equalsIgnoreCase(other.getName())) {
                        throw new IllegalArgumentException("Ya existe un método de captura con ese nombre.");
                    }
                }
                CaptureMethodEntity method = id == null ? new CaptureMethodEntity(normalized) : dao.queryForId(id);
                if (method == null) {
                    throw new IllegalArgumentException("El método de captura ya no está disponible.");
                }
                method.setName(normalized);
                if (id == null) dao.create(method); else dao.update(method);
                return method.id();
            }));
        } catch (SQLException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof IllegalArgumentException validation) throw validation;
            }
            if (isConstraintViolation(exception)) {
                throw new IllegalArgumentException("Ya existe un método de captura con ese nombre.");
            }
            throw new RepositoryException("Error guardando el método de captura", exception);
        }
    }

    public void setCaptureMethodActive(long id, boolean active) {
        if (id < 1) throw new IllegalArgumentException("Falta el método de captura.");
        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            databaseAccess.withOrm(source -> {
                Dao<CaptureMethodEntity, Long> dao = DaoManager.createDao(source, CaptureMethodEntity.class);
                CaptureMethodEntity method = dao.queryForId(id);
                if (method == null) {
                    throw new IllegalArgumentException("El método de captura ya no está disponible.");
                }
                method.setActive(active);
                dao.update(method);
                return null;
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error actualizando el método de captura", exception);
        }
    }

    private static final Comparator<EnvironmentOptionEntity> ENVIRONMENT_ORDER = Comparator
            .comparingInt((EnvironmentOptionEntity option) -> option.getCategory().ordinal())
            .thenComparingInt(EnvironmentOptionEntity::getSortOrder)
            .thenComparing(EnvironmentOptionEntity::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(EnvironmentOptionEntity::id);

    /** One immutable snapshot supplies all four selectors until a catalog mutation invalidates it. */
    public List<EnvironmentOption> findEnvironmentOptions() {
        for (;;) {
            long generation;
            synchronized (environmentCatalogLock) {
                while (environmentOptions == null && environmentCatalogLoading) {
                    try {
                        environmentCatalogLock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RepositoryException("Se interrumpió la carga del catálogo de Entorno", exception);
                    }
                }
                if (environmentOptions != null) return environmentOptions;
                generation = environmentCatalogGeneration;
                environmentCatalogLoading = true;
            }
            List<EnvironmentOption> loaded = null;
            try {
                // Database I/O stays outside the monitor so invalidation never waits for SQLite.
                loaded = loadEnvironmentOptions();
            } finally {
                synchronized (environmentCatalogLock) {
                    environmentCatalogLoading = false;
                    if (loaded != null && generation == environmentCatalogGeneration) environmentOptions = loaded;
                    environmentCatalogLock.notifyAll();
                }
            }
            synchronized (environmentCatalogLock) {
                if (environmentOptions != null) return environmentOptions;
            }
            // A concurrent import or edit invalidated the in-flight query: load a fresh generation.
        }
    }

    /** Call after an import or restore that changes this repository's database directly. */
    public void invalidateEnvironmentOptions() {
        synchronized (environmentCatalogLock) {
            environmentCatalogGeneration++;
            environmentOptions = null;
            environmentCatalogLock.notifyAll();
        }
    }

    private List<EnvironmentOption> loadEnvironmentOptions() {
        try {
            return databaseAccess.withOrm(source -> {
                Dao<EnvironmentOptionEntity, Long> dao = DaoManager.createDao(source, EnvironmentOptionEntity.class);
                return dao.queryForAll().stream().sorted(ENVIRONMENT_ORDER)
                        .map(option -> new EnvironmentOption(option.id(), option.getStableKey(), option.getCategory(),
                                option.getName(), option.isActive(), option.getSortOrder()))
                        .toList();
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando el catálogo de Entorno", exception);
        }
    }

    public long saveEnvironmentOption(Long id, EnvironmentCategory category, String name) {
        if (category == null) throw new IllegalArgumentException("Indica el campo de Entorno.");
        if (id != null && id < 1) throw new IllegalArgumentException("Falta la opción que quieres editar.");
        String normalized = required(name, "Indica el nombre de la opción.").replaceAll("\\s+", " ");
        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            long savedId = databaseAccess.withOrm(source -> TransactionManager.callInTransaction(source, () -> {
                Dao<EnvironmentOptionEntity, Long> dao = DaoManager.createDao(source, EnvironmentOptionEntity.class);
                List<EnvironmentOptionEntity> existing = dao.queryForAll();
                for (EnvironmentOptionEntity other : existing) {
                    if (other.getCategory() == category && (id == null || other.id() != id)
                            && normalized.equalsIgnoreCase(other.getName().trim().replaceAll("\\s+", " "))) {
                        throw new IllegalArgumentException("Ya existe una opción con ese nombre en " + category.label() + ".");
                    }
                }
                EnvironmentOptionEntity option;
                if (id == null) {
                    int last = existing.stream().filter(other -> other.getCategory() == category)
                            .mapToInt(EnvironmentOptionEntity::getSortOrder).max().orElse(-1);
                    if (last == Integer.MAX_VALUE) throw new IllegalArgumentException("No caben más opciones en este campo.");
                    option = new EnvironmentOptionEntity(category, normalized, last + 1);
                } else {
                    option = dao.queryForId(id);
                    if (option == null) throw new IllegalArgumentException("La opción ya no está disponible.");
                    if (option.getCategory() != category) throw new IllegalArgumentException("No se puede cambiar el campo de una opción existente.");
                    option.setName(normalized);
                }
                if (id == null) dao.create(option); else dao.update(option);
                return option.id();
            }));
            invalidateEnvironmentOptions();
            return savedId;
        } catch (SQLException exception) {
            throw environmentFailure(exception, "Error guardando la opción de Entorno");
        }
    }

    public void setEnvironmentOptionActive(long id, boolean active) {
        if (id < 1) throw new IllegalArgumentException("Falta la opción de Entorno.");
        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            databaseAccess.withOrm(source -> {
                Dao<EnvironmentOptionEntity, Long> dao = DaoManager.createDao(source, EnvironmentOptionEntity.class);
                EnvironmentOptionEntity option = dao.queryForId(id);
                if (option == null) throw new IllegalArgumentException("La opción ya no está disponible.");
                option.setActive(active);
                dao.update(option);
                return null;
            });
            invalidateEnvironmentOptions();
        } catch (SQLException exception) {
            throw environmentFailure(exception, "Error actualizando la opción de Entorno");
        }
    }

    public void moveEnvironmentOption(long id, int direction) {
        if (id < 1) throw new IllegalArgumentException("Falta la opción de Entorno.");
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Indica subir o bajar la opción.");
        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            databaseAccess.withOrm(source -> TransactionManager.callInTransaction(source, () -> {
                Dao<EnvironmentOptionEntity, Long> dao = DaoManager.createDao(source, EnvironmentOptionEntity.class);
                EnvironmentOptionEntity selected = dao.queryForId(id);
                if (selected == null) throw new IllegalArgumentException("La opción ya no está disponible.");
                List<EnvironmentOptionEntity> siblings = dao.queryForEq(EnvironmentOptionEntity.CATEGORY, selected.getCategory())
                        .stream().sorted(ENVIRONMENT_ORDER).toList();
                int index = -1;
                for (int position = 0; position < siblings.size(); position++) {
                    if (siblings.get(position).id() == id) index = position;
                }
                int target = index + direction;
                if (target < 0 || target >= siblings.size()) return null;
                // Normalize imported equal or sparse positions while swapping the selected neighbors.
                for (int position = 0; position < siblings.size(); position++) {
                    int destination = position == index ? target : position == target ? index : position;
                    EnvironmentOptionEntity option = siblings.get(position);
                    if (option.getSortOrder() != destination) {
                        option.setSortOrder(destination);
                        dao.update(option);
                    }
                }
                return null;
            }));
            invalidateEnvironmentOptions();
        } catch (SQLException exception) {
            throw environmentFailure(exception, "Error ordenando las opciones de Entorno");
        }
    }

    private static RuntimeException environmentFailure(SQLException exception, String message) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException validation) return validation;
        }
        if (isConstraintViolation(exception)) return new IllegalArgumentException("Ya existe una opción con ese nombre en este campo.");
        return new RepositoryException(message, exception);
    }

    public List<PlaceSummary> findPlaceSummaries() {
        String sql = """
                SELECT
                    p.id,
                    p.name,
                    p.locality,
                    p.autonomous_community,
                    p.country,
                    p.latitude,
                    p.longitude,
                    p.notes,
                    p.is_favorite,
                    p.is_default,
                    COUNT(e.id) AS event_count
                FROM place p
                LEFT JOIN bird_event e ON e.place_id = p.id
                WHERE p.active = 1
                GROUP BY p.id, p.name, p.locality, p.autonomous_community, p.country,
                         p.latitude, p.longitude, p.notes, p.is_favorite, p.is_default
                ORDER BY p.is_default DESC,
                         p.is_favorite DESC,
                         event_count DESC,
                         p.name ASC
                """;

        try {
            return databaseAccess.withOrm(source -> {
                Dao<PlaceEntity, Long> dao = DaoManager.createDao(
                        source,
                        PlaceEntity.class
                );
                return OrmQuery.list(dao, sql, result -> new PlaceSummary(
                        getLong(result, "id"),
                        getString(result, "name"),
                        getString(result, "locality"),
                        getString(result, "autonomous_community"),
                        getString(result, "country"),
                        getDouble(result, "latitude"),
                        getDouble(result, "longitude"),
                        getString(result, "notes"),
                        getInt(result, "is_favorite") == 1,
                        getInt(result, "is_default") == 1,
                        getLong(result, "event_count")
                ));
            });
        } catch (SQLException exception) {
            throw new RepositoryException("Error cargando los lugares", exception);
        }
    }

    public long insertPlace(PlaceInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Indica los datos del lugar.");
        }
        String name = required(input.name(), "Indica el nombre del lugar.");
        validateCoordinates(input.latitude(), input.longitude());
        PlaceEntity place = new PlaceEntity(
                name,
                optional(input.locality()),
                optional(input.autonomousCommunity()),
                optional(input.country()),
                input.latitude(),
                input.longitude(),
                optional(input.notes()),
                input.favorite(),
                input.isDefault()
        );

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source ->
                    TransactionManager.callInTransaction(source, () -> {
                        Dao<PlaceEntity, Long> dao = DaoManager.createDao(
                                source,
                                PlaceEntity.class
                        );
                        if (input.isDefault()) {
                            UpdateBuilder<PlaceEntity, Long> update = dao.updateBuilder();
                            update.updateColumnValue(PlaceEntity.DEFAULT, false);
                            update.where().eq(PlaceEntity.DEFAULT, true);
                            update.update();
                        }
                        dao.create(place);
                        return place.id();
                    })
            );
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new IllegalArgumentException(
                        "Ya existe un lugar con ese nombre y localidad."
                );
            }
            throw new RepositoryException("Error guardando el lugar", exception);
        }
    }

    public long updatePlace(long placeId, PlaceInput input) {
        if (placeId < 1) {
            throw new IllegalArgumentException("Falta el lugar que quieres editar.");
        }
        if (input == null) {
            throw new IllegalArgumentException("Indica los datos del lugar.");
        }
        String name = required(input.name(), "Indica el nombre del lugar.");
        validateCoordinates(input.latitude(), input.longitude());

        try (DataMutationCoordinator.Lease ignored = mutationCoordinator.acquireMutation()) {
            return databaseAccess.withOrm(source ->
                    TransactionManager.callInTransaction(source, () -> {
                        Dao<PlaceEntity, Long> dao = DaoManager.createDao(
                                source,
                                PlaceEntity.class
                        );
                        PlaceEntity place = dao.queryForId(placeId);
                        if (place == null || !place.isActive()) {
                            throw new IllegalArgumentException(
                                    "El lugar ya no está disponible para editarlo."
                            );
                        }
                        if (input.isDefault()) {
                            UpdateBuilder<PlaceEntity, Long> update = dao.updateBuilder();
                            update.updateColumnValue(PlaceEntity.DEFAULT, false);
                            update.where().eq(PlaceEntity.DEFAULT, true);
                            update.update();
                        }
                        place.setName(name);
                        place.setLocality(optional(input.locality()));
                        place.setAutonomousCommunity(optional(input.autonomousCommunity()));
                        place.setCountry(optional(input.country()));
                        place.setLatitude(input.latitude());
                        place.setLongitude(input.longitude());
                        place.setNotes(optional(input.notes()));
                        place.setFavorite(input.favorite());
                        place.setDefaultPlace(input.isDefault());
                        dao.update(place);
                        return place.id();
                    })
            );
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                throw new IllegalArgumentException(
                        "Ya existe un lugar con ese nombre y localidad."
                );
            }
            throw new RepositoryException("Error actualizando el lugar", exception);
        }
    }

    private static String required(String value, String message) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude != null
                && (!Double.isFinite(latitude) || latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("La latitud debe estar entre -90 y 90.");
        }
        if (longitude != null
                && (!Double.isFinite(longitude) || longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("La longitud debe estar entre -180 y 180.");
        }
    }

    private static boolean isConstraintViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                String message = sqlException.getMessage();
                if (sqlException.getErrorCode() == 19
                        || message != null && message.contains("constraint failed")) {
                    return true;
                }
            }
        }
        return false;
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

    private static Double getDouble(DatabaseResults result, String column)
            throws SQLException {
        int index = result.findColumn(column);
        double value = result.getDouble(index);
        return result.wasNull(index) ? null : value;
    }
}

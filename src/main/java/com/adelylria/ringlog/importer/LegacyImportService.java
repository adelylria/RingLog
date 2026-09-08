package com.adelylria.ringlog.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.entity.BirdEntity;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.EventPhotoEntity;
import com.adelylria.ringlog.database.entity.PlaceEntity;
import com.adelylria.ringlog.database.entity.SpeciesEntity;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.dao.GenericRawResults;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.support.ConnectionSource;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;

public final class LegacyImportService {

    private final String databaseFile;
    private final Path photoDirectory;
    private final MediaPathResolver mediaResolver;
    private final LegacyWorkbookReader reader;

    public LegacyImportService() {
        this(AppPaths.production());
    }

    public LegacyImportService(AppPaths paths) {
        this(paths.databasePath().toString(), paths);
    }

    public LegacyImportService(String databaseFile, Path dataRoot) {
        this(databaseFile, AppPaths.forDataRoot(dataRoot));
    }

    public LegacyImportService(String databaseFile, AppPaths paths) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos de RingLog.");
        }
        if (paths == null) {
            throw new IllegalArgumentException("Indica dónde guardar las fotos importadas.");
        }
        this.databaseFile = Path.of(databaseFile).toAbsolutePath().normalize().toString();
        photoDirectory = paths.eventPhotosDirectory().toAbsolutePath().normalize();
        mediaResolver = new MediaPathResolver(paths);
        reader = new LegacyWorkbookReader();
    }

    public ImportPreview preview(Path workbook) throws LegacyImportException {
        LegacyImportData data = reader.read(workbook);
        return new ImportPreview(
                data.species().size(),
                data.birds().size(),
                data.places().size(),
                data.events().size(),
                data.photos().size(),
                data.warnings()
        );
    }

    public ImportResult importWorkbook(Path workbook) throws LegacyImportException {
        LegacyImportData data = reader.read(workbook);
        List<PreparedPhoto> photos = preparePhotos(data);
        Set<Path> newlyCopiedFiles = new HashSet<>();

        try {
            Database.initialize(databaseFile);
            Database.validate(databaseFile);
            return Database.withOrm(databaseFile, source ->
                    TransactionManager.callInTransaction(
                            source,
                            () -> importData(source, data, photos, newlyCopiedFiles)
                    )
            );
        } catch (SQLException exception) {
            cleanupCopiedFiles(newlyCopiedFiles, exception);
            throw new LegacyImportException(databaseMessage(exception), exception);
        }
    }

    private ImportResult importData(
            ConnectionSource source,
            LegacyImportData data,
            List<PreparedPhoto> photos,
            Set<Path> newlyCopiedFiles
    ) throws SQLException {
        Dao<SpeciesEntity, Long> speciesDao = DaoManager.createDao(
                source,
                SpeciesEntity.class
        );
        Dao<BirdEntity, Long> birdDao = DaoManager.createDao(source, BirdEntity.class);
        Dao<PlaceEntity, Long> placeDao = DaoManager.createDao(source, PlaceEntity.class);
        Dao<BirdEventEntity, Long> eventDao = DaoManager.createDao(
                source,
                BirdEventEntity.class
        );
        Dao<EventPhotoEntity, Long> photoDao = DaoManager.createDao(
                source,
                EventPhotoEntity.class
        );

        Counters counters = new Counters();
        Map<String, Long> speciesIds = importSpecies(speciesDao, data.species(), counters);
        Map<String, Long> birdIds = importBirds(
                birdDao,
                data.birds(),
                speciesIds,
                counters
        );
        Map<String, Long> placeIds = importPlaces(placeDao, data.places(), counters);
        Map<String, Long> eventIds = importEvents(
                eventDao,
                data.events(),
                birdIds,
                placeIds,
                data.formatVersion(),
                counters
        );
        importPhotos(photoDao, photos, eventIds, newlyCopiedFiles, counters);
        return counters.result();
    }

    private Map<String, Long> importSpecies(
            Dao<SpeciesEntity, Long> dao,
            List<SpeciesRow> rows,
            Counters counters
    ) throws SQLException {
        Map<String, SpeciesEntity> byCode = new HashMap<>();
        Map<String, SpeciesEntity> byScientificName = new HashMap<>();
        for (SpeciesEntity species : dao.queryForAll()) {
            putIfPresent(byCode, species.getCode(), species);
            putIfPresent(byScientificName, species.getScientificName(), species);
        }

        Map<String, Long> ids = new HashMap<>();
        for (SpeciesRow row : rows) {
            SpeciesEntity codeMatch = row.code() == null
                    ? null
                    : byCode.get(normalize(row.code()));
            SpeciesEntity nameMatch = byScientificName.get(normalize(row.scientificName()));
            if (codeMatch != null && nameMatch != null && codeMatch.id() != nameMatch.id()) {
                throw conflict("El código y el nombre científico pertenecen a especies distintas: "
                        + row.scientificName());
            }

            SpeciesEntity species = codeMatch != null ? codeMatch : nameMatch;
            if (species != null) {
                if (codeMatch != null && !normalize(species.getScientificName())
                        .equals(normalize(row.scientificName()))) {
                    throw conflict("El código " + row.code()
                            + " ya pertenece a otra especie.");
                }
                counters.speciesReused++;
            } else {
                species = new SpeciesEntity(row.code(), row.scientificName(), row.commonName());
                species.setActive(row.active());
                dao.create(species);
                restoreTimestamps(
                        dao,
                        SpeciesEntity.TABLE,
                        species.id(),
                        row.createdAt(),
                        row.updatedAt(),
                        true
                );
                counters.speciesCreated++;
                putIfPresent(byCode, species.getCode(), species);
                putIfPresent(byScientificName, species.getScientificName(), species);
            }
            ids.put(normalize(row.key()), species.id());
        }
        return ids;
    }

    private Map<String, Long> importBirds(
            Dao<BirdEntity, Long> dao,
            List<BirdRow> rows,
            Map<String, Long> speciesIds,
            Counters counters
    ) throws SQLException {
        Map<String, BirdEntity> existing = new HashMap<>();
        for (BirdEntity bird : dao.queryForAll()) {
            existing.put(normalize(bird.getRingNumber()), bird);
        }

        Map<String, Long> ids = new HashMap<>();
        for (BirdRow row : rows) {
            long speciesId = requiredId(speciesIds, row.speciesKey(), "especie");
            String ring = normalize(row.ringNumber());
            BirdEntity bird = existing.get(ring);
            if (bird != null) {
                if (bird.getSpeciesId() != speciesId) {
                    throw conflict("La anilla " + row.ringNumber()
                            + " ya está asociada a otra especie.");
                }
                counters.birdsReused++;
            } else {
                bird = new BirdEntity(row.ringNumber().trim(), speciesId);
                dao.create(bird);
                restoreTimestamps(
                        dao,
                        BirdEntity.TABLE,
                        bird.id(),
                        row.createdAt(),
                        row.updatedAt(),
                        true
                );
                existing.put(ring, bird);
                counters.birdsCreated++;
            }
            ids.put(ring, bird.id());
        }
        return ids;
    }

    private Map<String, Long> importPlaces(
            Dao<PlaceEntity, Long> dao,
            List<PlaceRow> rows,
            Counters counters
    ) throws SQLException {
        Map<String, PlaceEntity> existing = new HashMap<>();
        boolean hasDefault = false;
        for (PlaceEntity place : dao.queryForAll()) {
            existing.putIfAbsent(placeIdentity(place.getName(), place.getLocality()), place);
            hasDefault |= place.isDefaultPlace();
        }

        Map<String, Long> ids = new HashMap<>();
        for (PlaceRow row : rows) {
            String identity = placeIdentity(row.name(), row.locality());
            PlaceEntity place = existing.get(identity);
            if (place != null) {
                counters.placesReused++;
            } else {
                boolean makeDefault = row.defaultPlace() && !hasDefault;
                place = new PlaceEntity(
                        row.name(),
                        row.locality(),
                        row.latitude(),
                        row.longitude(),
                        row.notes(),
                        row.favorite(),
                        makeDefault
                );
                place.setActive(row.active());
                dao.create(place);
                restoreTimestamps(
                        dao,
                        PlaceEntity.TABLE,
                        place.id(),
                        row.createdAt(),
                        row.updatedAt(),
                        true
                );
                existing.put(identity, place);
                hasDefault |= makeDefault;
                counters.placesCreated++;
            }
            ids.put(normalize(row.key()), place.id());
        }
        return ids;
    }

    private Map<String, Long> importEvents(
            Dao<BirdEventEntity, Long> dao,
            List<EventRow> rows,
            Map<String, Long> birdIds,
            Map<String, Long> placeIds,
            int formatVersion,
            Counters counters
    ) throws SQLException {
        Map<String, BirdEventEntity> existingBySource = new HashMap<>();
        Map<String, List<BirdEventEntity>> existingByIdentity = new HashMap<>();
        Map<String, List<BirdEventEntity>> existingByBackupLocator = new HashMap<>();
        Map<Long, String> creationTimes = formatVersion >= 3
                ? eventCreationTimes(dao)
                : Map.of();
        for (BirdEventEntity event : dao.queryForAll()) {
            if (event.getSourceReference() != null) {
                existingBySource.put(event.getSourceReference(), event);
                existingByIdentity.computeIfAbsent(
                        eventIdentity(
                                event.getBirdId(),
                                event.getEventType(),
                                event.getEventDate(),
                                event.getEventTime()
                        ),
                        ignored -> new ArrayList<>()
                ).add(event);
            }
            if (formatVersion >= 3 && event.getSourceReference() == null) {
                String createdAt = creationTimes.get(event.id());
                if (createdAt != null) {
                    existingByBackupLocator.computeIfAbsent(
                            backupEventLocator(event.getBirdId(), createdAt),
                            ignored -> new ArrayList<>()
                    ).add(event);
                }
            }
        }

        Map<String, Long> ids = new HashMap<>();
        Set<Long> claimedEvents = new HashSet<>();
        for (EventRow row : rows) {
            long birdId = requiredId(birdIds, row.ringNumber(), "anilla");
            BirdEventEntity event = row.sourceReference() == null
                    ? null
                    : existingBySource.get(row.sourceReference());
            boolean reconcileProvenance = false;
            if (event == null && row.sourceReference() != null) {
                event = findByLegacyReference(
                        row,
                        existingBySource,
                        claimedEvents
                );
                if (event != null) {
                    reconcileProvenance = true;
                }
            }
            if (event == null && formatVersion >= 3
                    && row.sourceReference() == null) {
                event = findByBackupLocator(
                        row,
                        birdId,
                        placeIds,
                        existingByBackupLocator,
                        claimedEvents
                );
            }
            if (event == null && row.sourceReference() != null) {
                List<BirdEventEntity> candidates = existingByIdentity.getOrDefault(
                        eventIdentity(
                                birdId,
                                row.eventType(),
                                row.eventDate(),
                                row.eventTime()
                        ),
                        List.of()
                ).stream().filter(candidate -> !claimedEvents.contains(candidate.id())).toList();
                if (candidates.size() > 1) {
                    throw conflict("Hay varios eventos anteriores que podrían corresponder a "
                            + row.key() + ".");
                }
                if (candidates.size() == 1) {
                    event = candidates.get(0);
                    reconcileProvenance = true;
                }
            }
            if (event != null) {
                if (reconcileProvenance) {
                    reconcileSourceReference(dao, event, row);
                    existingBySource.put(row.sourceReference(), event);
                }
                if (!claimedEvents.add(event.id())) {
                    throw conflict("Dos filas del archivo apuntan al mismo evento guardado.");
                }
                counters.eventsSkipped++;
            } else {
                event = eventEntity(row, birdIds, placeIds);
                dao.create(event);
                restoreTimestamps(
                        dao,
                        BirdEventEntity.TABLE,
                        event.id(),
                        row.createdAt(),
                        row.updatedAt(),
                        true
                );
                if (row.sourceReference() != null) {
                    existingBySource.put(row.sourceReference(), event);
                }
                claimedEvents.add(event.id());
                counters.eventsCreated++;
            }
            ids.put(normalize(row.key()), event.id());
        }
        return ids;
    }

    private BirdEventEntity findByBackupLocator(
            EventRow row,
            long birdId,
            Map<String, Long> placeIds,
            Map<String, List<BirdEventEntity>> existingByBackupLocator,
            Set<Long> claimedEvents
    ) throws SQLException {
        List<BirdEventEntity> candidates = existingByBackupLocator.getOrDefault(
                backupEventLocator(birdId, row.createdAt()),
                List.of()
        ).stream().filter(candidate -> !claimedEvents.contains(candidate.id())).toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        Long expectedPlaceId = row.placeKey() == null
                ? null
                : requiredId(placeIds, row.placeKey(), "lugar");
        List<BirdEventEntity> exact = candidates.stream()
                .filter(candidate -> eventContentMatches(
                        candidate,
                        row,
                        birdId,
                        expectedPlaceId
                ))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        throw conflict(
                "Hay varios eventos creados en el mismo instante y no se puede "
                        + "identificar con seguridad " + row.key() + "."
        );
    }

    private boolean sameEventMoment(BirdEventEntity candidate, EventRow row) {
        return Objects.equals(candidate.getEventType(), row.eventType())
                && Objects.equals(candidate.getEventDate(), row.eventDate())
                && Objects.equals(
                        normalizeTime(candidate.getEventTime()),
                        normalizeTime(row.eventTime())
                );
    }

    private boolean eventContentMatches(
            BirdEventEntity event,
            EventRow row,
            long birdId,
            Long placeId
    ) {
        return event.getBirdId() == birdId
                && sameEventMoment(event, row)
                && Objects.equals(event.getPlaceId(), placeId)
                && Objects.equals(event.getLocationText(), row.locationText())
                && Objects.equals(event.getSexCode(), row.sexCode())
                && Objects.equals(event.getAgeEuringCode(), row.ageEuringCode())
                && Objects.equals(event.getFatScore(), row.fatScore())
                && Objects.equals(event.getMuscleScore(), row.muscleScore())
                && Objects.equals(event.getRingerInitials(), row.ringerInitials())
                && Objects.equals(event.getStatus(), row.status())
                && Objects.equals(
                        event.getReproductiveStatus(),
                        row.reproductiveStatus()
                )
                && Objects.equals(event.getMoultIntensity(), row.moultIntensity())
                && Objects.equals(event.getMoultExtension(), row.moultExtension())
                && Objects.equals(event.getBirdCondition(), row.birdCondition())
                && Objects.equals(event.getReturnStatus(), row.returnStatus())
                && Objects.equals(event.getWing(), row.wing())
                && Objects.equals(event.getP3(), row.p3())
                && Objects.equals(event.getTorso(), row.torso())
                && Objects.equals(event.getWeight(), row.weight())
                && Objects.equals(event.getObservations(), row.observations())
                && Objects.equals(event.getClouds(), row.clouds())
                && Objects.equals(event.getRain(), row.rain())
                && Objects.equals(
                        event.getThermalSensation(),
                        row.thermalSensation()
                )
                && Objects.equals(event.getWind(), row.wind())
                && Objects.equals(event.getCaptureType(), row.captureType())
                && event.isDead() == row.dead();
    }

    private Map<Long, String> eventCreationTimes(Dao<BirdEventEntity, Long> dao)
            throws SQLException {
        Map<Long, String> values = new HashMap<>();
        try (GenericRawResults<String[]> results = dao.queryRaw(
                "SELECT id, created_at FROM bird_event"
        )) {
            for (String[] row : results) {
                values.put(Long.valueOf(row[0]), row[1]);
            }
        } catch (SQLException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException(
                    "No se pudieron leer las fechas internas de los eventos.",
                    exception
            );
        }
        return values;
    }

    private BirdEventEntity findByLegacyReference(
            EventRow row,
            Map<String, BirdEventEntity> existingBySource,
            Set<Long> claimedEvents
    ) throws SQLException {
        Set<BirdEventEntity> matches = new LinkedHashSet<>();
        for (String reference : compatibleSourceReferences(row)) {
            BirdEventEntity candidate = existingBySource.get(reference);
            if (candidate != null && !claimedEvents.contains(candidate.id())) {
                matches.add(candidate);
            }
        }
        if (matches.size() > 1) {
            throw conflict("Varias referencias anteriores corresponden al evento "
                    + row.sourceReference() + ".");
        }
        return matches.stream().findFirst().orElse(null);
    }

    private Set<String> compatibleSourceReferences(EventRow row) {
        Set<String> references = new LinkedHashSet<>();
        if (row.sourceReference() != null) {
            references.add(row.sourceReference());
        }
        references.addAll(row.sourceAliases());
        for (String reference : List.copyOf(references)) {
            String[] parts = reference.split(":");
            if (parts.length >= 3 && "XLSX".equalsIgnoreCase(parts[0])) {
                references.add(parts[1] + "!" + parts[2]);
            } else if (parts.length >= 3
                    && "DOCX".equalsIgnoreCase(parts[0])
                    && "RECOVERY".equalsIgnoreCase(parts[1])) {
                references.add("DOCX#" + parts[2]);
            } else if (parts.length >= 3
                    && "ACCESS".equalsIgnoreCase(parts[0])
                    && "CAPTURAS".equalsIgnoreCase(parts[1])) {
                references.add("CAPTURAS#" + parts[2]);
                references.add("CAPTURAS!" + parts[2]);
                references.add("ACCESS#" + parts[2]);
            }
        }
        return references;
    }

    private void reconcileSourceReference(
            Dao<BirdEventEntity, Long> dao,
            BirdEventEntity event,
            EventRow row
    ) throws SQLException {
        UpdateBuilder<BirdEventEntity, Long> update = dao.updateBuilder();
        update.updateColumnValue(BirdEventEntity.SOURCE_NAME, row.sourceName());
        update.updateColumnValue(
                BirdEventEntity.SOURCE_REFERENCE,
                row.sourceReference()
        );
        update.where().idEq(event.id());
        update.update();
        event.setSourceName(row.sourceName());
        event.setSourceReference(row.sourceReference());
    }

    private BirdEventEntity eventEntity(
            EventRow row,
            Map<String, Long> birdIds,
            Map<String, Long> placeIds
    ) throws SQLException {
        BirdEventEntity event = new BirdEventEntity();
        event.setBirdId(requiredId(birdIds, row.ringNumber(), "anilla"));
        event.setEventType(row.eventType());
        event.setEventDate(row.eventDate());
        event.setEventTime(row.eventTime());
        event.setPlaceId(row.placeKey() == null
                ? null
                : requiredId(placeIds, row.placeKey(), "lugar"));
        event.setLocationText(row.locationText());
        event.setSexCode(row.sexCode());
        event.setAgeEuringCode(row.ageEuringCode());
        event.setFatScore(row.fatScore());
        event.setMuscleScore(row.muscleScore());
        event.setRingerInitials(row.ringerInitials());
        event.setStatus(row.status());
        event.setReproductiveStatus(row.reproductiveStatus());
        event.setMoultIntensity(row.moultIntensity());
        event.setMoultExtension(row.moultExtension());
        event.setBirdCondition(row.birdCondition());
        event.setReturnStatus(row.returnStatus());
        event.setWing(row.wing());
        event.setP3(row.p3());
        event.setTorso(row.torso());
        event.setWeight(row.weight());
        event.setObservations(row.observations());
        event.setClouds(row.clouds());
        event.setRain(row.rain());
        event.setThermalSensation(row.thermalSensation());
        event.setWind(row.wind());
        event.setCaptureType(row.captureType());
        event.setDead(row.dead());
        event.setSourceName(row.sourceName());
        event.setSourceReference(row.sourceReference());
        return event;
    }

    private void importPhotos(
            Dao<EventPhotoEntity, Long> dao,
            List<PreparedPhoto> photos,
            Map<String, Long> eventIds,
            Set<Path> newlyCopiedFiles,
            Counters counters
    ) throws SQLException {
        Map<String, EventPhotoEntity> existingByPath = new HashMap<>();
        Map<String, Integer> existingByContent = new HashMap<>();
        for (EventPhotoEntity photo : dao.queryForAll()) {
            existingByPath.put(
                    photoIdentity(photo.getEventId(), photo.getFilePath()),
                    photo
            );
            Path existingFile;
            try {
                existingFile = mediaResolver.resolveEventPhoto(photo.getFilePath());
            } catch (IllegalArgumentException invalidReference) {
                continue;
            }
            if (Files.isRegularFile(existingFile)) {
                try {
                    existingByContent.merge(
                            photoContentIdentity(
                                    photo.getEventId(),
                                    photo.getFileName(),
                                    sha256(existingFile)
                            ),
                            1,
                            Integer::sum
                    );
                } catch (IOException ignored) {
                    // A broken old photo must not prevent restoring the healthy copy.
                }
            }
        }

        for (PreparedPhoto photo : photos) {
            long eventId = requiredId(eventIds, photo.row().eventKey(), "evento");
            String managedReference = mediaResolver.toEventReference(
                    PhotoArea.EVENTS, photo.destination()
            );
            String identity = photoIdentity(eventId, managedReference);
            String contentIdentity = photoContentIdentity(
                    eventId,
                    photo.row().fileName(),
                    photo.contentHash()
            );
            if (existingByPath.containsKey(identity)) {
                copyPhoto(photo, newlyCopiedFiles);
                consumeContentMatch(existingByContent, contentIdentity);
                counters.photosSkipped++;
                continue;
            }
            int matchingContent = existingByContent.getOrDefault(contentIdentity, 0);
            if (matchingContent > 0) {
                existingByContent.put(contentIdentity, matchingContent - 1);
                counters.photosSkipped++;
                continue;
            }

            copyPhoto(photo, newlyCopiedFiles);
            EventPhotoEntity entity = new EventPhotoEntity();
            entity.setEventId(eventId);
            entity.setFileName(photo.row().fileName());
            entity.setFilePath(managedReference);
            entity.setMimeType(photo.row().mimeType());
            dao.create(entity);
            restoreTimestamps(
                    dao,
                    EventPhotoEntity.TABLE,
                    entity.getId(),
                    photo.row().createdAt(),
                    null,
                    false
            );
            existingByPath.put(identity, entity);
            counters.photosCreated++;
        }
    }

    private List<PreparedPhoto> preparePhotos(LegacyImportData data)
            throws LegacyImportException {
        List<PreparedPhoto> prepared = new ArrayList<>();
        Path importDirectory = data.workbook().getParent();
        for (PhotoRow photo : data.photos()) {
            Path source = photo.embeddedContent() == null
                    ? importDirectory.resolve(photo.relativePath()).normalize()
                    : null;
            try {
                byte[] embeddedContent = photo.embeddedContent();
                String contentHash = embeddedContent == null
                        ? sha256(source)
                        : sha256(embeddedContent);
                String extension = safeExtension(photo.fileName());
                Path destination = photoDirectory
                        .resolve(contentHash.substring(0, 2))
                        .resolve(contentHash + extension)
                        .toAbsolutePath()
                        .normalize();
                if (!destination.startsWith(photoDirectory)) {
                    throw new LegacyImportException("No se pudo preparar una foto importada.");
                }
                prepared.add(new PreparedPhoto(
                        photo,
                        source,
                        embeddedContent,
                        destination,
                        contentHash
                ));
            } catch (IOException exception) {
                throw new LegacyImportException(
                        "No se pudo leer la foto vinculada " + photo.fileName() + ".",
                        exception
                );
            }
        }
        return List.copyOf(prepared);
    }

    private void copyPhoto(PreparedPhoto photo, Set<Path> newlyCopiedFiles)
            throws SQLException {
        Path staged = null;
        try {
            if (Files.exists(photo.destination())) {
                if (!photo.contentHash().equals(sha256(photo.destination()))) {
                    throw new IOException("El archivo de destino tiene otro contenido.");
                }
                return;
            }
            Files.createDirectories(photo.destination().getParent());
            staged = Files.createTempFile(
                    photo.destination().getParent(),
                    "." + photo.destination().getFileName() + ".",
                    ".tmp"
            );
            if (photo.embeddedContent() == null) {
                Files.copy(
                        photo.source(),
                        staged,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            } else {
                Files.write(
                        staged,
                        photo.embeddedContent(),
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            }
            if (!photo.contentHash().equals(sha256(staged))) {
                throw new IOException("La foto cambió mientras se estaba restaurando.");
            }
            try {
                Files.move(staged, photo.destination(), StandardCopyOption.ATOMIC_MOVE);
                staged = null;
                newlyCopiedFiles.add(photo.destination());
            } catch (FileAlreadyExistsException anotherWriterWon) {
                if (!Files.isRegularFile(photo.destination())
                        || !photo.contentHash().equals(sha256(photo.destination()))) {
                    throw anotherWriterWon;
                }
            }
        } catch (IOException exception) {
            throw new SQLException(
                    "No se pudo copiar la foto vinculada " + photo.row().fileName() + ".",
                    exception
            );
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // The original copy failure is more useful to the user.
                }
            }
        }
    }

    private static void consumeContentMatch(
            Map<String, Integer> matches,
            String identity
    ) {
        int count = matches.getOrDefault(identity, 0);
        if (count > 0) {
            matches.put(identity, count - 1);
        }
    }

    private static long requiredId(Map<String, Long> ids, String key, String kind)
            throws SQLException {
        Long id = ids.get(normalize(key));
        if (id == null) {
            throw conflict("No se pudo resolver la referencia de " + kind + ": " + key);
        }
        return id;
    }

    private static void restoreTimestamps(
            Dao<?, Long> dao,
            String table,
            long id,
            String createdAt,
            String updatedAt,
            boolean hasUpdateTrigger
    ) throws SQLException {
        if (createdAt == null) {
            return;
        }
        String identifier = Long.toString(id);
        if (!hasUpdateTrigger) {
            dao.updateRaw(
                    "UPDATE " + table + " SET created_at = ? WHERE id = ?",
                    createdAt,
                    identifier
            );
        } else if (updatedAt == null) {
            dao.updateRaw(
                    "UPDATE " + table
                            + " SET created_at = ?, updated_at = '' WHERE id = ?",
                    createdAt,
                    identifier
            );
            dao.updateRaw(
                    "UPDATE " + table + " SET updated_at = NULL WHERE id = ?",
                    identifier
            );
        } else {
            dao.updateRaw(
                    "UPDATE " + table
                            + " SET created_at = ?, updated_at = ? WHERE id = ?",
                    createdAt,
                    updatedAt,
                    identifier
            );
        }
    }

    private static void putIfPresent(
            Map<String, SpeciesEntity> destination,
            String key,
            SpeciesEntity value
    ) {
        if (key != null && !key.isBlank()) {
            destination.putIfAbsent(normalize(key), value);
        }
    }

    private static String placeIdentity(String name, String locality) {
        return normalizePlaceText(name) + '\u0000' + normalizePlaceText(locality);
    }

    private static String normalizePlaceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(
                        value.trim().toUpperCase(Locale.ROOT),
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.replace("POLLENCA", "POLLENSA");
    }

    private static String eventIdentity(
            long birdId,
            String eventType,
            String eventDate,
            String eventTime
    ) {
        return birdId + "\u0000"
                + normalize(eventType) + "\u0000"
                + eventDate.trim() + "\u0000"
                + normalizeTime(eventTime);
    }

    private static String backupEventLocator(long birdId, String createdAt) {
        return birdId + "\u0000" + createdAt;
    }

    private static String normalizeTime(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return LocalTime.parse(value.trim()).withNano(0).toString();
        } catch (DateTimeParseException ignored) {
            return value.trim();
        }
    }

    private static String photoIdentity(long eventId, String path) {
        return eventId + "\u0000" + Path.of(path).toAbsolutePath().normalize();
    }

    private static String photoContentIdentity(
            long eventId,
            String fileName,
            String contentHash
    ) {
        return eventId + "\u0000" + normalize(fileName) + "\u0000" + contentHash;
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return hex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static SQLException conflict(String message) {
        return new SQLException(message);
    }

    private static String databaseMessage(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return "No se pudo completar la importación: " + current.getMessage();
            }
        }
        return "No se pudo completar la importación.";
    }

    private static void cleanupCopiedFiles(Set<Path> files, Exception original) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private record PreparedPhoto(
            PhotoRow row,
            Path source,
            byte[] embeddedContent,
            Path destination,
            String contentHash
    ) {
        private PreparedPhoto {
            embeddedContent = embeddedContent == null ? null : embeddedContent.clone();
        }

        @Override
        public byte[] embeddedContent() {
            return embeddedContent == null ? null : embeddedContent.clone();
        }
    }

    private static final class Counters {

        private int speciesCreated;
        private int speciesReused;
        private int birdsCreated;
        private int birdsReused;
        private int placesCreated;
        private int placesReused;
        private int eventsCreated;
        private int eventsSkipped;
        private int photosCreated;
        private int photosSkipped;

        private ImportResult result() {
            return new ImportResult(
                    speciesCreated,
                    speciesReused,
                    birdsCreated,
                    birdsReused,
                    placesCreated,
                    placesReused,
                    eventsCreated,
                    eventsSkipped,
                    photosCreated,
                    photosSkipped
            );
        }
    }
}

package com.adelylria.ringlog.media;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.EventPhotoEntity;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.RepositoryException;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.misc.TransactionManager;

/** Applies photo edits to one event, never to the entire bird history. */
public final class EventPhotoService {
    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(EventPhotoService.class);
    private final AppPaths paths;
    private final DataMutationCoordinator mutations;

    public EventPhotoService(AppPaths paths, DataMutationCoordinator mutations) {
        this.paths = java.util.Objects.requireNonNull(paths);
        this.mutations = java.util.Objects.requireNonNull(mutations);
    }

    /** Called only on Save. Selecting or cancelling photos does not invoke this service. */
    public synchronized long saveEvent(Long eventId, BirdEventInput input, List<Path> originals)
            throws IOException {
        return saveEvent(eventId, input, originals, List.of());
    }

    public synchronized long saveEvent(Long eventId, BirdEventInput input, List<Path> originals,
            List<String> removedPhotoPaths) throws IOException {
        List<String> removals = List.copyOf(removedPhotoPaths);
        if (eventId == null && !removals.isEmpty()) {
            throw new IllegalArgumentException("Un registro nuevo no contiene fotos guardadas para eliminar.");
        }
        try (var lease = mutations.acquireMutation()) {
            List<PreparedPhoto> photos = new ArrayList<>();
            boolean committed = false;
            try {
                for (Path original : List.copyOf(originals)) photos.add(prepare(original));
                for (PreparedPhoto photo : photos) photo.publish();
                BirdEventRepository repository = new BirdEventRepository(paths,
                        new WritableDatabaseAccess(paths.databasePath()), mutations);
                List<EventPhotoEntity> entities = photos.stream().map(photo -> photo.entity).toList();
                long savedId;
                List<String> removed = List.of();
                if (eventId == null) {
                    savedId = repository.insert(input, entities);
                } else {
                    removed = repository.update(eventId, input, entities, removals);
                    savedId = eventId;
                }
                committed = true;
                cleanupRemoved(removed);
                return savedId;
            } catch (RepositoryException failure) {
                throw new IOException("No se han guardado los cambios ni las fotos. Puedes volver a intentarlo.", failure);
            } finally {
                cleanup(photos, committed);
            }
        }
    }

    private void cleanupRemoved(List<String> references) {
        if (references.isEmpty()) return;
        MediaPathResolver resolver = new MediaPathResolver(paths);
        // Hold SQLite's writer reservation while checking references and deleting files. A concurrent
        // import cannot attach one of these binaries between the check and deletion. The outer mutation
        // lease also excludes portable snapshots until both DB and media have reached their final state.
        try (var connection = new WritableDatabaseAccess(paths.databasePath()).openConnection();
             var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                java.util.Set<Path> inUse = new java.util.HashSet<>();
                try (var rows = statement.executeQuery("SELECT file_path FROM event_photo")) {
                    while (rows.next()) inUse.add(resolver.resolveEventPhoto(rows.getString(1)));
                }
                try (var rows = statement.executeQuery("SELECT file_path FROM legacy_unassigned_photo WHERE file_path IS NOT NULL AND file_path <> ''")) {
                    while (rows.next()) inUse.add(resolver.resolveUnassignedPhoto(rows.getString(1)));
                }
                for (String reference : references) {
                    Path file = resolver.resolveEventPhoto(reference);
                    if (inUse.contains(file) || !Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    // Never follow a symlink/junction out to user originals or another storage area.
                    if (!file.equals(file.toRealPath()) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    Files.deleteIfExists(file);
                }
            } finally {
                statement.execute("ROLLBACK"); // No DB writes in this cleanup-only transaction.
            }
        } catch (IOException | SQLException | RuntimeException failure) {
            // The edit has already committed. Leave an unreferenced file rather than risking data loss
            // or telling the user to repeat a successful edit. Do not log filenames or personal data.
            LOG.warn("Removed photo cleanup after committed save failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** @return false if the same image already belongs to this event. */
    public synchronized boolean addPhoto(long eventId, Path original) throws IOException {
        if (eventId < 1) throw new IllegalArgumentException("Selecciona un registro válido.");
        try (var lease = mutations.acquireMutation()) {
            PreparedPhoto prepared = prepare(original);
            boolean committed = false;
            try {
                EventPhotoEntity photo = prepared.entity;
                photo.setEventId(eventId);
                boolean added = new WritableDatabaseAccess(paths.databasePath()).withOrm(source ->
                        TransactionManager.callInTransaction(source, () -> {
                            Dao<BirdEventEntity, Long> events = DaoManager.createDao(source, BirdEventEntity.class);
                            if (!events.idExists(eventId)) {
                                throw new IllegalArgumentException("El registro ya no está disponible.");
                            }
                            Dao<EventPhotoEntity, Long> photos = DaoManager.createDao(source, EventPhotoEntity.class);
                            var duplicate = photos.queryBuilder();
                            duplicate.where().eq(EventPhotoEntity.EVENT_ID, eventId)
                                    .and().eq("content_sha256", photo.getContentSha256());
                            if (duplicate.queryForFirst() != null) return false;
                            prepared.publish();
                            photos.create(photo);
                            return true;
                        }));
                committed = true;
                return added;
            } catch (SQLException failure) {
                throw new IOException("No se pudo guardar la fotografía. El archivo original no se ha modificado.", failure);
            } finally {
                cleanup(List.of(prepared), committed);
            }
        }
    }

    private PreparedPhoto prepare(Path original) throws IOException {
        if (original == null || !Files.isRegularFile(original)) {
            throw new IOException("No se encuentra la imagen seleccionada.");
        }
        if (Files.size(original) > PhotoImages.MAX_FILE_BYTES) {
            throw new IOException("Selecciona una imagen de hasta 50 MB.");
        }
        Files.createDirectories(paths.migrationDirectory());
        Path staging = Files.createTempFile(paths.migrationDirectory(), ".photo-", ".tmp");
        try {
            String hash = copyAndHash(original, staging);
            PhotoImages.Preview verified = PhotoImages.load(staging, 1200, 900);
            if (!verified.extension().equals("png") && !verified.extension().equals("jpg")) {
                throw new IOException("Selecciona una fotografía en formato JPG o PNG.");
            }
            EventPhotoEntity photo = new EventPhotoEntity();
            photo.setFileName(original.getFileName().toString());
            photo.setMimeType(verified.mimeType());
            photo.setContentSha256(hash);
            photo.setContentSize(Files.size(staging));
            // Independently owned files allow rollback without affecting imported or existing photos.
            Path destination = paths.eventPhotosDirectory().resolve("added")
                    .resolve(photo.getStableKey() + '.' + verified.extension());
            photo.setFilePath(new MediaPathResolver(paths).toEventReference(PhotoArea.EVENTS, destination));
            return new PreparedPhoto(photo, staging, destination);
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(staging); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void cleanup(List<PreparedPhoto> photos, boolean committed) throws IOException {
        IOException failure = null;
        for (PreparedPhoto photo : photos) {
            for (Path file : List.of(photo.staging, photo.destination)) {
                if (file.equals(photo.destination) && (!photo.published || (committed && photo.entity.getId() != 0))) continue;
                try { Files.deleteIfExists(file); }
                catch (IOException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            if (!committed) throw failure;
            // A committed record must not be reported as a failed save merely because cleanup failed.
            LOG.warn("Photo cleanup after committed save failed: {}", failure.getClass().getSimpleName());
        }
    }

    private static final class PreparedPhoto {
        private final EventPhotoEntity entity;
        private final Path staging;
        private final Path destination;
        private boolean published;

        private PreparedPhoto(EventPhotoEntity entity, Path staging, Path destination) {
            this.entity = entity;
            this.staging = staging;
            this.destination = destination;
        }

        private void publish() throws IOException {
            Files.createDirectories(destination.getParent());
            try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(staging, destination); }
            published = true;
        }
    }

    private static String copyAndHash(Path original, Path staging) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = Files.newInputStream(original); var output = Files.newOutputStream(staging)) {
            byte[] buffer = new byte[16_384];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > PhotoImages.MAX_FILE_BYTES) throw new IOException("La imagen supera los 50 MB.");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

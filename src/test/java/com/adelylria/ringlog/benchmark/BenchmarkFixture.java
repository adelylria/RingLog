package com.adelylria.ringlog.benchmark;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;

/** Deterministic synthetic archive. It never opens a user-supplied or production database. */
final class BenchmarkFixture {
    private BenchmarkFixture() { }

    static BenchmarkContext create(Path work, int rows, int historyLength, int warmups,
                                   int samples, int reportLimit) throws Exception {
        Path root = work.resolve("synthetic-data").toAbsolutePath().normalize();
        if (Files.exists(root)) throw new IllegalArgumentException("Fixture directory already exists: " + root);
        Files.createDirectories(root);
        System.setProperty("ringlog.test.mode", "true");
        System.setProperty("ringlog.data.dir", root.toString());
        System.setProperty("ringlog.preferences.path", root.resolve("preferences.properties").toString());
        AppPaths paths = AppPaths.forDataRoot(root);
        Database.initialize(paths.databasePath().toString());
        List<Path> sources = photos(paths);
        int birds = (rows + historyLength - 1) / historyLength;
        List<Long> photoEventIds = new ArrayList<>();
        try (Connection connection = Database.getConnection(paths.databasePath().toString())) {
            connection.setAutoCommit(false);
            try (var species = connection.prepareStatement(
                    "INSERT INTO species(id,stable_key,code,scientific_name,common_name) VALUES(?,?,?,?,?)");
                 var place = connection.prepareStatement(
                    "INSERT INTO place(id,stable_key,name,locality,is_default) VALUES(?,?,?,?,?)");
                 var bird = connection.prepareStatement(
                    "INSERT INTO bird(id,stable_key,ring_number,species_id) VALUES(?,?,?,?)");
                 var event = connection.prepareStatement("""
                    INSERT INTO bird_event(id,stable_key,bird_id,event_type,event_date,event_time,
                        place_id,sex_code,age_euring_code,status,bird_condition,wing,p3,torso,weight,
                        fat_score,muscle_score,observations,capture_method_id,source_name,source_reference)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (int id = 1; id <= 20; id++) {
                    species.setInt(1, id); species.setString(2, key("species", id));
                    species.setString(3, "SYN-" + id); species.setString(4, "Species benchmark " + id);
                    species.setString(5, String.format(Locale.ROOT, "Especie sintética %02d", id)); species.addBatch();
                }
                species.executeBatch();
                for (int id = 1; id <= 30; id++) {
                    place.setInt(1, id); place.setString(2, key("place", id));
                    place.setString(3, String.format(Locale.ROOT, "Lugar sintético %02d", id));
                    place.setString(4, "Localidad de prueba"); place.setInt(5, id == 1 ? 1 : 0); place.addBatch();
                }
                place.executeBatch();
                for (int id = 1; id <= birds; id++) {
                    bird.setInt(1, id); bird.setString(2, key("bird", id));
                    bird.setString(3, String.format(Locale.ROOT, "BENCH-%06d", id));
                    bird.setInt(4, 1 + (id - 1) % 20); bird.addBatch();
                }
                bird.executeBatch();
                for (int id = 1; id <= rows; id++) {
                    int birdId = 1 + (id - 1) / historyLength;
                    int position = (id - 1) % historyLength;
                    event.setInt(1, id); event.setString(2, key("event", id)); event.setInt(3, birdId);
                    event.setString(4, position == 0 ? "RINGING" : position == historyLength - 1 ? "RECOVERY" : "CONTROL");
                    event.setString(5, LocalDate.of(2010, 1, 1).plusDays((id * 17L) % 6000).toString());
                    event.setString(6, String.format(Locale.ROOT, "%02d:%02d", 6 + id % 14, id % 60));
                    event.setInt(7, 1 + (id - 1) % 30); event.setString(8, id % 2 == 0 ? "M" : "F");
                    event.setString(9, "4"); event.setString(10, "B0"); event.setString(11, "GOOD");
                    event.setDouble(12, 90 + id % 30); event.setDouble(13, 65 + id % 20);
                    event.setDouble(14, 20.5 + id % 10); event.setDouble(15, 25.5 + id % 50);
                    event.setInt(16, id % 8); event.setInt(17, id % 4);
                    String note = "Observación sintética de anillamiento, hábitat y estado del ave. "
                            + "Dato de prueba BENCH-" + String.format(Locale.ROOT, "%06d", Math.max(1, birdId - 1));
                    event.setString(18, id % 100 == 0 ? note.repeat(20) : note.repeat(2));
                    event.setInt(19, 1 + id % 2); event.setString(20, "RingLog benchmark");
                    event.setString(21, "benchmark-event-" + id); event.addBatch();
                    if (id % 1000 == 0) event.executeBatch();
                }
                event.executeBatch();
            }
            try (var query = connection.createStatement();
                 var result = query.executeQuery("SELECT id FROM bird_event ORDER BY event_date DESC,event_time DESC,id DESC LIMIT 24")) {
                while (result.next()) photoEventIds.add(result.getLong(1));
            }
            List<Path> copied = new ArrayList<>();
            try (var photo = connection.prepareStatement("""
                    INSERT INTO event_photo(stable_key,event_id,file_name,file_path,mime_type,content_sha256,content_size)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                for (int index = 0; index < photoEventIds.size(); index++) {
                    Path source = sources.get(index % sources.size());
                    Path copy = paths.eventPhotosDirectory().resolve("benchmark-" + index + ".jpg");
                    Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES);
                    copied.add(copy);
                    photo.setString(1, key("photo", index + 1)); photo.setLong(2, photoEventIds.get(index));
                    photo.setString(3, copy.getFileName().toString());
                    photo.setString(4, new MediaPathResolver(paths).toEventReference(PhotoArea.EVENTS, copy));
                    photo.setString(5, "image/jpeg"); photo.setString(6, HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy))));
                    photo.setLong(7, Files.size(copy)); photo.addBatch();
                }
                photo.executeBatch();
            }
            connection.commit();
            try (var query = connection.createStatement(); var check = query.executeQuery("PRAGMA quick_check")) {
                if (!check.next() || !"ok".equals(check.getString(1))) throw new AssertionError("Fixture integrity failed");
            }
            try (var query = connection.createStatement(); var check = query.executeQuery("PRAGMA foreign_key_check")) {
                if (check.next()) throw new AssertionError("Fixture foreign keys failed");
            }
            long selected = photoEventIds.get(0);
            long selectedBird = 1 + (selected - 1) / historyLength;
            return new BenchmarkContext(paths, work, rows, warmups, samples, reportLimit, selected, selectedBird, copied);
        }
    }

    private static List<Path> photos(AppPaths paths) throws Exception {
        Files.createDirectories(paths.eventPhotosDirectory());
        List<Path> result = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            BufferedImage image = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setPaint(new GradientPaint(0, 0, new Color(35 + index * 20, 95, 80),
                        2400, 1800, new Color(140, 140 + index * 20, 200)));
                graphics.fillRect(0, 0, 2400, 1800);
                for (int line = 0; line < 150; line++) {
                    graphics.setColor(new Color((line * 13) % 256, (line * 19) % 256, (line * 23) % 256));
                    graphics.drawLine((line * 37) % 2400, 0, (line * 71) % 2400, 1800);
                }
            } finally {
                graphics.dispose();
            }
            Path file = paths.eventPhotosDirectory().resolve("source-" + index + ".jpg");
            if (!ImageIO.write(image, "jpg", file.toFile())) throw new AssertionError("JPEG writer unavailable");
            result.add(file);
        }
        return result;
    }

    private static String key(String table, int id) {
        return UUID.nameUUIDFromBytes(("ringlog-benchmark:" + table + ':' + id)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}

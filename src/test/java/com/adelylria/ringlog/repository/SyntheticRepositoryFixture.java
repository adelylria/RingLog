package com.adelylria.ringlog.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Small synthetic dataset covering repository projections without user information. */
final class SyntheticRepositoryFixture {

    private SyntheticRepositoryFixture() {
    }

    static void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species (
                        id, stable_key, code, scientific_name, common_name
                    ) VALUES (
                        1, 'species-synthetic-1', 'TESTSP', 'AVIS TESTENSIS', NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO place (
                        id, stable_key, name, locality, latitude, longitude,
                        notes, is_favorite, is_default
                    ) VALUES
                        (1, 'place-synthetic-1', 'ESTACION ALFA', 'LOCALIDAD DE PRUEBA',
                         39.1000, 2.1000, NULL, 1, 1),
                        (2, 'place-synthetic-2', 'ESTACION BETA', 'LOCALIDAD DE PRUEBA',
                         39.2000, 2.2000, NULL, 1, 0),
                        (3, 'place-synthetic-3', 'ESTACION GAMMA', 'LOCALIDAD DE PRUEBA',
                         39.3000, 2.3000, NULL, 0, 0),
                        (4, 'place-synthetic-4', 'ESTACION DELTA', 'LOCALIDAD DE PRUEBA',
                         39.4000, 2.4000, NULL, 0, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird (id, stable_key, ring_number, species_id) VALUES
                        (1, 'bird-synthetic-1', 'TEST-PRIMARY', 1),
                        (2, 'bird-synthetic-2', 'TEST-HISTORY', 1),
                        (3, 'bird-synthetic-3', 'TEST-THIRD', 1),
                        (4, 'bird-synthetic-4', 'TEST-FOURTH', 1),
                        (5, 'bird-synthetic-5', 'TEST-FIFTH', 1),
                        (6, 'bird-synthetic-6', 'TEST-NEWEST', 1)
                    """);
        }

        try (PreparedStatement event = connection.prepareStatement("""
                INSERT INTO bird_event (
                    id, stable_key, bird_id, event_type, event_date, event_time,
                    place_id, location_text, sex_code, age_euring_code,
                    fat_score, muscle_score, weight, source_name, source_reference,
                    review_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'U', ?, 2, 2, 75.0,
                          'fixture-sintetico.xlsx', ?, 'OK')
                """)) {
            addEvent(event, 1, 1, "RINGING", "2026-01-01", "08:00:00",
                    1L, null, "2");
            addEvent(event, 2, 2, "RINGING", "2026-01-02", "09:00:00",
                    1L, null, "4");
            addEvent(event, 3, 2, "CONTROL", "2026-01-03", "10:00:00",
                    2L, null, "4");
            addEvent(event, 4, 2, "RECOVERY", "2026-01-04", null,
                    null, "UBICACION SINTETICA", "4");
            addEvent(event, 5, 3, "RINGING", "2026-01-05", "11:00:00",
                    3L, null, "4");
            addEvent(event, 6, 4, "RINGING", "2026-01-06", "12:00:00",
                    4L, null, "4");
            addEvent(event, 7, 5, "RINGING", "2026-01-07", "13:00:00",
                    1L, null, "4");
            addEvent(event, 8, 6, "RINGING", "2026-02-01", "14:00:00",
                    1L, null, "4");
        }
    }

    private static void addEvent(
            PreparedStatement statement,
            long id,
            long birdId,
            String type,
            String date,
            String time,
            Long placeId,
            String location,
            String age
    ) throws SQLException {
        statement.setLong(1, id);
        statement.setString(2, "event-synthetic-" + id);
        statement.setLong(3, birdId);
        statement.setString(4, type);
        statement.setString(5, date);
        statement.setString(6, time);
        statement.setObject(7, placeId);
        statement.setString(8, location);
        statement.setString(9, age);
        statement.setString(10, "synthetic:" + id);
        statement.executeUpdate();
    }
}

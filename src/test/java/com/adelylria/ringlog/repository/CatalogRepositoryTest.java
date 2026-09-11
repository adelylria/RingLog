package com.adelylria.ringlog.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.input.PlaceInput;
import com.adelylria.ringlog.model.input.SpeciesInput;
import com.adelylria.ringlog.model.view.PlaceSummary;
import com.adelylria.ringlog.model.view.SpeciesSummary;

public final class CatalogRepositoryTest {

    private CatalogRepositoryTest() {
    }

    public static void placesCanBeCreatedAndEditedWithoutChangingTheirIdentity()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-place-edit-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long placeId = repository.insertPlace(new PlaceInput(
                    "Els Rafals", "Pollença", "Illes Balears", "España",
                    39.8502, 2.9850, "Acceso por el camino principal", true, true
            ));
            String stableKey = stableKey(database, placeId);

            repository.updatePlace(placeId, new PlaceInput(
                    "Els Rafals", "Pollença", "Illes Balears", "España",
                    39.8510, 2.9860, "Acceso actualizado", false, true
            ));

            PlaceSummary place = repository.findPlaceSummaries().stream()
                    .filter(item -> item.id() == placeId)
                    .findFirst()
                    .orElseThrow();
            require("Illes Balears".equals(place.autonomousCommunity()),
                    "The autonomous community must be persisted");
            require("España".equals(place.country()), "The country must be persisted");
            require(Double.valueOf(39.8510).equals(place.latitude())
                            && "Acceso actualizado".equals(place.notes()),
                    "Editing must persist all mutable place fields");
            require(!place.favorite() && place.isDefault(),
                    "Editing must preserve the chosen flags");
            require(stableKey.equals(stableKey(database, placeId)),
                    "Editing a place must never change its stable identity");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void changingTheDefaultPlaceKeepsOnlyOneDefault() throws Exception {
        Path root = Files.createTempDirectory("ringlog-place-default-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long first = repository.insertPlace(new PlaceInput(
                    "Primer lugar", null, null, null, null, false, true
            ));
            repository.insertPlace(new PlaceInput(
                    "Segundo lugar", null, null, null, null, false, true
            ));
            repository.updatePlace(first, new PlaceInput(
                    "Primer lugar", null, null, null, null, false, true
            ));

            List<PlaceSummary> places = repository.findPlaceSummaries();
            require(places.stream().filter(PlaceSummary::isDefault).count() == 1,
                    "There must never be more than one default place");
            require(places.stream().anyMatch(item -> item.id() == first && item.isDefault()),
                    "The edited place must become the new default");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void speciesCanBeEditedWithoutChangingTheirIdentity()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-species-edit-");
        Path database = root.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            CatalogRepository repository = new CatalogRepository(database.toString());
            long speciesId = repository.insertSpecies(new SpeciesInput(
                    "TUR-PHI", "Turdus philomelos", "Zorzal común"
            ));
            String stableKey = stableKey(database, "species", speciesId);

            repository.updateSpecies(speciesId, new SpeciesInput(
                    "TUR-PHI", "Turdus philomelos", "Zorzal común actualizado"
            ));

            SpeciesSummary species = repository.findSpeciesSummaries().stream()
                    .filter(item -> item.id() == speciesId)
                    .findFirst()
                    .orElseThrow();
            require("TUR-PHI".equals(species.code())
                            && "Turdus philomelos".equals(species.scientificName())
                            && "Zorzal común actualizado".equals(species.commonName()),
                    "Editing must persist every mutable species field");
            require("Zorzal común actualizado".equals(species.name()),
                    "The catalog must immediately use the updated display name");
            require(stableKey.equals(stableKey(database, "species", speciesId)),
                    "Editing a species must never change its stable identity");
        } finally {
            deleteDirectory(root);
        }
    }

    private static String stableKey(Path database, long placeId) throws Exception {
        return stableKey(database, "place", placeId);
    }

    private static String stableKey(Path database, String table, long entityId)
            throws Exception {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT stable_key FROM " + table + " WHERE id = " + entityId
             )) {
            require(result.next(), "Expected the persisted catalog entity");
            return result.getString(1);
        }
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        placesCanBeCreatedAndEditedWithoutChangingTheirIdentity();
        changingTheDefaultPlaceKeepsOnlyOneDefault();
        speciesCanBeEditedWithoutChangingTheirIdentity();
        System.out.println("CatalogRepositoryTest: PASS");
    }
}

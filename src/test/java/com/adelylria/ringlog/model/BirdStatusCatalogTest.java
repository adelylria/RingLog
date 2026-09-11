package com.adelylria.ringlog.model;

import java.util.HashSet;
import java.util.List;

public final class BirdStatusCatalogTest {

    private BirdStatusCatalogTest() {
    }

    public static void catalogContainsEveryOfficialCodeExactlyOnce() {
        List<String> expectedCodes = List.of(
                "B0", "B1",
                "F0", "F1", "F2", "F3", "F4", "F5", "F9",
                "E0", "E1", "E2", "E3", "E9",
                "L0", "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9",
                "G0", "G1", "G2", "G3", "G4", "G9",
                "V0", "V1", "V2", "V3", "V4", "V5", "V6", "V9",
                "X0", "X1", "X2", "X3", "X4", "X5", "X6", "X7", "X8", "X9"
        );
        List<String> actualCodes = BirdStatusCatalog.entries().stream()
                .map(BirdStatusCatalog.Entry::code)
                .toList();

        require(actualCodes.equals(expectedCodes),
                "The bird-status catalog must keep the official order and codes");
        require(new HashSet<>(actualCodes).size() == actualCodes.size(),
                "Bird-status codes must be unique");
    }

    public static void statusCanBeResolvedAndDisplayedByCode() {
        BirdStatusCatalog.Entry status = BirdStatusCatalog.find(" f5 ").orElseThrow();

        require("Lesiones previas y enfermedades".equals(status.category()),
                "A status must expose its category");
        require("Lesión en la pata causada por la anilla".equals(status.description()),
                "A status must expose its complete meaning");
        require(BirdStatusCatalog.display("F5").startsWith("F5 · "),
                "The readable status must retain its official code");
        require("CÓDIGO ANTIGUO".equals(BirdStatusCatalog.display("CÓDIGO ANTIGUO")),
                "Unknown historical status values must never be reinterpreted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        catalogContainsEveryOfficialCodeExactlyOnce();
        statusCanBeResolvedAndDisplayedByCode();
        System.out.println("BirdStatusCatalogTest: PASS");
    }
}

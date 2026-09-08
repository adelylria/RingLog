package com.adelylria.ringlog.database;

import java.util.List;

/** Contract tests for ordered, explicitly typed schema migration steps. */
public final class SchemaMigrationRegistryTest {

    private SchemaMigrationRegistryTest() {
    }

    public static void executionTypeIsPartOfEveryMigrationStep() {
        SchemaMigration sql = new TestMigration(
                2,
                3,
                SchemaMigrationExecutionType.SQL_TRANSACTIONAL
        );
        SchemaMigration storage = new TestMigration(
                3,
                4,
                SchemaMigrationExecutionType.STAGED_STORAGE
        );

        require(sql.executionType() == SchemaMigrationExecutionType.SQL_TRANSACTIONAL,
                "The v2 -> v3 step must expose SQL transactional execution");
        require(storage.executionType() == SchemaMigrationExecutionType.STAGED_STORAGE,
                "The v3 -> v4 step must expose staged storage execution");
    }

    public static void registryBuildsAContiguousOrderedPlan() {
        SchemaMigration v4ToV5 = new TestMigration(
                4,
                5,
                SchemaMigrationExecutionType.SQL_TRANSACTIONAL
        );
        SchemaMigration v5ToV6 = new TestMigration(
                5,
                6,
                SchemaMigrationExecutionType.SQL_TRANSACTIONAL
        );
        SchemaMigrationRegistry registry = new SchemaMigrationRegistry(
                List.of(v5ToV6, v4ToV5)
        );

        SchemaUpgradePlan plan = registry.plan(4, 6);

        require(plan.fromSchema() == 4, "The plan must retain its source schema");
        require(plan.targetSchema() == 6, "The plan must retain its target schema");
        require(plan.steps().equals(List.of(v4ToV5, v5ToV6)),
                "The registry must order every contiguous step by source schema");
    }

    public static void registryRejectsMissingAndDuplicateSteps() {
        SchemaMigration v4ToV5 = new TestMigration(
                4,
                5,
                SchemaMigrationExecutionType.SQL_TRANSACTIONAL
        );
        SchemaMigration duplicateV4ToV5 = new TestMigration(
                4,
                5,
                SchemaMigrationExecutionType.STAGED_STORAGE
        );

        requireFailure(
                () -> new SchemaMigrationRegistry(List.of(v4ToV5, duplicateV4ToV5)),
                "A registry must reject two implementations for the same source schema"
        );
        requireFailure(
                () -> new SchemaMigrationRegistry(List.of(v4ToV5)).plan(4, 6),
                "A registry must reject an upgrade path with a missing step"
        );
    }

    public static void currentStepsExposeTheirRealExecutionModel() {
        require(new V2ToV3SchemaMigration().executionType()
                        == SchemaMigrationExecutionType.SQL_TRANSACTIONAL,
                "The real v2 -> v3 migration must be SQL transactional");
        require(new V3ToV4StorageMigration().executionType()
                        == SchemaMigrationExecutionType.STAGED_STORAGE,
                "The real v3 -> v4 migration must be staged storage");
    }

    private static void requireFailure(ThrowingRunnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // Expected contract rejection.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        executionTypeIsPartOfEveryMigrationStep();
        registryBuildsAContiguousOrderedPlan();
        registryRejectsMissingAndDuplicateSteps();
        currentStepsExposeTheirRealExecutionModel();
        System.out.println("SchemaMigrationRegistryTest: PASS");
    }

    private record TestMigration(
            int fromSchema,
            int targetSchema,
            SchemaMigrationExecutionType executionType
    ) implements SchemaMigration {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}

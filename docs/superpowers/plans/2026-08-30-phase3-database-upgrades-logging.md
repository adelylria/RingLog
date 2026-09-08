# RingLog Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit and atomic schema upgrades, validated pre-schema backups, persistent privacy-safe diagnostics, and integrated startup recovery without changing production schema v4.

**Architecture:** `DatabaseUpgradeService` builds an ordered plan from a typed migration registry. SQL chains share one transaction; staged-storage steps delegate to the same v3-to-v4 media engine used by physical-location migration. Backup and logging are independent services injected into orchestration boundaries.

**Tech Stack:** Java 21, SQLite JDBC 3.50.3.0, ORMLite 6.1, Log4j API/Core 2.26.1, Swing, existing standalone Java test suite.

**Spec:** `docs/superpowers/specs/2026-08-30-phase3-database-upgrades-logging-design.md`

## Global Constraints

- `Database.SCHEMA_VERSION` remains `4`.
- Migration execution type is explicitly `SQL_TRANSACTIONAL` or `STAGED_STORAGE`.
- v3-to-v4 has one shared implementation; no special hidden branch in the orchestrator.
- Logging failure alone never blocks application startup.
- Every schema backup has a validated SQLite snapshot and technical JSON manifest.
- No bird observations, raw payloads, snapshots, personal data, tokens, or secrets enter logs or manifests.
- All paths come from `AppPaths`; tests use isolated temporary roots and real JDK 21.
- Do not touch the user's real `ringlog.db` or real photos.
- Do not begin Phase 4.

---

### Task 1: Explicit migration contract and ordered registry

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaMigration.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SqlTransactionalMigration.java`
- Create: `src/main/java/com/adelylria/ringlog/database/StagedStorageMigration.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaMigrationExecutionType.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaMigrationRegistry.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaUpgradePlan.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaVersionDetector.java`
- Create: `src/main/java/com/adelylria/ringlog/database/V2ToV3SchemaMigration.java`
- Modify: `src/main/java/com/adelylria/ringlog/database/SchemaMigrator.java`
- Test: `src/test/java/com/adelylria/ringlog/database/SchemaMigrationRegistryTest.java`

**Interfaces:**
- Produces: `SchemaMigration#fromSchema()`, `targetSchema()`, and `executionType()`.
- Produces: `SqlTransactionalMigration#migrate(Connection)` and `validate(Connection)`.
- Produces: `StagedStorageMigration#migrate(StagedStorageMigrationContext)`.
- Produces: `SchemaMigrationRegistry.plan(int, int)` returning a contiguous `SchemaUpgradePlan`.
- Produces: `SchemaVersionDetector.detect(Path)` mapping exact legacy pragma-zero v2 to logical version 2.

- [ ] **Step 1: Write failing registry and detector tests**

```java
require(registry.plan(4, 6).steps().stream()
        .map(SchemaMigration::executionType).toList()
        .equals(List.of(SQL_TRANSACTIONAL, SQL_TRANSACTIONAL)),
        "The registry must preserve ordered execution types");
require(detector.detect(v2Database).logicalVersion() == 2,
        "Exact pragma-zero v2 must be represented as logical schema 2");
```

- [ ] **Step 2: Run the new test and verify RED because the contract is absent**

Run: `mvn test`

- [ ] **Step 3: Implement the minimal typed contract and extract v2-to-v3**

```java
public interface SchemaMigration {
    int fromSchema();
    int targetSchema();
    SchemaMigrationExecutionType executionType();
}
public interface SqlTransactionalMigration extends SchemaMigration {
    void migrate(Connection connection) throws Exception;
    default void validate(Connection connection) throws Exception { }
}
```

`V2ToV3SchemaMigration` delegates the existing SQL body without owning commit or rollback.

- [ ] **Step 4: Run registry, legacy schema, and full database tests until GREEN**

Run: `mvn test`

### Task 2: Validated pre-schema backup and manifest

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/database/DatabaseIntegrityValidator.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaBackup.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaBackupManifest.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaBackupService.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaBackupException.java`
- Modify: `src/main/java/com/adelylria/ringlog/storage/SQLiteBackupService.java`
- Test: `src/test/java/com/adelylria/ringlog/database/SchemaBackupServiceTest.java`

**Interfaces:**
- Consumes: `SQLiteBackupService.backup(Path, Path)`.
- Produces: `SchemaBackupService.create(AppPaths, Path, int, int, String)`.
- Produces: validated `.db` and `.json` paths plus SHA-256 and size.

- [ ] **Step 1: Write failing backup-order, integrity, metadata, and failure tests**

Assert that a valid backup opens independently, carries the original schema,
has a matching SHA, and publishes a manifest containing only the six approved
keys. Inject a failing snapshot service and assert no migration callback runs.

- [ ] **Step 2: Run the new test and verify RED**

Run: `mvn test`

- [ ] **Step 3: Implement snapshot-to-temporary, validation, and atomic publication**

```java
public record SchemaBackup(
        Path database, Path manifest, int fromSchema, int targetSchema,
        String appVersion, String createdAt, String sha256, long size
) { }
```

The manifest is written only after the backup passes all validation and is
published atomically after its database file.

- [ ] **Step 4: Run backup and database tests until GREEN**

Run: `mvn test`

### Task 3: One staged-storage implementation for v3-to-v4

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/storage/ManagedMediaMigrationService.java`
- Create: `src/main/java/com/adelylria/ringlog/database/V3ToV4StorageMigration.java`
- Modify: `src/main/java/com/adelylria/ringlog/storage/LegacyDataLocationMigrator.java`
- Modify: `src/main/java/com/adelylria/ringlog/storage/ApplicationStorageBootstrap.java`
- Modify: `src/main/java/com/adelylria/ringlog/database/DatabaseUpgradeService.java`
- Test: `src/test/java/com/adelylria/ringlog/storage/ManagedMediaMigrationServiceTest.java`
- Test: `src/test/java/com/adelylria/ringlog/storage/LegacyDataLocationMigratorTest.java`

**Interfaces:**
- Produces: `V3ToV4StorageMigration.type() == STAGED_STORAGE`.
- Produces: a staged service accepting source database, destination `AppPaths`, and publication mode.
- Consumed by both location migration and managed AppData upgrade.

- [ ] **Step 1: Write failing managed-v3 and shared-path tests**

Create a v3 managed database with absolute event and unassigned media paths.
Assert startup reaches v4, references resolve relatively, backup survives, and
original media hashes remain unchanged.

- [ ] **Step 2: Run the tests and verify RED because managed v3 is currently a dead end**

Run: `mvn test`

- [ ] **Step 3: Extract the existing staging algorithm and register it explicitly**

```java
public final class V3ToV4StorageMigration implements StagedStorageMigration {
    public int fromSchema() { return 3; }
    public int targetSchema() { return 4; }
    public SchemaMigrationExecutionType executionType() {
        return SchemaMigrationExecutionType.STAGED_STORAGE;
    }
    public MigrationResult migrate(StagedStorageMigrationContext context) throws Exception {
        return context.mediaMigration().migrate(context);
    }
}
```

The actual execution is supplied by the staged context rather than an `if`
hidden in `DatabaseUpgradeService`.

- [ ] **Step 4: Run location, managed storage, and schema tests until GREEN**

Run: `mvn test`

### Task 4: Atomic DatabaseUpgradeService orchestration

**Files:**
- Modify: `src/main/java/com/adelylria/ringlog/database/DatabaseUpgradeService.java`
- Create: `src/main/java/com/adelylria/ringlog/database/DatabaseUpgradeException.java`
- Create: `src/main/java/com/adelylria/ringlog/database/FutureSchemaVersionException.java`
- Create: `src/main/java/com/adelylria/ringlog/database/DatabaseUpgradeResult.java`
- Test: `src/test/java/com/adelylria/ringlog/database/DatabaseUpgradeServiceTest.java`

**Interfaces:**
- Consumes: detector, registry, backup service, and staged executor.
- Produces: `inspect(Path, MediaPathResolver)` and `upgrade(AppPaths)`.

- [ ] **Step 1: Write the fifteen requested startup, order, rollback, and validation tests**

Use real SQLite fixture migrations v4-to-v5 and v5-to-v6. Record execution
order in fixture tables, fail the second step, and assert both schema and data
rollback to v4 while the pre-upgrade backup remains.

- [ ] **Step 2: Run the service test and verify RED**

Run: `mvn test`

- [ ] **Step 3: Implement plan-before-backup and one transaction for SQL chains**

```java
connection.setAutoCommit(false);
try {
    for (SchemaMigration step : plan.steps()) {
        SqlTransactionalMigration sql = (SqlTransactionalMigration) step;
        sql.migrate(connection);
        setUserVersion(connection, sql.targetSchema());
        sql.validate(connection);
    }
    validator.requireValid(connection, targetVersion);
    connection.commit();
} catch (Exception failure) {
    connection.rollback();
    throw failure;
}
```

Future schemas are rejected before backup creation. Current schemas validate
and return immediately.

- [ ] **Step 4: Run upgrade and complete database tests until GREEN**

Run: `mvn test`

### Task 5: Persistent privacy-safe rolling logging

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/log4j2.xml`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/RingLogLogging.java`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/SafeLog.java`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/LogSanitizer.java`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/BuildInfo.java`
- Test: `src/test/java/com/adelylria/ringlog/diagnostics/RingLogLoggingTest.java`

**Interfaces:**
- Produces: non-throwing `RingLogLogging.initialize(AppPaths)` and `logFile()`.
- Produces: allow-listed `SafeLog` lifecycle methods and sanitized failures.

- [ ] **Step 1: Write failing path, startup context, privacy, fallback, and rotation tests**

Use a temporary `AppPaths`, log a marker larger than 5 MB in bounded chunks,
and assert `ringlog.log` plus no more than five archives. Pass strings containing
`raw_payload`, observations, snapshots, and tokens through failure logging and
assert the secrets are absent from every log file.

- [ ] **Step 2: Run the logging test and verify RED/provider warning**

Run: `mvn test`

- [ ] **Step 3: Add aligned Log4j BOM/API/Core 2.26.1 and configuration**

Configure `${sys:ringlog.logs.dir}/ringlog.log`, a `5MB`
`SizeBasedTriggeringPolicy`, and `DefaultRolloverStrategy min=1 max=5
fileIndex=min`. Logging initialization catches only logging setup failures,
falls back to stderr, and never throws solely because the log file cannot open.

- [ ] **Step 4: Run logging and full tests until GREEN with no provider warning**

Run: `mvn test`

### Task 6: Startup errors, diagnostics, and operation lifecycle logs

**Files:**
- Modify: `src/main/java/com/adelylria/ringlog/RingLog.java`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/DiagnosticsService.java`
- Create: `src/main/java/com/adelylria/ringlog/diagnostics/DiagnosticsSnapshot.java`
- Create: `src/main/java/com/adelylria/ringlog/ui/StartupErrorFrame.java`
- Create: `src/main/java/com/adelylria/ringlog/ui/StartupErrorPanel.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/StorageStartupFrame.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/MainFrame.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/panels/SettingsPanel.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/importexport/ImportExportController.java`
- Modify: import/export and migration orchestration boundaries that still use `System.err`.
- Test: `src/test/java/com/adelylria/ringlog/ui/StartupErrorUiTest.java`
- Test: `src/test/java/com/adelylria/ringlog/ui/SettingsPanelTest.java`
- Test: `src/test/java/com/adelylria/ringlog/diagnostics/OperationLoggingTest.java`

**Interfaces:**
- Produces: sanitized integrated startup error UI.
- Produces: diagnostics snapshot with app, Java, OS, data root, DB, schema, and log path.

- [ ] **Step 1: Write failing UI, diagnostics, and lifecycle tests**

Assert future-schema copy, no raw stacktrace in the visible component, exact
AppPaths diagnostics, safe disabled/open-log action, and import/export start/end
events without source filenames or payload content.

- [ ] **Step 2: Run the new UI/diagnostics tests and verify RED**

Run: `mvn test`

- [ ] **Step 3: Integrate startup error routing, About card, and safe lifecycle logs**

Initialize logging after resolving `AppPaths` and before database startup.
Catch upgrade failures, log sanitized technical details, and open
`StartupErrorFrame`; a logging-only failure continues through normal startup.

- [ ] **Step 4: Run UI, import/export, and full tests until GREEN**

Run: `mvn test`

### Task 7: Full regression, review, and Phase 3 checkpoint

**Files:**
- Modify: `src/test/java/com/adelylria/ringlog/RingLogTestSuite.java`
- Modify: affected existing integration tests to use explicit temporary `AppPaths`.

- [ ] **Step 1: Register every new standalone test in `RingLogTestSuite`**

- [ ] **Step 2: Run isolated functional regression with legacy v5.2, native export/import, backup v3, PDF/XLSX, photos, and reviews**

Run: `mvn clean test`

- [ ] **Step 3: Run clean compile and package with real JDK 21**

Run: `mvn clean compile`

Run: `mvn clean package`

- [ ] **Step 4: Review the complete diff against all 24 requested tests and privacy rules**

Run: `git diff --check`

- [ ] **Step 5: Create the independent Phase 3 commit**

```bash
git add pom.xml src/main src/test docs/superpowers
git commit -m "feat: add database upgrade safety and logging"
```

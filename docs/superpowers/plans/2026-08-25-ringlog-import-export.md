# RingLog Import/Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement lossless legacy-v5 import, current-v3 backup compatibility, native-v1 export/restore, persistent conflict resolution, stable exchange identities, and a themed Swing workflow.

**Architecture:** `ImportCoordinator` detects the workbook profile and delegates parsing to a format-specific reader. Readers only build validated staging models; `ImportTransactionService` applies immutable plans through one ORMLite transaction, including exact stable-key native restore/merge. Legacy v5 rows are authoritative and are never re-inferred.

**Tech Stack:** Java 26, Swing, FlatLaf, SQLite, ORMLite 6.1, Apache POI 5.5.0, Maven custom isolated test suite.

**Spec:** Approved RingLog requirements for legacy v5/v5.2, backup v3, and native export v1.

## Global Constraints

- Support only Legacy Migrator v5/v5.2, RingLog backup v3, and RingLog Export v1.
- Preserve every legacy source record, audit row, warning, conflict value, source reference, snapshot, and note.
- Never use SQLite AUTOINCREMENT values as exchange identities.
- Never reinterpret reconciled v5 species, birds, places, or events.
- Parse and validate before any database write; apply each import atomically.
- Native restore never launches conflict resolution.
- ZIP is mandatory when any physical event or unassigned photo is required.
- Use deterministic content fingerprints excluding variable metadata such as `generated_at`.
- The workspace has no Git repository; commit steps are intentionally omitted.

---

### Task 1: Schema v3 and incremental migration

**Files:**
- Modify: `database/schema.sql`
- Modify: `src/main/java/com/adelylria/ringlog/database/Database.java`
- Create: `src/main/java/com/adelylria/ringlog/database/SchemaMigrator.java`
- Create: entity classes for `import_batch`, aliases, source records, audit, conflicts, warnings, and unassigned photos
- Create: `src/test/java/com/adelylria/ringlog/database/SchemaMigrationTest.java`
- Modify: `src/test/java/com/adelylria/ringlog/RingLogTestSuite.java`

**Interfaces:**
- Produces: `SchemaMigrator.migrate(Connection)` and schema version constant `Database.SCHEMA_VERSION = 3`.
- Produces: persistent UUID `stable_key` on species, bird, place, bird_event, and event_photo; legacy `migration_key` remains separate on bird_event.

- [x] Write a test that creates a fresh DB and asserts `PRAGMA user_version = 3`, all required tables/columns, unique UUIDs, and FK validity.
- [x] Write a test that creates the exact v2 schema with populated rows, runs `Database.initialize(path)`, and asserts all original values remain plus valid UUIDs and default review state.
- [x] Run `SchemaMigrationTest`; expect failure because schema v3 and migration do not exist.
- [x] Add v3 schema and transactional `SchemaMigrator`; populate UUIDs with `UUID.randomUUID()` once during migration and validate no null/duplicate key remains.
- [x] Extend entities and database validation; ensure application inserts assign UUIDs for new core rows.
- [x] Run `SchemaMigrationTest`, existing repository tests, and the isolated suite subset; expect PASS.

### Task 2: Format detection and staging contracts

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/importexport/ImportFormat.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/WorkbookProfile.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/WorkbookFormatDetector.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/ImportSource.java`
- Create: staging row records under `importexport/model/`
- Create: `src/test/java/com/adelylria/ringlog/importexport/WorkbookFormatDetectorTest.java`

**Interfaces:**
- Produces: `WorkbookProfile detect(Path source)` with profiles `LEGACY_V5`, `RINGLOG_BACKUP_V3`, and `RINGLOG_EXPORT_V1`.
- Produces: `ImportSource.open(Path)` exposing a workbook path, media root, and cleanup lifecycle for XLSX/ZIP.

- [x] Write failing detector tests for v5, v3 backup, native v1, unknown format, missing metadata, unsupported version, and missing required sheets.
- [x] Run detector tests and confirm the new symbols are absent.
- [x] Implement metadata-only detection, file/ZIP limits, path traversal protection, and clear Spanish validation errors.
- [x] Run detector tests; expect PASS.

### Task 3: Legacy v5 reader and canonical fingerprint

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/importexport/legacy/LegacyV5WorkbookReader.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/legacy/LegacyImportModel.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/ImportFingerprintService.java`
- Create: `src/test/java/com/adelylria/ringlog/importexport/LegacyV5WorkbookReaderTest.java`
- Create: `src/test/java/com/adelylria/ringlog/importexport/ImportFingerprintServiceTest.java`

**Interfaces:**
- Produces: `LegacyImportModel read(ImportSource source)` containing all workbook sheets without inferred entities.
- Produces: `String fingerprint(LegacyImportModel model)` using SHA-256 over length-prefixed UTF-8 canonical fields and deterministic row ordering.

- [x] Write failing tests for LOST/UNMAPPED, duplicate/missing keys, invalid references, counts, dates, coordinates, default place, seven pending plus one resolved conflict, and preservation of raw whitespace.
- [x] Write a fingerprint test proving row-order independence, `generated_at` independence, and sensitivity to a single value change in every relevant sheet family.
- [x] Run both tests and confirm failure.
- [x] Implement strict v5 reading using the declared rows as authoritative; do not call old inference or reconciliation methods.
- [x] Implement canonical length-prefixed serialization for metadata excluding only volatile keys, species, birds, places, events, photos, unassigned photos, source records, audit, conflicts, and warnings.
- [x] Run both tests and the real v5.2 fixture count test; expect PASS.

### Task 4: Transactional legacy import and persistent conflicts

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/importexport/ImportCoordinator.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/ImportPlan.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/service/ImportTransactionService.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/service/PhotoStorageService.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/service/ConflictResolutionService.java`
- Create: `src/main/java/com/adelylria/ringlog/repository/MigrationConflictRepository.java`
- Create: `src/test/java/com/adelylria/ringlog/importexport/LegacyImportTransactionTest.java`
- Create: `src/test/java/com/adelylria/ringlog/importexport/ConflictResolutionServiceTest.java`

**Interfaces:**
- Produces: `ImportCoordinator.analyze(Path)` with no writes and `execute(ImportPlan)` for one atomic import.
- Produces: `ConflictResolutionService.resolve(long conflictId, ResolutionRequest request)` supporting CANONICAL, ALTERNATIVE, MANUAL_VALUE, and PENDING.

- [x] Write failing integration tests for exact v5 persistence, second-import idempotency, SQL-trigger-induced rollback, external-key maps, source aliases, raw/audit rows, unassigned photos, and already-resolved conflicts.
- [x] Write failing resolution tests that independently assert canonical/no change, alternative update, validated manual update, pending/no change, last-pending marks OK, and remaining-pending stays REVIEW.
- [x] Run tests and confirm they fail before implementation.
- [x] Implement direct entity-key mapping and merge-by-stable/business identity without content inference; any non-equivalent existing row is an explicit validation conflict.
- [x] Persist original conflict evidence unchanged; only resolution columns and operational event fields may change.
- [x] Stage and hash media, insert all database rows in one ORMLite transaction, and delete only newly published files on rollback.
- [x] Run transaction and conflict tests; expect PASS.

### Task 5: Current RingLog backup v3 compatibility

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/importexport/compatibility/RingLogV3BackupReader.java`
- Adapt: existing `LegacyWorkbookReader` helpers without using its legacy inference path
- Create: `src/test/java/com/adelylria/ringlog/importexport/RingLogV3CompatibilityTest.java`

**Interfaces:**
- Produces: v3 backup staging data compatible with the common native restore plan and `requiresConflictReview() == false`.

- [x] Write a failing test around a workbook emitted by the current `BackupExportService`, including embedded photos and text content.
- [x] Run the test and confirm profile detection succeeds but import support is absent.
- [x] Implement the isolated compatibility reader and preserve current same-database/fresh-database behavior.
- [x] Run compatibility plus existing backup tests; expect PASS.

### Task 6: Native RingLog Export v1 and round trip

**Files:**
- Create: `src/main/java/com/adelylria/ringlog/importexport/nativeformat/RingLogExporter.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/nativeformat/RingLogExportReader.java`
- Create: `src/main/java/com/adelylria/ringlog/importexport/nativeformat/NativeExportModel.java`
- Create: `src/test/java/com/adelylria/ringlog/importexport/NativeExportRoundTripTest.java`

**Interfaces:**
- Produces: `ExportResult exportTo(Path destination)` with XLSX only when no required binaries exist and ZIP otherwise.
- Consumes and restores stable UUID keys, import history, pending/resolved conflicts, raw records, audit, warnings, aliases, timestamps, null/empty distinctions, and exact media bytes.

- [x] Write a failing DB→export→fresh DB test comparing every relevant table by stable key and every photo by SHA-256.
- [x] Add tests that media forces ZIP, media-free state permits XLSX, native import skips conflict review, and duplicate export_id is recognized.
- [x] Run round-trip tests and confirm failure.
- [x] Implement a repeatable read snapshot, styled workbook, hidden chunked text storage, ZIP media layout, self-validation, and atomic publication.
- [x] Implement transactional native restore/merge; stable keys are matched exactly and divergent rows are never silently overwritten.
- [x] Run round-trip and backup tests; expect PASS.

### Task 7: Swing workflow and conflicts screen

**Files:**
- Create UI classes under `src/main/java/com/adelylria/ringlog/ui/importexport/`
- Modify: `src/main/java/com/adelylria/ringlog/ui/MainFrame.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/components/Sidebar.java`
- Modify: `src/main/java/com/adelylria/ringlog/ui/panels/SettingsPanel.java`
- Modify: event list/detail view models and renderers for REVIEW state
- Create: `src/test/java/com/adelylria/ringlog/ui/ImportExportUiTest.java`

**Interfaces:**
- Produces: one `ImportExportController` shared by `Archivo` menu and Settings actions.
- Produces: `ConflictsPanel.refresh()` and a themed modal conflict detail workflow.

- [x] Write failing UI tests for menu actions, preview counts, resolved-conflict omission, allowed v5 resolution choices, native no-wizard behavior, filters, and theme-safe components.
- [x] Run UI tests and confirm failure.
- [x] Implement SwingWorker-backed analyze/import/export actions and themed card/modal panels using `UiKit` surfaces rather than raw technical tables.
- [x] Add the Conflicts destination, event REVIEW badge, refresh callbacks, and accessible Spanish labels.
- [x] Run UI and layout tests; expect PASS.

### Task 8: Final integration and verification

**Files:**
- Modify: `src/test/java/com/adelylria/ringlog/RingLogTestSuite.java`
- Update user-facing import/export copy where needed.

**Interfaces:**
- Consumes every previous phase; produces a verified application build.

- [x] Run the real v5.2 fixture integration and assert 4/271/4/289, 271/8/10 event types, 7 pending, 1 resolved, 0 LOST, 0 UNMAPPED, and 9 unassigned photos.
- [x] Run `mvn clean test`; expect all isolated test classes to print PASS and Maven to exit 0.
- [x] Run `mvn clean compile`; expect Maven to exit 0.
- [x] Inspect the final source set and schema/database hashes; confirm no test touched `ringlog.db` and no required scope remains incomplete.

# RingLog Phase 3: Database Upgrades, Safety Backups, and Logging

## Scope

Phase 3 adds an explicit schema-upgrade pipeline, validated pre-upgrade SQLite
backups with technical manifests, persistent privacy-safe logging, integrated
startup failures, and diagnostics. `Database.SCHEMA_VERSION` remains `4` and
Phase 4 distribution work is out of scope.

## Upgrade architecture

Every registered migration exposes `fromSchema()`, `targetSchema()`, and an
execution type: `SQL_TRANSACTIONAL` or `STAGED_STORAGE`. `SqlTransactionalMigration`
receives a JDBC connection; `StagedStorageMigration` receives a staging context.
SQL-only chains run in one transaction and commit only after all target
validations pass. The v3 to v4 step delegates to one shared staged-storage
implementation used both by the legacy-location migrator and managed AppData
databases; it is not a hidden branch in `DatabaseUpgradeService`.

The legacy v2 schema is represented as logical version 2 although its existing
`PRAGMA user_version` is zero. It is accepted only when its exact core-table
shape matches the known v2 contract. Newer-than-supported schemas and unknown
schemas are rejected without modification or downgrade.

## Backups

An upgrade plan is built before any backup. A non-empty plan first creates a
consistent snapshot with SQLite Backup API under `AppPaths.backupsDirectory()`.
The snapshot must be non-empty and pass `quick_check`, `foreign_key_check`, and
original-version validation. Only then are the `.db` and a technical `.json`
manifest published. The manifest contains only `fromSchema`, `targetSchema`,
`appVersion`, `createdAt`, `sha256`, and `databaseFile`. Backups survive both
successful and failed upgrades. Current-schema startup creates no backup.

## Logging and privacy

RingLog uses aligned Log4j API/Core 2.26.1, configured against
`AppPaths.logsDirectory()`. `ringlog.log` rotates at 5 MB and keeps five
archives. Logging initialization failure falls back to stderr and never blocks
startup by itself.

Logs include versions, runtime, OS, technical paths, schema transitions,
backup/import/export lifecycle, durations, stable identifiers, counts, and
sanitized failures. They exclude observations, raw payloads, source snapshots,
personal data, secrets, tokens, and whole entities. Arbitrary exceptions pass
through a sanitizer before being persisted.

## Startup and diagnostics

Fatal startup errors open a themed integrated screen with a human-readable
summary, sanitized details, log location, and an exit action. A future schema
gets a dedicated explanation. Settings gains a small About card showing app,
Java, OS, data root, database schema, and a safe open-log-folder action when
supported.

## Verification

All new behavior is developed test-first. Final verification uses a real JDK
21 with `mvn clean test`, `mvn clean compile`, and `mvn clean package`, plus
isolated functional regressions for legacy v5.2 import, native round-trip,
reports, photos, and reviews. The user's real database and media are never
modified.

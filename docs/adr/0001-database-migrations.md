# 0001 — Database migrations

## Context
NFR-17 requires migrations to run automatically at startup. The backend targets a
Raspberry Pi 3B+ and must keep few dependencies. Go's standard library has no
PostgreSQL driver.

## Decision
- Driver: `github.com/jackc/pgx/v5` (pool included); the only external dependency.
- Migrations are plain, forward-only SQL files (`NNNN_description.sql`) in
  `backend/internal/migrate/migrations/`, embedded in the binary with `embed`.
- A small in-house runner (`internal/migrate`) applies them at startup, each in its
  own transaction, records them in `schema_migrations`, and holds a PostgreSQL
  advisory lock so two instances never migrate concurrently.
- No down migrations. A binary older than the database schema refuses to start;
  rollback means restoring a backup.
- Applied migration files are never edited; fixes go into a new migration.

## Consequences
- No migration tool (goose, golang-migrate) to install or learn; ~150 lines to maintain.
- Upgrading is just deploying a new image. Downgrading requires a `pg_dump` restore,
  so backups should be taken before upgrades (to document with NFR-19).

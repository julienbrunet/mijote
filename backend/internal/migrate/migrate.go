// Package migrate applies the embedded SQL migrations to the database.
//
// Migrations are plain SQL files named NNNN_description.sql. They are applied
// in order, each in its own transaction, and recorded in schema_migrations.
// A PostgreSQL advisory lock prevents two instances from migrating at once.
// Applied migrations must never be edited: add a new file instead.
package migrate

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"log/slog"
	"path"
	"sort"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var embedded embed.FS

// lockID is an arbitrary key for pg_advisory_lock, unique to Mijote.
const lockID = 0x6d696a6f7465 // "mijote"

// Migration is one versioned SQL script.
type Migration struct {
	Version int
	Name    string
	SQL     string
}

// Load returns the embedded migrations sorted by version.
func Load() ([]Migration, error) {
	return load(embedded, "migrations")
}

func load(fsys fs.FS, dir string) ([]Migration, error) {
	entries, err := fs.ReadDir(fsys, dir)
	if err != nil {
		return nil, err
	}
	var ms []Migration
	seen := map[int]string{}
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".sql") {
			continue
		}
		prefix, _, ok := strings.Cut(name, "_")
		v, err := strconv.Atoi(prefix)
		if !ok || err != nil || v <= 0 {
			return nil, fmt.Errorf("migration %q: name must look like NNNN_description.sql", name)
		}
		if other, dup := seen[v]; dup {
			return nil, fmt.Errorf("migrations %q and %q share version %d", other, name, v)
		}
		seen[v] = name
		b, err := fs.ReadFile(fsys, path.Join(dir, name))
		if err != nil {
			return nil, err
		}
		ms = append(ms, Migration{Version: v, Name: strings.TrimSuffix(name, ".sql"), SQL: string(b)})
	}
	sort.Slice(ms, func(i, j int) bool { return ms[i].Version < ms[j].Version })
	return ms, nil
}

// Up applies every embedded migration not yet recorded in the database.
func Up(ctx context.Context, pool *pgxpool.Pool) error {
	ms, err := Load()
	if err != nil {
		return err
	}
	return apply(ctx, pool, ms)
}

func apply(ctx context.Context, pool *pgxpool.Pool, ms []Migration) error {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()

	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", lockID); err != nil {
		return fmt.Errorf("acquire migration lock: %w", err)
	}
	defer conn.Exec(context.Background(), "SELECT pg_advisory_unlock($1)", lockID) //nolint:errcheck

	if _, err := conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version    integer PRIMARY KEY,
		name       text NOT NULL,
		applied_at timestamptz NOT NULL DEFAULT now()
	)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	var current int
	if err := conn.QueryRow(ctx, "SELECT coalesce(max(version), 0) FROM schema_migrations").Scan(&current); err != nil {
		return err
	}
	if n := len(ms); n > 0 && current > ms[n-1].Version {
		return fmt.Errorf("database schema version %d is newer than this binary (%d): downgrades are not supported", current, ms[n-1].Version)
	}

	for _, m := range ms {
		if m.Version <= current {
			continue
		}
		err := pgx.BeginFunc(ctx, conn, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, m.SQL); err != nil {
				return err
			}
			_, err := tx.Exec(ctx, "INSERT INTO schema_migrations (version, name) VALUES ($1, $2)", m.Version, m.Name)
			return err
		})
		if err != nil {
			return fmt.Errorf("migration %s: %w", m.Name, err)
		}
		slog.Info("applied migration", "version", m.Version, "name", m.Name)
	}
	return nil
}

// Version returns the latest applied migration version, 0 if none.
func Version(ctx context.Context, pool *pgxpool.Pool) (int, error) {
	var v int
	err := pool.QueryRow(ctx, "SELECT coalesce(max(version), 0) FROM schema_migrations").Scan(&v)
	return v, err
}

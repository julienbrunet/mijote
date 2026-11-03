package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// Instance figures shown on the "Système" page of the web admin (FR-ADM-05).

// RecordServerStart notes that this server version started, keeping the date
// it first ran on the instance.
func (s *Store) RecordServerStart(ctx context.Context, version string) error {
	_, err := s.Pool.Exec(ctx, `INSERT INTO server_version (version) VALUES ($1)
		ON CONFLICT (version) DO UPDATE SET last_started_at = now()`, version)
	return err
}

// ServerVersion is a server version that ran on the instance.
type ServerVersion struct {
	Version        string
	FirstStartedAt time.Time
	LastStartedAt  time.Time
}

// ServerVersions returns the versions that ran on the instance, newest first.
func (s *Store) ServerVersions(ctx context.Context, limit int) ([]ServerVersion, error) {
	rows, err := s.Pool.Query(ctx, `SELECT version, first_started_at, last_started_at FROM server_version
		ORDER BY first_started_at DESC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[ServerVersion])
}

// InstanceCounts are the content and account figures of the instance.
type InstanceCounts struct {
	Recipes, ArchivedRecipes, RecipesWithoutImage, Tags int
	Users, Members, Readonly, Disabled                  int
	UpcomingMeals, PastMeals, ManualMeals, EmptyMeals   int
	LastPlannedDate                                     *time.Time // furthest planned meal
	LastMealChange                                      *time.Time
	PendingInvitations, AppSessions, AdminSessions      int
	APITokens                                           int
	LoginFailures24h                                    int
	AdminTwoFA                                          bool
	MinRecipes                                          int
}

// InstanceCounts counts recipes, accounts, meals and sessions; today is the
// current date in the family's timezone.
func (s *Store) InstanceCounts(ctx context.Context, today time.Time) (InstanceCounts, error) {
	var c InstanceCounts
	err := s.Pool.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM recipe WHERE archived_at IS NULL)::int,
		(SELECT count(*) FROM recipe WHERE archived_at IS NOT NULL)::int,
		(SELECT count(*) FROM recipe WHERE archived_at IS NULL AND image_path IS NULL)::int,
		(SELECT count(*) FROM tag)::int,
		(SELECT count(*) FROM app_user)::int,
		(SELECT count(*) FROM app_user WHERE role = 'member')::int,
		(SELECT count(*) FROM app_user WHERE role = 'readonly')::int,
		(SELECT count(*) FROM app_user WHERE disabled_at IS NOT NULL)::int,
		(SELECT count(*) FROM menu_slot WHERE deleted_at IS NULL AND date >= $1)::int,
		(SELECT count(*) FROM menu_slot WHERE deleted_at IS NULL AND date < $1)::int,
		(SELECT count(*) FROM menu_slot WHERE deleted_at IS NULL AND date >= $1 AND title IS NOT NULL)::int,
		(SELECT count(*) FROM menu_slot WHERE deleted_at IS NULL AND date >= $1 AND title IS NULL AND recipe_id IS NULL)::int,
		(SELECT max(date) FROM menu_slot WHERE deleted_at IS NULL),
		(SELECT max(updated_at) FROM menu_slot),
		(SELECT count(*) FROM invitation WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > now())::int,
		(SELECT count(DISTINCT family_id) FROM refresh_token WHERE revoked_at IS NULL AND expires_at > now())::int,
		(SELECT count(*) FROM admin_session WHERE revoked_at IS NULL AND expires_at > now()
			AND last_seen_at > now() - idle_seconds * interval '1 second')::int,
		(SELECT count(*) FROM api_token WHERE revoked_at IS NULL AND expires_at > now())::int,
		(SELECT count(*) FROM auth_event WHERE at > now() - interval '24 hours'
			AND event IN ('login_failed', 'locked', '2fa_failed', 'refresh_reuse', 'invite_failed'))::int,
		EXISTS (SELECT 1 FROM user_totp t JOIN app_user u ON u.id = t.user_id WHERE u.role = 'admin' AND t.enabled_at IS NOT NULL),
		(SELECT min_recipes FROM family_settings)`, today).Scan(
		&c.Recipes, &c.ArchivedRecipes, &c.RecipesWithoutImage, &c.Tags,
		&c.Users, &c.Members, &c.Readonly, &c.Disabled,
		&c.UpcomingMeals, &c.PastMeals, &c.ManualMeals, &c.EmptyMeals, &c.LastPlannedDate, &c.LastMealChange,
		&c.PendingInvitations, &c.AppSessions, &c.AdminSessions, &c.APITokens, &c.LoginFailures24h,
		&c.AdminTwoFA, &c.MinRecipes)
	return c, err
}

// TableSize is the on-disk size of a table, its indexes included.
type TableSize struct {
	Name  string
	Bytes int64
}

// DatabaseInfo describes the PostgreSQL database of the instance.
type DatabaseInfo struct {
	Version         string
	Bytes           int64
	Connections     int // open connections to this database
	SchemaVersion   int
	InstalledAt     time.Time // first migration applied: the instance was installed
	LastMigrationAt time.Time
	Tables          []TableSize // largest first
	ImagePaths      []string    // image files referenced by recipes
}

// DatabaseInfo returns the size and state of the database.
func (s *Store) DatabaseInfo(ctx context.Context, tables int) (DatabaseInfo, error) {
	var d DatabaseInfo
	err := s.Pool.QueryRow(ctx, `SELECT split_part(current_setting('server_version'), ' ', 1), pg_database_size(current_database()),
		(SELECT count(*) FROM pg_stat_activity WHERE datname = current_database())::int,
		(SELECT coalesce(max(version), 0) FROM schema_migrations),
		(SELECT min(applied_at) FROM schema_migrations), (SELECT max(applied_at) FROM schema_migrations)`).Scan(
		&d.Version, &d.Bytes, &d.Connections, &d.SchemaVersion, &d.InstalledAt, &d.LastMigrationAt)
	if err != nil {
		return d, err
	}
	rows, err := s.Pool.Query(ctx, `SELECT c.relname::text, pg_total_relation_size(c.oid)
		FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE c.relkind = 'r' AND n.nspname = current_schema()
		ORDER BY 2 DESC, 1 LIMIT $1`, tables)
	if err != nil {
		return d, err
	}
	if d.Tables, err = pgx.CollectRows(rows, pgx.RowToStructByPos[TableSize]); err != nil {
		return d, err
	}
	rows, err = s.Pool.Query(ctx, `SELECT image_path FROM recipe WHERE image_path IS NOT NULL`)
	if err != nil {
		return d, err
	}
	d.ImagePaths, err = pgx.CollectRows(rows, pgx.RowTo[string])
	return d, err
}

// RecipeUsage tells how often a recipe was served.
type RecipeUsage struct {
	ID, Title string
	DishType  string
	Archived  bool
	Count     int        // meals served in the period
	Total     int        // meals served since the beginning
	Last      *time.Time // last time it was served
	Next      *time.Time // next planned meal with it
}

// DishShare is the share of a dish type among the meals served in a period.
type DishShare struct {
	DishType string
	Count    int
	Pct      int // of the meals with a recipe
	Target   int // family target, percent
}

// RecipeStats is the usage of the recipes in the meals.
type RecipeStats struct {
	Recipes []RecipeUsage // active recipes, and archived ones served in the period; most served first
	Split   []DishShare
	Served  int // meals with a recipe served in the period
	Manual  int // manual meals in the period
	Never   int // active recipes never served
}

// RecipeStats computes how the recipes were used in the past meals since the
// given date (nil: since the beginning), until yesterday.
func (s *Store) RecipeStats(ctx context.Context, today time.Time, since *time.Time) (RecipeStats, error) {
	var st RecipeStats
	rows, err := s.Pool.Query(ctx, `SELECT r.id::text, r.title, r.dish_type::text, r.archived_at IS NOT NULL,
			count(m.id) FILTER (WHERE m.date < $1 AND ($2::date IS NULL OR m.date >= $2))::int AS n,
			count(m.id) FILTER (WHERE m.date < $1)::int,
			max(m.date) FILTER (WHERE m.date < $1) AS last,
			min(m.date) FILTER (WHERE m.date >= $1)
		FROM recipe r LEFT JOIN menu_slot m ON m.recipe_id = r.id AND m.deleted_at IS NULL
		GROUP BY r.id
		HAVING r.archived_at IS NULL OR count(m.id) FILTER (WHERE m.date < $1 AND ($2::date IS NULL OR m.date >= $2)) > 0
		ORDER BY n DESC, last DESC NULLS LAST, lower(r.title)`, today, since)
	if err != nil {
		return st, err
	}
	st.Recipes, err = pgx.CollectRows(rows, pgx.RowToStructByPos[RecipeUsage])
	if err != nil {
		return st, err
	}
	var meat, fish, veg int
	if err := s.Pool.QueryRow(ctx, `SELECT target_meat_pct, target_fish_pct, target_veg_pct,
			(SELECT count(*) FROM menu_slot WHERE deleted_at IS NULL AND title IS NOT NULL AND date < $1 AND ($2::date IS NULL OR date >= $2))::int
		FROM family_settings`, today, since).Scan(&meat, &fish, &veg, &st.Manual); err != nil {
		return st, err
	}
	counts := map[string]int{}
	for _, r := range st.Recipes {
		counts[r.DishType] += r.Count
		st.Served += r.Count
		if !r.Archived && r.Total == 0 {
			st.Never++
		}
	}
	for _, d := range []struct {
		code   string
		target int
	}{{"meat", meat}, {"fish", fish}, {"vegetarian", veg}} {
		sh := DishShare{DishType: d.code, Count: counts[d.code], Target: d.target}
		if st.Served > 0 {
			sh.Pct = (sh.Count*100 + st.Served/2) / st.Served
		}
		st.Split = append(st.Split, sh)
	}
	return st, nil
}

package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// Meal list of the web admin: every planned meal of the instance, with who
// planned it (requirements.md §4.8, FR-ADM-08).

// MealListMaxRows caps one page of the meal list (past meals grow forever).
const MealListMaxRows = 300

// MealListFilter selects the planned meals shown in the web admin.
type MealListFilter struct {
	Today  time.Time // in the family's timezone
	Past   bool      // past meals (most recent first) instead of today and later
	UserID string    // only the meals last planned by this account ("" = everyone)
}

// MealListItem is one planned meal with its author.
type MealListItem struct {
	ID          string
	Date        time.Time
	Meal        string // lunch / dinner
	RecipeID    *string
	RecipeTitle *string
	Archived    bool    // the recipe was archived since
	Title       *string // manual meal
	Locked      bool
	PlannedBy   *string // display name of the account that last planned or changed the meal
	PlannedAt   time.Time
}

// MealList returns the planned meals matching f, at most MealListMaxRows.
// It also reports whether more rows exist.
func (s *Store) MealList(ctx context.Context, f MealListFilter) ([]MealListItem, bool, error) {
	cond, order := `m.date >= $1`, `m.date, m.meal`
	if f.Past {
		cond, order = `m.date < $1`, `m.date DESC, m.meal`
	}
	var user *string
	if f.UserID != "" {
		if !validUUID(f.UserID) {
			return []MealListItem{}, false, nil
		}
		user = &f.UserID
	}
	rows, err := s.Pool.Query(ctx, `SELECT m.id::text, m.date, m.meal::text, r.id::text, r.title, r.archived_at IS NOT NULL,
			m.title, m.locked, u.display_name, m.updated_at
		FROM menu_slot m
		LEFT JOIN recipe r ON r.id = m.recipe_id
		LEFT JOIN app_user u ON u.id = m.updated_by
		WHERE m.deleted_at IS NULL AND `+cond+` AND ($2::uuid IS NULL OR m.updated_by = $2)
		ORDER BY `+order+` LIMIT $3`, f.Today, user, MealListMaxRows+1)
	if err != nil {
		return nil, false, err
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (MealListItem, error) {
		var m MealListItem
		err := row.Scan(&m.ID, &m.Date, &m.Meal, &m.RecipeID, &m.RecipeTitle, &m.Archived,
			&m.Title, &m.Locked, &m.PlannedBy, &m.PlannedAt)
		return m, err
	})
	if err != nil {
		return nil, false, err
	}
	more := len(list) > MealListMaxRows
	if more {
		list = list[:MealListMaxRows]
	}
	if list == nil {
		list = []MealListItem{}
	}
	return list, more, nil
}

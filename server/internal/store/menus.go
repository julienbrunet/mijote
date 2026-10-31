package store

import (
	"context"
	"errors"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/julienbrunet/mijote/server/internal/menu"
)

// Menus (FR-GEN, requirements.md §9 menu_slot and §10).

// Generation limits.
const (
	MaxGeneratedMeals = 42 // three weeks of lunches and dinners
	maxGenerationDays = 60 // how far generation looks for the requested meals
)

// Meal plans accepted by GenerateMenu.
const (
	PlanDefault     = "default"      // family default meals per weekday
	PlanLunchDinner = "lunch_dinner" // every day
	PlanDinner      = "dinner"       // dinner only, every day
)

// ErrNotEnoughRecipes is returned when the recipe base is below the family's
// minimum (FR-GEN-02).
type ErrNotEnoughRecipes struct{ Have, Need int }

func (e *ErrNotEnoughRecipes) Error() string { return "not enough recipes" }

// MenuRecipe is the recipe summary shown in a menu.
type MenuRecipe struct {
	ID           string  `json:"id"`
	Title        string  `json:"title"`
	DishType     string  `json:"dishType"`
	PrepMinutes  *int    `json:"prepMinutes"`
	TotalMinutes *int    `json:"totalMinutes"`
	Image        *string `json:"image"`
}

// MenuSlot is one planned meal.
type MenuSlot struct {
	ID      string      `json:"id"`
	Date    string      `json:"date"` // YYYY-MM-DD
	Meal    string      `json:"meal"`
	Recipe  *MenuRecipe `json:"recipe"`
	Locked  bool        `json:"locked"`
	Version int         `json:"version"`
}

// GenerationInfo tells the app whether generation is possible and its defaults.
type GenerationInfo struct {
	RecipeCount  int      `json:"recipeCount"`
	MinRecipes   int      `json:"minRecipes"`
	HorizonDays  int      `json:"horizonDays"`
	DefaultMeals []string `json:"defaultMeals"` // ISO weekdays, Monday first
}

type familySettings struct {
	defaultMeals []string
	horizon      int
	min          int
	timezone     string
	plan         menu.Settings
}

func loadSettings(ctx context.Context, q querier, forUpdate bool) (familySettings, error) {
	var f familySettings
	var weekend []int16
	var meat, fish, veg int
	sql := `SELECT default_meals::text[], horizon_days, quick_max_minutes, weekend_days, recent_use_days,
		target_meat_pct, target_fish_pct, target_veg_pct, min_recipes, timezone FROM family_settings`
	if forUpdate {
		sql += ` FOR UPDATE`
	}
	err := q.QueryRow(ctx, sql).Scan(&f.defaultMeals, &f.horizon, &f.plan.QuickMaxMinutes, &weekend,
		&f.plan.RecentUseDays, &meat, &fish, &veg, &f.min, &f.timezone)
	for _, d := range weekend {
		f.plan.Weekend = append(f.plan.Weekend, time.Weekday(d%7)) // ISO 7 = Sunday = 0
	}
	f.plan.Target = map[string]int{"meat": meat, "fish": fish, "vegetarian": veg}
	return f, err
}

// Today returns the current date in the family's timezone.
func (s *Store) Today(ctx context.Context) (time.Time, error) {
	var tz string
	if err := s.Pool.QueryRow(ctx, `SELECT timezone FROM family_settings`).Scan(&tz); err != nil {
		return time.Time{}, err
	}
	loc, err := time.LoadLocation(tz)
	if err != nil {
		loc = time.Local
	}
	y, m, d := s.now().In(loc).Date()
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC), nil
}

// GenerationInfo returns what the app needs to offer generation.
func (s *Store) GenerationInfo(ctx context.Context) (GenerationInfo, error) {
	f, err := loadSettings(ctx, s.Pool, false)
	if err != nil {
		return GenerationInfo{}, err
	}
	info := GenerationInfo{MinRecipes: f.min, HorizonDays: f.horizon, DefaultMeals: f.defaultMeals}
	err = s.Pool.QueryRow(ctx, `SELECT count(*) FROM recipe WHERE archived_at IS NULL`).Scan(&info.RecipeCount)
	return info, err
}

// MenuSlots returns the planned meals between two dates (inclusive).
func (s *Store) MenuSlots(ctx context.Context, from, to time.Time) ([]MenuSlot, error) {
	return menuSlotsIn(ctx, s.Pool, from, to)
}

func menuSlotsIn(ctx context.Context, q querier, from, to time.Time) ([]MenuSlot, error) {
	rows, err := q.Query(ctx, `SELECT m.id::text, to_char(m.date, 'YYYY-MM-DD'), m.meal::text, m.locked, m.version,
			r.id::text, r.title, r.dish_type::text, r.prep_minutes, r.total_minutes, r.image_path
		FROM menu_slot m LEFT JOIN recipe r ON r.id = m.recipe_id
		WHERE m.deleted_at IS NULL AND m.date BETWEEN $1 AND $2
		ORDER BY m.date, m.meal`, from, to)
	if err != nil {
		return nil, err
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (MenuSlot, error) {
		var m MenuSlot
		var id, title, dish, img *string
		var r MenuRecipe
		err := row.Scan(&m.ID, &m.Date, &m.Meal, &m.Locked, &m.Version, &id, &title, &dish, &r.PrepMinutes, &r.TotalMinutes, &img)
		if id != nil {
			r.ID, r.Title, r.DishType = *id, *title, *dish
			if img != nil {
				r.Image = Recipe{ID: r.ID, ImagePath: img}.imageURL()
			}
			m.Recipe = &r
		}
		return m, err
	})
	if list == nil {
		list = []MenuSlot{}
	}
	return list, err
}

// GenerateRequest asks for a number of meals starting on a date.
type GenerateRequest struct {
	Start time.Time
	Meals int
	Plan  string
}

// mealsFor returns the meals planned on a day.
func mealsFor(plan string, defaults []string, d time.Time) []string {
	if plan == PlanDefault {
		plan = defaults[(int(d.Weekday())+6)%7]
	}
	switch plan {
	case PlanLunchDinner:
		return []string{"lunch", "dinner"}
	case PlanDinner:
		return []string{"dinner"}
	}
	return nil
}

// GenerateMenu plans req.Meals meals from req.Start (requirements.md §10).
// Locked slots of the period are kept and count among the meals; the other
// slots of the period are refilled, and unlocked slots that the new plan
// leaves out are removed (on the last day, slots after the last requested
// meal are left alone).
func (s *Store) GenerateMenu(ctx context.Context, userID string, req GenerateRequest) ([]MenuSlot, error) {
	if req.Meals < 1 || req.Meals > MaxGeneratedMeals {
		return nil, invalid("le nombre de repas doit être compris entre 1 et 42")
	}
	if !slices.Contains([]string{PlanDefault, PlanLunchDinner, PlanDinner}, req.Plan) {
		return nil, invalid("formule de repas inconnue")
	}
	var out []MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		// Locking the settings row serializes concurrent generations.
		f, err := loadSettings(ctx, tx, true)
		if err != nil {
			return err
		}
		recipes, err := planRecipes(ctx, tx)
		if err != nil {
			return err
		}
		if len(recipes) < f.min || len(recipes) == 0 {
			return &ErrNotEnoughRecipes{Have: len(recipes), Need: max(f.min, 1)}
		}

		// The period: the requested number of meals, day after day.
		var slots []menu.Slot
		end := req.Start
		for d := req.Start; len(slots) < req.Meals && d.Before(req.Start.AddDate(0, 0, maxGenerationDays)); d = d.AddDate(0, 0, 1) {
			for _, m := range mealsFor(req.Plan, f.defaultMeals, d) {
				if len(slots) < req.Meals {
					slots = append(slots, menu.Slot{Date: d, Meal: m})
					end = d
				}
			}
		}
		if len(slots) == 0 {
			return invalid("aucun repas n'est prévu dans les réglages de la famille")
		}

		// Existing slots: locked ones are kept, the others go.
		type key struct {
			date string
			meal string
		}
		locked := map[key]string{}
		rows, err := tx.Query(ctx, `SELECT to_char(date, 'YYYY-MM-DD'), meal::text, coalesce(recipe_id::text, '')
			FROM menu_slot WHERE deleted_at IS NULL AND locked AND date BETWEEN $1 AND $2`, req.Start, end)
		if err != nil {
			return err
		}
		for rows.Next() {
			var k key
			var rid string
			if err := rows.Scan(&k.date, &k.meal, &rid); err != nil {
				return err
			}
			locked[k] = rid
		}
		if err := rows.Err(); err != nil {
			return err
		}
		for i, sl := range slots {
			if rid, ok := locked[key{sl.Date.Format(time.DateOnly), sl.Meal}]; ok {
				slots[i].RecipeID, slots[i].Locked = rid, true
			}
		}

		last, err := lastUsed(ctx, tx, req.Start)
		if err != nil {
			return err
		}
		p := menu.Planner{Recipes: recipes, LastUsed: last, Settings: f.plan}
		p.Fill(slots)

		now := s.now()
		if _, err := tx.Exec(ctx, `UPDATE menu_slot SET deleted_at = $3, updated_at = $3, updated_by = $4, version = version + 1
			WHERE deleted_at IS NULL AND NOT locked AND date >= $1 AND date < $2`, req.Start, end, now, userID); err != nil {
			return err
		}
		for _, sl := range slots {
			if sl.Locked {
				continue
			}
			var rid *string
			if sl.RecipeID != "" {
				rid = &sl.RecipeID
			}
			if _, err := tx.Exec(ctx, `INSERT INTO menu_slot (date, meal, recipe_id, updated_by, updated_at)
				VALUES ($1, $2, $3, $4, $5)
				ON CONFLICT (date, meal) DO UPDATE SET recipe_id = $3, locked = false, deleted_at = NULL,
					updated_by = $4, updated_at = $5, version = menu_slot.version + 1`,
				sl.Date, sl.Meal, rid, userID, now); err != nil {
				return err
			}
		}
		out, err = menuSlotsIn(ctx, tx, req.Start, end)
		return err
	})
	return out, err
}

func planRecipes(ctx context.Context, tx pgx.Tx) ([]menu.Recipe, error) {
	rows, err := tx.Query(ctx, `SELECT id::text, dish_type::text,
			coalesce(total_minutes, prep_minutes + cook_minutes, prep_minutes, cook_minutes),
			seasons::text[], meal_types::text[]
		FROM recipe WHERE archived_at IS NULL ORDER BY id`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (menu.Recipe, error) {
		var r menu.Recipe
		err := row.Scan(&r.ID, &r.DishType, &r.Minutes, &r.Seasons, &r.Meals)
		return r, err
	})
}

// lastUsed returns, per recipe, the last date it was served before a date
// (history kept in menu_slot, FR-GEN-03).
func lastUsed(ctx context.Context, tx pgx.Tx, before time.Time) (map[string]time.Time, error) {
	rows, err := tx.Query(ctx, `SELECT recipe_id::text, max(date) FROM menu_slot
		WHERE deleted_at IS NULL AND recipe_id IS NOT NULL AND date < $1 GROUP BY recipe_id`, before)
	if err != nil {
		return nil, err
	}
	out := map[string]time.Time{}
	for rows.Next() {
		var id string
		var d time.Time
		if err := rows.Scan(&id, &d); err != nil {
			return nil, err
		}
		out[id] = d
	}
	return out, rows.Err()
}

// IsNotEnoughRecipes reports whether err is an *ErrNotEnoughRecipes.
func IsNotEnoughRecipes(err error) (*ErrNotEnoughRecipes, bool) {
	var e *ErrNotEnoughRecipes
	ok := errors.As(err, &e)
	return e, ok
}

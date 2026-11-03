package store

import (
	"context"
	"errors"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

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
	PlanLunch       = "lunch"        // lunch only, every day
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
	ID     string      `json:"id"`
	Date   string      `json:"date"` // YYYY-MM-DD
	Meal   string      `json:"meal"`
	Recipe *MenuRecipe `json:"recipe"`
	// Title is the free text of a manual meal ("Commander une pizza"); a slot
	// has a recipe or a title, never both (both null: no recipe suited it).
	Title *string `json:"title"`
	// People is the number of covers of the meal, shared by the family (1–20); null for
	// meals planned before it existed (clients then use the user's preference).
	People  *int `json:"people"`
	Locked  bool `json:"locked"`
	Version int  `json:"version"`
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
	rows, err := q.Query(ctx, `SELECT m.id::text, to_char(m.date, 'YYYY-MM-DD'), m.meal::text, m.title, m.people::int, m.locked, m.version,
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
		err := row.Scan(&m.ID, &m.Date, &m.Meal, &m.Title, &m.People, &m.Locked, &m.Version, &id, &title, &dish, &r.PrepMinutes, &r.TotalMinutes, &img)
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
	case PlanLunch:
		return []string{"lunch"}
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
	slots, _, err := s.generateMenu(ctx, userID, req, false)
	return slots, err
}

var errPreviewRollback = errors.New("preview: roll back planned meals")

// generateMenu reuses the planner for a non-persistent preview when preview is true.
func (s *Store) generateMenu(ctx context.Context, userID string, req GenerateRequest, preview bool) ([]MenuSlot, []MenuSlot, error) {
	if req.Meals < 1 || req.Meals > MaxGeneratedMeals {
		return nil, nil, invalid("le nombre de repas doit être compris entre 1 et 42")
	}
	if !slices.Contains([]string{PlanDefault, PlanLunchDinner, PlanLunch, PlanDinner}, req.Plan) {
		return nil, nil, invalid("formule de repas inconnue")
	}
	var out, baseline []MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		// Locking the settings row serializes concurrent generations.
		f, err := loadSettings(ctx, tx, true)
		if err != nil {
			return err
		}
		if preview {
			baseline, err = menuSlotsIn(ctx, tx, req.Start, req.Start.AddDate(0, 0, maxGenerationDays-1))
			if err != nil {
				return err
			}
		}
		recipes, err := planRecipes(ctx, tx)
		if err != nil {
			return err
		}
		if len(recipes) < f.min || len(recipes) == 0 {
			return &ErrNotEnoughRecipes{Have: len(recipes), Need: max(f.min, 1)}
		}
		// New meals are planned for the number of people of whoever generates them.
		people, err := userPeople(ctx, tx, userID)
		if err != nil {
			return err
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

		// Existing slots: locked ones and manual meals are kept, the others go.
		type key struct {
			date string
			meal string
		}
		locked := map[key]string{}
		rows, err := tx.Query(ctx, `SELECT to_char(date, 'YYYY-MM-DD'), meal::text, coalesce(recipe_id::text, '')
			FROM menu_slot WHERE deleted_at IS NULL AND (locked OR title IS NOT NULL) AND date BETWEEN $1 AND $2`, req.Start, end)
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
			WHERE deleted_at IS NULL AND NOT locked AND title IS NULL AND date >= $1 AND date < $2`, req.Start, end, now, userID); err != nil {
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
			if _, err := tx.Exec(ctx, `INSERT INTO menu_slot (date, meal, recipe_id, updated_by, updated_at, people)
				VALUES ($1, $2, $3, $4, $5, $6)
				ON CONFLICT (date, meal) DO UPDATE SET recipe_id = $3, title = NULL, locked = false, deleted_at = NULL,
					updated_by = $4, updated_at = $5, people = $6, version = menu_slot.version + 1`,
				sl.Date, sl.Meal, rid, userID, now, people); err != nil {
				return err
			}
		}
		out, err = menuSlotsIn(ctx, tx, req.Start, end)
		if err == nil && preview {
			return errPreviewRollback
		}
		return err
	})
	if errors.Is(err, errPreviewRollback) {
		err = nil
	}
	return out, baseline, err
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

// slotWindow is how many days around a slot count as "the period" when a single
// meal is picked (swap, extra meal): recipes planned there are not proposed again.
const slotWindow = 6

// pickFor chooses a recipe for the slot (date, meal) among the recipes that
// suit it, never returning exclude. The meals planned around it are the period.
func pickFor(ctx context.Context, tx pgx.Tx, date time.Time, meal, exclude string) (string, error) {
	f, err := loadSettings(ctx, tx, false)
	if err != nil {
		return "", err
	}
	recipes, err := planRecipes(ctx, tx)
	if err != nil {
		return "", err
	}
	if len(recipes) < f.min || len(recipes) == 0 {
		return "", &ErrNotEnoughRecipes{Have: len(recipes), Need: max(f.min, 1)}
	}
	around, err := menuSlotsIn(ctx, tx, date.AddDate(0, 0, -slotWindow), date.AddDate(0, 0, slotWindow))
	if err != nil {
		return "", err
	}
	var slots []menu.Slot
	for _, m := range around {
		d, _ := time.Parse(time.DateOnly, m.Date)
		if d.Equal(date) && m.Meal == meal {
			continue
		}
		sl := menu.Slot{Date: d, Meal: m.Meal, Locked: m.Locked}
		if m.Recipe != nil {
			sl.RecipeID = m.Recipe.ID
		}
		slots = append(slots, sl)
	}
	slots = append(slots, menu.Slot{Date: date, Meal: meal})
	last, err := lastUsed(ctx, tx, date)
	if err != nil {
		return "", err
	}
	p := menu.Planner{Recipes: recipes, LastUsed: last, Settings: f.plan}
	return p.Pick(slots, len(slots)-1, exclude), nil
}

func slotByID(ctx context.Context, tx pgx.Tx, id string) (MenuSlot, error) {
	var date time.Time
	var meal string
	var locked bool
	var rid *string
	err := tx.QueryRow(ctx, `SELECT date, meal::text, locked, recipe_id::text FROM menu_slot
		WHERE id::text = $1 AND deleted_at IS NULL FOR UPDATE`, id).Scan(&date, &meal, &locked, &rid)
	if err != nil {
		return MenuSlot{}, notFound(err)
	}
	m := MenuSlot{ID: id, Date: date.Format(time.DateOnly), Meal: meal, Locked: locked}
	if rid != nil {
		m.Recipe = &MenuRecipe{ID: *rid}
	}
	return m, nil
}

func oneSlot(ctx context.Context, tx pgx.Tx, id string) (MenuSlot, error) {
	var date time.Time
	if err := tx.QueryRow(ctx, `SELECT date FROM menu_slot WHERE id::text = $1`, id).Scan(&date); err != nil {
		return MenuSlot{}, notFound(err)
	}
	list, err := menuSlotsIn(ctx, tx, date, date)
	if err != nil {
		return MenuSlot{}, err
	}
	for _, m := range list {
		if m.ID == id {
			return m, nil
		}
	}
	return MenuSlot{}, ErrNotFound
}

// SwapSlot replaces the recipe of a slot by another suitable one (FR-ADJ-01,
// requirements.md §10). Asking for another recipe releases a locked slot and
// turns a manual meal back into a recipe.
func (s *Store) SwapSlot(ctx context.Context, userID, id string) (MenuSlot, error) {
	var out MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		cur, err := slotByID(ctx, tx, id)
		if err != nil {
			return err
		}
		exclude := ""
		if cur.Recipe != nil {
			exclude = cur.Recipe.ID
		}
		date, _ := time.Parse(time.DateOnly, cur.Date)
		rid, err := pickFor(ctx, tx, date, cur.Meal, exclude)
		if err != nil {
			return err
		}
		if rid == "" {
			return invalid("aucune autre recette ne convient pour ce repas")
		}
		if _, err := tx.Exec(ctx, `UPDATE menu_slot SET recipe_id = $2, title = NULL, locked = false,
			updated_by = $3, updated_at = $4, version = version + 1 WHERE id::text = $1`, id, rid, userID, s.now()); err != nil {
			return err
		}
		out, err = oneSlot(ctx, tx, id)
		return err
	})
	return out, err
}

// Longest title of a manual meal.
const maxMealTitleLen = 100

// SlotRequest plans one meal on a day. With neither RecipeID nor Title, the
// server picks a recipe; with RecipeID, that recipe is planned; with Title, a
// manual meal ("Commander une pizza") is planned. Without Replace, a meal
// already planned at that date and time is an error (ErrSlotExists).
type SlotRequest struct {
	Date     time.Time
	Meal     string
	RecipeID string
	Title    string
	Replace  bool
}

// checkTitle trims and validates the title of a manual meal.
func checkTitle(t string) (string, error) {
	t = strings.TrimSpace(t)
	switch {
	case t == "":
		return "", invalid("l'intitulé du repas est obligatoire")
	case utf8.RuneCountInString(t) > maxMealTitleLen:
		return "", invalid("l'intitulé du repas est trop long (100 caractères maximum)")
	}
	return t, nil
}

// activeRecipe checks that a recipe exists and is not archived.
func activeRecipe(ctx context.Context, tx pgx.Tx, id string) error {
	if !validUUID(id) {
		return invalid("recette inconnue")
	}
	var ok bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM recipe WHERE id = $1 AND archived_at IS NULL)`, id).Scan(&ok); err != nil {
		return err
	}
	if !ok {
		return invalid("recette inconnue ou archivée")
	}
	return nil
}

// AddSlot plans one meal (lunch or dinner) on a day (FR-PLAN-04, FR-ADJ-04).
// A recipe or a manual meal chosen by the user is locked, so that generating
// the period again keeps it.
func (s *Store) AddSlot(ctx context.Context, userID string, req SlotRequest) (MenuSlot, error) {
	if req.Meal != "lunch" && req.Meal != "dinner" {
		return MenuSlot{}, invalid("le repas doit être le midi ou le soir")
	}
	if req.RecipeID != "" && req.Title != "" {
		return MenuSlot{}, invalid("un repas est une recette ou un intitulé libre, pas les deux")
	}
	var title *string
	if req.Title != "" {
		t, err := checkTitle(req.Title)
		if err != nil {
			return MenuSlot{}, err
		}
		title = &t
	}
	var out MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := loadSettings(ctx, tx, true); err != nil { // serializes with generation
			return err
		}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM menu_slot WHERE date = $1 AND meal = $2::meal AND deleted_at IS NULL)`,
			req.Date, req.Meal).Scan(&exists); err != nil {
			return err
		}
		if exists && !req.Replace {
			return ErrSlotExists
		}
		var rp *string
		chosen := req.RecipeID != "" || title != nil
		switch {
		case req.RecipeID != "":
			if err := activeRecipe(ctx, tx, req.RecipeID); err != nil {
				return err
			}
			rp = &req.RecipeID
		case title == nil:
			rid, err := pickFor(ctx, tx, req.Date, req.Meal, "")
			if err != nil {
				return err
			}
			if rid != "" {
				rp = &rid
			}
		}
		var id string
		people, err := userPeople(ctx, tx, userID)
		if err != nil {
			return err
		}
		err = tx.QueryRow(ctx, `INSERT INTO menu_slot (date, meal, recipe_id, title, locked, updated_by, updated_at, people)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
			ON CONFLICT (date, meal) DO UPDATE SET recipe_id = $3, title = $4, locked = $5, deleted_at = NULL,
				updated_by = $6, updated_at = $7, people = $8, version = menu_slot.version + 1
			RETURNING id::text`, req.Date, req.Meal, rp, title, chosen, userID, s.now(), people).Scan(&id)
		if err != nil {
			return err
		}
		out, err = oneSlot(ctx, tx, id)
		return err
	})
	return out, err
}

// UpdateSlot changes a planned meal into the given recipe or manual meal
// (exactly one of recipeID and title). The meal is then locked, as in AddSlot.
func (s *Store) UpdateSlot(ctx context.Context, userID, id, recipeID, title string) (MenuSlot, error) {
	if (recipeID == "") == (title == "") {
		return MenuSlot{}, invalid("indiquez une recette ou un intitulé libre")
	}
	var tp, rp *string
	if title != "" {
		t, err := checkTitle(title)
		if err != nil {
			return MenuSlot{}, err
		}
		tp = &t
	}
	var out MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := slotByID(ctx, tx, id); err != nil {
			return err
		}
		if recipeID != "" {
			if err := activeRecipe(ctx, tx, recipeID); err != nil {
				return err
			}
			rp = &recipeID
		}
		if _, err := tx.Exec(ctx, `UPDATE menu_slot SET recipe_id = $2, title = $3, locked = true,
			updated_by = $4, updated_at = $5, version = version + 1 WHERE id::text = $1`, id, rp, tp, userID, s.now()); err != nil {
			return err
		}
		var err error
		out, err = oneSlot(ctx, tx, id)
		return err
	})
	return out, err
}

// ErrSlotExists is returned when a day already has the requested meal.
var ErrSlotExists = errors.New("slot_exists")

// RemoveSlot removes a planned meal (soft delete, so other devices learn of it).
func (s *Store) RemoveSlot(ctx context.Context, userID, id string) error {
	tag, err := s.Pool.Exec(ctx, `UPDATE menu_slot SET deleted_at = $2, updated_at = $2, updated_by = $3, version = version + 1
		WHERE id::text = $1 AND deleted_at IS NULL`, id, s.now(), userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// userPeople is the user's preferred number of people, used for the meals they plan.
func userPeople(ctx context.Context, tx pgx.Tx, userID string) (*int, error) {
	var n *int
	err := tx.QueryRow(ctx, `SELECT people FROM app_user WHERE id::text = $1`, userID).Scan(&n)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return n, err
}

// SetSlotPeople changes the number of people of a planned meal (1–20), for the
// whole family; the recipe quantities follow it.
func (s *Store) SetSlotPeople(ctx context.Context, userID, id string, people int) (MenuSlot, error) {
	if people < 1 || people > 20 {
		return MenuSlot{}, invalid("le nombre de personnes doit être compris entre 1 et 20")
	}
	var out MenuSlot
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := slotByID(ctx, tx, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE menu_slot SET people = $2, updated_by = $3, updated_at = $4, version = version + 1
			WHERE id::text = $1`, id, people, userID, s.now()); err != nil {
			return err
		}
		var err error
		out, err = oneSlot(ctx, tx, id)
		return err
	})
	return out, err
}

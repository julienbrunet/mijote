package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/julienbrunet/mijote/server/internal/menu"
)

// MenuProposal is a client-held draft. Base detects concurrent edits at confirmation.
type MenuProposal struct {
	From  string        `json:"from"`
	To    string        `json:"to"`
	Slots []MenuSlot    `json:"slots"`
	Base  []SlotVersion `json:"base"`
}

type SlotVersion struct {
	ID      string `json:"id"`
	Version int    `json:"version"`
}

var ErrMenuChanged = errors.New("menu_changed")

// PreviewMenu rolls back every planner write, including deletions and versions.
func (s *Store) PreviewMenu(ctx context.Context, userID string, req GenerateRequest) (MenuProposal, error) {
	slots, before, err := s.generateMenu(ctx, userID, req, true)
	if err != nil {
		return MenuProposal{}, err
	}
	end := req.Start.Format(time.DateOnly)
	for _, slot := range slots {
		if slot.Date > end {
			end = slot.Date
		}
	}
	p := MenuProposal{From: req.Start.Format(time.DateOnly), To: end, Slots: slots, Base: []SlotVersion{}}
	for _, slot := range before {
		if slot.Date <= end {
			p.Base = append(p.Base, SlotVersion{slot.ID, slot.Version})
		}
	}
	return p, nil
}

// ConfirmMenu is the only draft operation that writes planned meals. No partial
// confirmation is possible, and a stale draft cannot overwrite another member's edits.
func (s *Store) ConfirmMenu(ctx context.Context, userID string, p MenuProposal) ([]MenuSlot, error) {
	if p.Slots == nil || p.Base == nil {
		return nil, invalid("les repas et la référence du menu sont obligatoires")
	}
	from, err := time.Parse(time.DateOnly, p.From)
	if err != nil {
		return nil, invalid("date de début invalide")
	}
	to, err := time.Parse(time.DateOnly, p.To)
	if err != nil || to.Before(from) || to.Sub(from) >= maxGenerationDays*24*time.Hour {
		return nil, invalid("période invalide")
	}
	if len(p.Slots) > maxGenerationDays*2 || len(p.Base) > maxGenerationDays*2 {
		return nil, invalid("trop de repas")
	}
	seen := map[string]bool{}
	for _, slot := range p.Slots {
		date, err := time.Parse(time.DateOnly, slot.Date)
		key := slot.Date + "|" + slot.Meal
		if err != nil || date.Before(from) || date.After(to) || (slot.Meal != "lunch" && slot.Meal != "dinner") || seen[key] {
			return nil, invalid("créneau invalide ou en double")
		}
		seen[key] = true
		if slot.Recipe != nil && slot.Title != nil {
			return nil, invalid("un repas contient une recette ou un intitulé libre")
		}
		if slot.Title != nil {
			if _, err := checkTitle(*slot.Title); err != nil {
				return nil, err
			}
		}
		if slot.People != nil && (*slot.People < 1 || *slot.People > 20) {
			return nil, invalid("le nombre de personnes doit être compris entre 1 et 20")
		}
	}
	var out []MenuSlot
	err = s.tx(ctx, func(tx pgx.Tx) error {
		// Use the same lock order as generation. The short table lock also blocks
		// inserts into previously empty days while checking the complete period.
		if _, err := loadSettings(ctx, tx, true); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `LOCK TABLE menu_slot IN EXCLUSIVE MODE`); err != nil {
			return err
		}
		current, err := menuSlotsIn(ctx, tx, from, to)
		if err != nil {
			return err
		}
		versions := map[string]int{}
		for _, version := range p.Base {
			if _, exists := versions[version.ID]; exists {
				return invalid("référence de menu en double")
			}
			versions[version.ID] = version.Version
		}
		if len(versions) != len(current) {
			return ErrMenuChanged
		}
		keptRecipes := map[string]bool{}
		for _, slot := range current {
			if version, ok := versions[slot.ID]; !ok || version != slot.Version {
				return ErrMenuChanged
			}
			if slot.Recipe != nil {
				keptRecipes[slot.Recipe.ID] = true
			}
		}
		for _, slot := range p.Slots {
			if slot.Recipe != nil && !keptRecipes[slot.Recipe.ID] {
				if err := activeRecipe(ctx, tx, slot.Recipe.ID); err != nil {
					return err
				}
			}
		}
		now := s.now()
		if _, err := tx.Exec(ctx, `UPDATE menu_slot SET deleted_at = $3, updated_at = $3, updated_by = $4, version = version + 1
			WHERE deleted_at IS NULL AND date BETWEEN $1 AND $2`, from, to, now, userID); err != nil {
			return err
		}
		for _, slot := range p.Slots {
			var recipeID *string
			if slot.Recipe != nil {
				recipeID = &slot.Recipe.ID
			}
			var title *string
			if slot.Title != nil {
				trimmed, _ := checkTitle(*slot.Title)
				title = &trimmed
			}
			date, _ := time.Parse(time.DateOnly, slot.Date)
			if _, err := tx.Exec(ctx, `INSERT INTO menu_slot (date, meal, recipe_id, title, locked, people, updated_by, updated_at)
				VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
				ON CONFLICT (date, meal) DO UPDATE SET recipe_id = $3, title = $4, locked = $5, people = $6,
				deleted_at = NULL, updated_by = $7, updated_at = $8, version = menu_slot.version + 1`,
				date, slot.Meal, recipeID, title, slot.Locked, slot.People, userID, now); err != nil {
				return err
			}
		}
		out, err = menuSlotsIn(ctx, tx, from, to)
		return err
	})
	return out, err
}

// PreviewRecipe picks using the draft as context, without adding or swapping a saved slot.
func (s *Store) PreviewRecipe(ctx context.Context, date time.Time, meal, exclude string, draft []MenuSlot) (*MenuRecipe, error) {
	if (meal != "lunch" && meal != "dinner") || len(draft) > maxGenerationDays*2 {
		return nil, invalid("repas invalide")
	}
	var out *MenuRecipe
	err := s.tx(ctx, func(tx pgx.Tx) error {
		settings, err := loadSettings(ctx, tx, false)
		if err != nil {
			return err
		}
		recipes, err := planRecipes(ctx, tx)
		if err != nil {
			return err
		}
		if len(recipes) < settings.min || len(recipes) == 0 {
			return &ErrNotEnoughRecipes{Have: len(recipes), Need: max(settings.min, 1)}
		}
		last, err := lastUsed(ctx, tx, date)
		if err != nil {
			return err
		}
		var slots []menu.Slot
		for _, slot := range draft {
			day, err := time.Parse(time.DateOnly, slot.Date)
			if err != nil {
				return invalid("date invalide")
			}
			if slot.Date == date.Format(time.DateOnly) && slot.Meal == meal {
				continue
			}
			rid := ""
			if slot.Recipe != nil {
				rid = slot.Recipe.ID
			}
			slots = append(slots, menu.Slot{Date: day, Meal: slot.Meal, RecipeID: rid, Locked: slot.Locked})
		}
		slots = append(slots, menu.Slot{Date: date, Meal: meal})
		planner := menu.Planner{Recipes: recipes, LastUsed: last, Settings: settings.plan}
		id := planner.Pick(slots, len(slots)-1, exclude)
		if id == "" {
			return invalid("aucune autre recette ne convient pour ce repas")
		}
		var r MenuRecipe
		var image *string
		if err := tx.QueryRow(ctx, `SELECT id::text, title, dish_type::text, prep_minutes, total_minutes, image_path
			FROM recipe WHERE id::text = $1 AND archived_at IS NULL`, id).Scan(&r.ID, &r.Title, &r.DishType, &r.PrepMinutes, &r.TotalMinutes, &image); err != nil {
			return notFound(err)
		}
		if image != nil {
			r.Image = Recipe{ID: r.ID, ImagePath: image}.imageURL()
		}
		out = &r
		return nil
	})
	return out, err
}

package api

import (
	"net/http"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Menus (FR-GEN): reading the planned meals and generating a period.
// Any member can generate (requirements.md §3).

func parseDate(s string) (time.Time, bool) {
	d, err := time.Parse(time.DateOnly, s)
	return d, err == nil
}

// getMenu returns the meals between from and to (default: today and the
// family horizon) and the generation info.
func (a *API) getMenu(w http.ResponseWriter, r *http.Request) {
	info, err := a.Store.GenerationInfo(r.Context())
	if err != nil {
		fail(w, r, err)
		return
	}
	today, err := a.Store.Today(r.Context())
	if err != nil {
		fail(w, r, err)
		return
	}
	from, to := today, today.AddDate(0, 0, max(info.HorizonDays, 1)+30)
	q := r.URL.Query()
	if v := q.Get("from"); v != "" {
		d, ok := parseDate(v)
		if !ok {
			problem(w, http.StatusBadRequest, "invalid_date", "from must be YYYY-MM-DD")
			return
		}
		from = d
	}
	if v := q.Get("to"); v != "" {
		d, ok := parseDate(v)
		if !ok {
			problem(w, http.StatusBadRequest, "invalid_date", "to must be YYYY-MM-DD")
			return
		}
		to = d
	}
	if to.Before(from) || to.Sub(from) > 366*24*time.Hour {
		problem(w, http.StatusBadRequest, "invalid_date", "invalid date range (one year at most)")
		return
	}
	slots, err := a.Store.MenuSlots(r.Context(), from, to)
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"today": today.Format(time.DateOnly), "slots": slots, "generation": info})
}

// generateMenu plans {"meals": N, "start": "YYYY-MM-DD", "plan": "default|lunch_dinner|dinner"}.
func (a *API) generateMenu(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Start string `json:"start"`
		Meals int    `json:"meals"`
		Plan  string `json:"plan"`
	}
	if !body(w, r, &in) {
		return
	}
	req := store.GenerateRequest{Meals: in.Meals, Plan: in.Plan}
	if req.Plan == "" {
		req.Plan = store.PlanDefault
	}
	if in.Start == "" {
		today, err := a.Store.Today(r.Context())
		if err != nil {
			fail(w, r, err)
			return
		}
		req.Start = today
	} else {
		d, ok := parseDate(in.Start)
		if !ok {
			problem(w, http.StatusUnprocessableEntity, "validation_error", "date de début invalide")
			return
		}
		req.Start = d
	}
	slots, err := a.Store.GenerateMenu(r.Context(), current(r).user.ID, req)
	if e, ok := store.IsNotEnoughRecipes(err); ok {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "not_enough_recipes",
			"message": "not enough recipes to generate a menu", "have": e.Have, "need": e.Need})
		return
	}
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"slots": slots})
}

package api

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/julienbrunet/mijote/server/internal/store"
)

func (a *API) requireReadonly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !current(r).user.IsReadonly() {
			problem(w, http.StatusForbidden, "forbidden", "les demandes d’envies sont réservées aux comptes en lecture seule")
			return
		}
		next(w, r)
	}
}
func (a *API) recipeWishStatus(w http.ResponseWriter, r *http.Request) {
	status, err := a.Store.RecipeWishStatus(r.Context(), current(r).user.ID, r.PathValue("id"))
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, status)
}
func (a *API) requestRecipe(w http.ResponseWriter, r *http.Request) {
	status, created, err := a.Store.RequestRecipe(r.Context(), current(r).user.ID, r.PathValue("id"))
	switch {
	case errors.Is(err, store.ErrWishQuota):
		problem(w, http.StatusConflict, "wish_limit_reached", fmt.Sprintf("Vous avez atteint la limite de %d demandes cette semaine.", status.WeeklyLimit))
		return
	case errors.Is(err, store.ErrWishRole):
		problem(w, http.StatusForbidden, "forbidden", "les demandes d’envies sont réservées aux comptes en lecture seule")
		return
	case errors.Is(err, store.ErrNoWishRecipients):
		problem(w, http.StatusConflict, "no_menu_creators", "Aucun membre ne peut recevoir cette demande pour le moment.")
		return
	case err != nil:
		recipeError(w, r, err)
		return
	}
	code := http.StatusOK
	if created {
		code = http.StatusCreated
	}
	writeJSON(w, code, status)
}
func (a *API) getWishSettings(w http.ResponseWriter, r *http.Request) {
	limit, err := a.Store.WishLimit(r.Context())
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"weeklyLimit": limit})
}
func (a *API) setWishSettings(w http.ResponseWriter, r *http.Request) {
	var in struct {
		WeeklyLimit int `json:"weeklyLimit"`
	}
	if !body(w, r, &in) {
		return
	}
	if err := a.Store.SetWishLimit(r.Context(), in.WeeklyLimit); err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, in)
}
func (a *API) notifications(w http.ResponseWriter, r *http.Request) {
	offset, limit := 0, 50
	var err error
	if value := r.URL.Query().Get("offset"); value != "" {
		offset, err = strconv.Atoi(value)
	}
	if err == nil {
		if value := r.URL.Query().Get("limit"); value != "" {
			limit, err = strconv.Atoi(value)
		}
	}
	if err != nil || offset < 0 || limit < 1 || limit > 100 {
		problem(w, http.StatusBadRequest, "invalid_pagination", "pagination invalide")
		return
	}
	page, err := a.Store.RecipeNotifications(r.Context(), current(r).user.ID, offset, limit)
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}
func (a *API) readNotification(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.ReadRecipeNotification(r.Context(), current(r).user.ID, r.PathValue("id")); err != nil {
		recipeError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (a *API) deleteNotification(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.DeleteRecipeNotification(r.Context(), current(r).user.ID, r.PathValue("id")); err != nil {
		recipeError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

package api

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Recipes and tags (FR-REC-10..20). Any member can list, read, add and edit;
// archiving (the API's delete) is reserved to the admin (requirements.md §3).

// recipeError writes the HTTP answer matching a store error.
func recipeError(w http.ResponseWriter, r *http.Request, err error) {
	var ve *store.ValidationError
	switch {
	case errors.Is(err, store.ErrNotFound):
		problem(w, http.StatusNotFound, "not_found", "recipe not found")
	case errors.As(err, &ve):
		problem(w, http.StatusUnprocessableEntity, "validation_error", ve.Msg)
	default:
		fail(w, r, err)
	}
}

func (a *API) listRecipes(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	offset, _ := strconv.Atoi(q.Get("offset"))
	list, err := a.Store.ListRecipes(r.Context(), store.RecipeFilter{
		Q: q.Get("q"), Tag: q.Get("tag"), DishType: q.Get("dishType"), Limit: limit, Offset: offset})
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"recipes": list})
}

func (a *API) getRecipe(w http.ResponseWriter, r *http.Request) {
	rec, err := a.Store.Recipe(r.Context(), r.PathValue("id"))
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (a *API) createRecipe(w http.ResponseWriter, r *http.Request) {
	var in store.Recipe
	if !body(w, r, &in) {
		return
	}
	rec, err := a.Store.CreateRecipe(r.Context(), current(r).user.ID, in)
	if err != nil {
		recipeError(w, r, err)
		return
	}
	w.Header().Set("Location", "/api/v1/recipes/"+rec.ID)
	writeJSON(w, http.StatusCreated, rec)
}

func (a *API) updateRecipe(w http.ResponseWriter, r *http.Request) {
	var in store.Recipe
	if !body(w, r, &in) {
		return
	}
	rec, err := a.Store.UpdateRecipe(r.Context(), r.PathValue("id"), in)
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (a *API) archiveRecipe(w http.ResponseWriter, r *http.Request) {
	if !current(r).user.IsAdmin() {
		problem(w, http.StatusForbidden, "forbidden", "only the administrator can delete recipes")
		return
	}
	if _, err := a.Store.SetRecipeArchived(r.Context(), r.PathValue("id"), true); err != nil {
		recipeError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) listTags(w http.ResponseWriter, r *http.Request) {
	tags, err := a.Store.ListTags(r.Context())
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tags": tags})
}

package api

import (
	"errors"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/julienbrunet/mijote/server/internal/images"
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
	fav := q.Get("favorite")
	list, err := a.Store.ListRecipes(r.Context(), store.RecipeFilter{
		Q: q.Get("q"), Tag: q.Get("tag"), DishType: q.Get("dishType"), Limit: limit, Offset: offset,
		UserID: current(r).user.ID, FavoritesOnly: fav == "1" || fav == "true"})
	if err != nil {
		fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"recipes": list})
}

// withFavorite sets the personal favorite flag of the user asking.
func (a *API) withFavorite(r *http.Request, rec store.Recipe) (store.Recipe, error) {
	var err error
	rec.Favorite, err = a.Store.IsFavorite(r.Context(), current(r).user.ID, rec.ID)
	return rec, err
}

func (a *API) getRecipe(w http.ResponseWriter, r *http.Request) {
	rec, err := a.Store.Recipe(r.Context(), r.PathValue("id"))
	if err == nil {
		rec, err = a.withFavorite(r, rec)
	}
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// setFavorite adds the recipe to the favorites of the user asking (PUT) or
// removes it (DELETE). Favorites are personal, so read-only users have them too.
func (a *API) setFavorite(on bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := a.Store.SetFavorite(r.Context(), current(r).user.ID, r.PathValue("id"), on); err != nil {
			recipeError(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
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
	if err == nil {
		rec, err = a.withFavorite(r, rec)
	}
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

// ServeImage writes a thumbnail; the URL carries the content hash, so it can be cached for good.
func serveImage(w http.ResponseWriter, r *http.Request, st *store.Store, dir images.Dir, id string) {
	name, err := st.RecipeImagePath(r.Context(), id)
	if err != nil {
		recipeError(w, r, err)
		return
	}
	f, err := dir.Open(name)
	if errors.Is(err, os.ErrNotExist) {
		problem(w, http.StatusNotFound, "not_found", "image file missing")
		return
	} else if err != nil {
		fail(w, r, err)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	http.ServeContent(w, r, name, time.Time{}, f)
}

func (a *API) getImage(w http.ResponseWriter, r *http.Request) {
	serveImage(w, r, a.Store, a.Images, r.PathValue("id"))
}

// putImage takes the picture as the raw request body (JPEG, PNG or GIF, 10 MB at most).
func (a *API) putImage(w http.ResponseWriter, r *http.Request) {
	rec, err := a.Store.ChangeRecipeImage(r.Context(), a.Images, r.PathValue("id"),
		http.MaxBytesReader(w, r.Body, images.MaxUpload+1))
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (a *API) deleteImage(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RemoveRecipeImage(r.Context(), a.Images, r.PathValue("id")); err != nil {
		recipeError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

package admin

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Recipes and tags management (FR-ADM-04).

// blankIngredientRows is the number of empty ingredient lines offered below the existing ones.
const blankIngredientRows = 5

type recipesPage struct {
	Recipes  []store.Recipe
	Tags     []store.Tag
	Q, Tag   string
	DishType string
	Archived bool
	DishOpts []store.Option
}

func (a *Admin) recipesPage(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := store.RecipeFilter{Q: q.Get("q"), Tag: q.Get("tag"), DishType: q.Get("dishType"), Limit: store.MaxRecipePage}
	if q.Get("archived") != "" {
		f.Archived = "only"
	}
	list, err := a.Store.ListRecipes(r.Context(), f)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	tags, err := a.Store.ListTags(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "recipes", http.StatusOK, view{Title: "Recettes", Nav: "recipes",
		Data: recipesPage{Recipes: list, Tags: tags, Q: f.Q, Tag: f.Tag, DishType: f.DishType,
			Archived: f.Archived == "only", DishOpts: store.DishTypes}})
}

type recipeForm struct {
	R        store.Recipe
	New      bool
	Rows     []store.Ingredient // existing ingredients followed by blank rows
	Steps    string             // one step per line
	Tags     string             // comma-separated
	Quantity []string           // quantity as typed, per row
	DishOpts []store.Option
	Seasons  []store.Option
	Meals    []store.Option
	Units    []store.Option
	AllTags  []store.Tag
}

func (a *Admin) renderRecipeForm(w http.ResponseWriter, r *http.Request, status int, rec store.Recipe, isNew bool, qty []string, errMsg string) {
	tags, err := a.Store.ListTags(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	rows := append([]store.Ingredient{}, rec.Ingredients...)
	for len(qty) < len(rows) {
		qty = append(qty, "")
	}
	for i := 0; i < blankIngredientRows; i++ {
		rows = append(rows, store.Ingredient{})
		qty = append(qty, "")
	}
	for i, ing := range rows {
		if qty[i] == "" && ing.Quantity != nil {
			qty[i] = strconv.FormatFloat(*ing.Quantity, 'f', -1, 64)
		}
	}
	title := "Modifier la recette"
	if isNew {
		title = "Nouvelle recette"
	}
	a.render(w, r, "recipe", status, view{Title: title, Nav: "recipes", Error: errMsg,
		Data: recipeForm{R: rec, New: isNew, Rows: rows, Quantity: qty, Steps: strings.Join(rec.Steps, "\n"),
			Tags: strings.Join(rec.Tags, ", "), DishOpts: store.DishTypes, Seasons: store.Seasons,
			Meals: store.Meals, Units: store.Units, AllTags: tags}})
}

func (a *Admin) newRecipePage(w http.ResponseWriter, r *http.Request) {
	a.renderRecipeForm(w, r, http.StatusOK, store.Recipe{MealTypes: []string{"lunch", "dinner"}}, true, nil, "")
}

func (a *Admin) recipeNotFound(w http.ResponseWriter, r *http.Request) {
	a.render(w, r, "error", http.StatusNotFound, view{Title: "Recette introuvable", Error: "Cette recette n'existe pas."})
}

func (a *Admin) editRecipePage(w http.ResponseWriter, r *http.Request) {
	rec, err := a.Store.Recipe(r.Context(), r.PathValue("id"))
	if err == store.ErrNotFound {
		a.recipeNotFound(w, r)
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	a.renderRecipeForm(w, r, http.StatusOK, rec, false, nil, "")
}

func optString(s string) *string {
	if s = strings.TrimSpace(s); s == "" {
		return nil
	}
	return &s
}

// optInt parses an optional whole number; bad reports an unreadable value.
func optInt(s string) (v *int, bad bool) {
	if s = strings.TrimSpace(s); s == "" {
		return nil, false
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return nil, true
	}
	return &n, false
}

// parseRecipeForm reads the submitted form. Unreadable numbers are reported
// in msg; the rest of the form is still returned so it can be shown again.
func parseRecipeForm(r *http.Request) (rec store.Recipe, qty []string, msg string) {
	f := func(k string) string { return r.PostFormValue(k) }
	rec = store.Recipe{Title: f("title"), SourceURL: optString(f("source_url")),
		DishType: f("dish_type"), Seasons: r.PostForm["season"], MealTypes: r.PostForm["meal"],
		Notes: optString(f("notes")), Tags: strings.Split(f("tags"), ","), Steps: strings.Split(f("steps"), "\n")}
	for label, dst := range map[string]struct {
		key string
		p   **int
	}{
		"temps de préparation": {"prep_minutes", &rec.PrepMinutes}, "temps de cuisson": {"cook_minutes", &rec.CookMinutes},
		"temps total": {"total_minutes", &rec.TotalMinutes}, "nombre de personnes": {"servings", &rec.Servings},
	} {
		v, bad := optInt(f(dst.key))
		if bad {
			msg = label + " : saisissez un nombre entier"
		}
		*dst.p = v
	}
	names, units, notes, qtys := r.PostForm["ing_name"], r.PostForm["ing_unit"], r.PostForm["ing_note"], r.PostForm["ing_qty"]
	at := func(s []string, i int) string {
		if i < len(s) {
			return strings.TrimSpace(s[i])
		}
		return ""
	}
	for i := range names {
		name, q := at(names, i), at(qtys, i)
		if name == "" && q == "" {
			continue // blank row
		}
		ing := store.Ingredient{Name: name, Unit: optString(at(units, i)), Note: optString(at(notes, i))}
		if q != "" {
			v, err := strconv.ParseFloat(strings.Replace(q, ",", ".", 1), 64)
			if err != nil {
				msg = "quantité illisible pour « " + name + " » : " + q
			}
			ing.Quantity = &v
		}
		rec.Ingredients = append(rec.Ingredients, ing)
		qty = append(qty, q)
	}
	return rec, qty, msg
}

func (a *Admin) saveRecipe(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id") // empty when creating
	rec, qty, msg := parseRecipeForm(r)
	rec.ID = id
	var saved store.Recipe
	var err error
	if msg == "" {
		if id == "" {
			saved, err = a.Store.CreateRecipe(r.Context(), current(r).user.ID, rec)
		} else {
			saved, err = a.Store.UpdateRecipe(r.Context(), id, rec)
		}
	}
	switch {
	case err == store.ErrNotFound:
		a.recipeNotFound(w, r)
		return
	case err != nil:
		if m, ok := validationMsg(err); ok {
			msg = strings.ToUpper(m[:1]) + m[1:]
		} else {
			a.fail(w, r, err)
			return
		}
	}
	if msg != "" {
		a.renderRecipeForm(w, r, http.StatusUnprocessableEntity, rec, id == "", qty, msg)
		return
	}
	if id == "" {
		redirect(w, r, "/admin/recipes/"+saved.ID, "recipe_created")
		return
	}
	redirect(w, r, "/admin/recipes/"+id, "recipe_saved")
}

func (a *Admin) setRecipeArchived(archived bool, ok string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, err := a.Store.SetRecipeArchived(r.Context(), r.PathValue("id"), archived); err == store.ErrNotFound {
			a.recipeNotFound(w, r)
			return
		} else if err != nil {
			a.fail(w, r, err)
			return
		}
		redirect(w, r, "/admin/recipes", ok)
	}
}

// ---------- Tags ----------

type tagsPage struct{ Tags []store.Tag }

func (a *Admin) renderTags(w http.ResponseWriter, r *http.Request, status int, errMsg string) {
	tags, err := a.Store.ListTags(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "tags", status, view{Title: "Étiquettes", Nav: "tags", Error: errMsg, Data: tagsPage{Tags: tags}})
}

func (a *Admin) tagsPage(w http.ResponseWriter, r *http.Request) {
	a.renderTags(w, r, http.StatusOK, "")
}

func (a *Admin) tagAction(w http.ResponseWriter, r *http.Request, ok string, do func(id string) error) {
	err := do(r.PathValue("id"))
	if err == store.ErrNotFound {
		a.render(w, r, "error", http.StatusNotFound, view{Title: "Étiquette introuvable", Error: "Cette étiquette n'existe pas."})
		return
	} else if msg, isMsg := validationMsg(err); isMsg {
		a.renderTags(w, r, http.StatusUnprocessableEntity, strings.ToUpper(msg[:1])+msg[1:])
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	redirect(w, r, "/admin/tags", ok)
}

func (a *Admin) renameTag(w http.ResponseWriter, r *http.Request) {
	a.tagAction(w, r, "tag_renamed", func(id string) error {
		return a.Store.RenameTag(r.Context(), id, r.PostFormValue("name"))
	})
}

func (a *Admin) deleteTag(w http.ResponseWriter, r *http.Request) {
	a.tagAction(w, r, "tag_deleted", func(id string) error { return a.Store.DeleteTag(r.Context(), id) })
}

// ---------- Image ----------

func (a *Admin) recipeImage(w http.ResponseWriter, r *http.Request) {
	name, err := a.Store.RecipeImagePath(r.Context(), r.PathValue("id"))
	if err == store.ErrNotFound {
		http.NotFound(w, r)
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	f, err := a.Images.Open(name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	http.ServeContent(w, r, name, time.Time{}, f)
}

func (a *Admin) uploadRecipeImage(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	file, _, err := r.FormFile("image")
	if err != nil {
		a.imageResult(w, r, id, errors.New("choisissez un fichier image"))
		return
	}
	defer file.Close()
	_, err = a.Store.ChangeRecipeImage(r.Context(), a.Images, id, file)
	a.imageResult(w, r, id, err)
}

func (a *Admin) removeRecipeImage(w http.ResponseWriter, r *http.Request) {
	a.imageResult(w, r, r.PathValue("id"), a.Store.RemoveRecipeImage(r.Context(), a.Images, r.PathValue("id")))
}

func (a *Admin) imageResult(w http.ResponseWriter, r *http.Request, id string, err error) {
	if err == nil {
		redirect(w, r, "/admin/recipes/"+id, "recipe_saved")
		return
	}
	if err == store.ErrNotFound {
		a.recipeNotFound(w, r)
		return
	}
	msg := err.Error()
	if m, ok := validationMsg(err); !ok && err.Error() != "choisissez un fichier image" {
		_ = m
		a.fail(w, r, err)
		return
	}
	rec, gerr := a.Store.Recipe(r.Context(), id)
	if gerr != nil {
		a.fail(w, r, gerr)
		return
	}
	a.renderRecipeForm(w, r, http.StatusUnprocessableEntity, rec, false, nil, strings.ToUpper(msg[:1])+msg[1:])
}

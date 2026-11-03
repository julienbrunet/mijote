package admin

import (
	"errors"
	"net/http"
	"slices"
	"strconv"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Home page: the planned meals as cards, grouped by day (FR-ADM-08), which the
// admin can change or remove. Upcoming meals by default, past meals in a tab.

var (
	frWeekdays = [...]string{"Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi"}
	frMonths   = [...]string{"janvier", "février", "mars", "avril", "mai", "juin", "juillet",
		"août", "septembre", "octobre", "novembre", "décembre"}
)

// frDay formats a date as "Lundi 3 novembre 2026".
func frDay(d time.Time) string {
	return frWeekdays[d.Weekday()] + " " + strconv.Itoa(d.Day()) + " " + frMonths[d.Month()-1] + " " + strconv.Itoa(d.Year())
}

// dayTitle is the title of a day relative to today, as in the app.
func dayTitle(d, today time.Time) string {
	switch int(d.Sub(today).Hours() / 24) {
	case 0:
		return "Aujourd'hui"
	case 1:
		return "Demain"
	case -1:
		return "Hier"
	}
	return frWeekdays[d.Weekday()]
}

type mealDay struct {
	Label string // "Demain"
	Date  string // "4 novembre 2026"
	Today bool
	Meals []store.MealListItem
}

type homePage struct {
	Past    bool
	UserID  string
	Users   []store.User
	Days    []mealDay
	Count   int
	More    bool
	MaxRows int
}

func (a *Admin) home(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	today, err := a.Store.Today(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	f := store.MealListFilter{Today: today, Past: q.Get("period") == "past", UserID: q.Get("user")}
	list, more, err := a.Store.MealList(r.Context(), f)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	users, err := a.Store.ListUsers(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	p := homePage{Past: f.Past, UserID: f.UserID, Users: users, Count: len(list), More: more, MaxRows: store.MealListMaxRows}
	for _, m := range list {
		if n := len(p.Days); n == 0 || !p.Days[n-1].Meals[0].Date.Equal(m.Date) {
			full := frDay(m.Date)
			p.Days = append(p.Days, mealDay{Label: dayTitle(m.Date, today), Date: full[len(frWeekdays[m.Date.Weekday()])+1:],
				Today: m.Date.Equal(today)})
		}
		d := &p.Days[len(p.Days)-1]
		d.Meals = append(d.Meals, m)
	}
	title := "Prochains repas"
	if f.Past {
		title = "Repas passés"
	}
	a.render(w, r, "home", http.StatusOK, view{Title: title, Nav: "home", Data: p})
}

// oldMealsPage keeps the former meal list address working.
func (a *Admin) oldMealsPage(w http.ResponseWriter, r *http.Request) {
	target := "/admin/"
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	http.Redirect(w, r, target, http.StatusMovedPermanently)
}

type mealPage struct {
	M        store.MealListItem
	Label    string
	Suitable []store.Recipe // recipes for this meal (lunch / dinner)
	Others   []store.Recipe
}

func (a *Admin) renderMeal(w http.ResponseWriter, r *http.Request, status int, errMsg string) {
	m, err := a.Store.MealListItem(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		a.render(w, r, "error", http.StatusNotFound, view{Title: "Repas introuvable", Error: "Ce repas n'existe pas ou a été retiré."})
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	recipes, err := a.Store.ListRecipes(r.Context(), store.RecipeFilter{Limit: store.MaxRecipePage})
	if err != nil {
		a.fail(w, r, err)
		return
	}
	p := mealPage{M: m, Label: frDay(m.Date)}
	for _, rec := range recipes {
		if slices.Contains(rec.MealTypes, m.Meal) {
			p.Suitable = append(p.Suitable, rec)
		} else {
			p.Others = append(p.Others, rec)
		}
	}
	a.render(w, r, "meal", status, view{Title: "Modifier le repas", Nav: "home", Error: errMsg, Data: p})
}

func (a *Admin) mealPage(w http.ResponseWriter, r *http.Request) {
	a.renderMeal(w, r, http.StatusOK, "")
}

// mealResult redirects home after a change, or shows the error on the meal page.
func (a *Admin) mealResult(w http.ResponseWriter, r *http.Request, err error, ok string) {
	if msg, isValidation := validationMsg(err); isValidation {
		a.renderMeal(w, r, http.StatusUnprocessableEntity, msg)
		return
	}
	if e, short := store.IsNotEnoughRecipes(err); short {
		a.renderMeal(w, r, http.StatusUnprocessableEntity,
			"pas assez de recettes pour tirer au hasard ("+strconv.Itoa(e.Have)+" sur les "+strconv.Itoa(e.Need)+" nécessaires)")
		return
	}
	switch {
	case errors.Is(err, store.ErrNotFound):
		a.render(w, r, "error", http.StatusNotFound, view{Title: "Repas introuvable", Error: "Ce repas n'existe pas ou a été retiré."})
	case err != nil:
		a.fail(w, r, err)
	default:
		redirect(w, r, "/admin/", ok)
	}
}

func (a *Admin) setMealRecipe(w http.ResponseWriter, r *http.Request) {
	_, err := a.Store.UpdateSlot(r.Context(), current(r).user.ID, r.PathValue("id"), r.FormValue("recipe"), "")
	a.mealResult(w, r, err, "meal_saved")
}

func (a *Admin) setMealTitle(w http.ResponseWriter, r *http.Request) {
	title := r.FormValue("title")
	if title == "" {
		a.renderMeal(w, r, http.StatusUnprocessableEntity, "l'intitulé du repas est obligatoire")
		return
	}
	_, err := a.Store.UpdateSlot(r.Context(), current(r).user.ID, r.PathValue("id"), "", title)
	a.mealResult(w, r, err, "meal_saved")
}

func (a *Admin) swapMeal(w http.ResponseWriter, r *http.Request) {
	_, err := a.Store.SwapSlot(r.Context(), current(r).user.ID, r.PathValue("id"))
	a.mealResult(w, r, err, "meal_saved")
}

func (a *Admin) deleteMeal(w http.ResponseWriter, r *http.Request) {
	err := a.Store.RemoveSlot(r.Context(), current(r).user.ID, r.PathValue("id"))
	a.mealResult(w, r, err, "meal_deleted")
}

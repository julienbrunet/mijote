package admin

import (
	"net/http"
	"strconv"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Meal list (FR-ADM-08): every planned meal of the instance, grouped by day,
// with the account that planned it. Read-only: meals are planned in the app.

var (
	frWeekdays = [...]string{"Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi"}
	frMonths   = [...]string{"janvier", "février", "mars", "avril", "mai", "juin", "juillet",
		"août", "septembre", "octobre", "novembre", "décembre"}
)

// frDay formats a date as "Lundi 3 novembre 2026".
func frDay(d time.Time) string {
	return frWeekdays[d.Weekday()] + " " + strconv.Itoa(d.Day()) + " " + frMonths[d.Month()-1] + " " + strconv.Itoa(d.Year())
}

type mealDay struct {
	Label string
	Today bool
	Meals []store.MealListItem
}

type mealsPage struct {
	Past    bool
	UserID  string
	Users   []store.User
	Days    []mealDay
	Count   int
	More    bool
	MaxRows int
}

func (a *Admin) mealsPage(w http.ResponseWriter, r *http.Request) {
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
	p := mealsPage{Past: f.Past, UserID: f.UserID, Users: users, Count: len(list), More: more, MaxRows: store.MealListMaxRows}
	for _, m := range list {
		if n := len(p.Days); n == 0 || !p.Days[n-1].Meals[0].Date.Equal(m.Date) {
			p.Days = append(p.Days, mealDay{Label: frDay(m.Date), Today: m.Date.Equal(today)})
		}
		d := &p.Days[len(p.Days)-1]
		d.Meals = append(d.Meals, m)
	}
	a.render(w, r, "meals", http.StatusOK, view{Title: "Liste de repas", Nav: "meals", Data: p})
}

package menu

import (
	"fmt"
	"math/rand/v2"
	"testing"
	"time"
)

func day(s string) time.Time { d, _ := time.Parse("2006-01-02", s); return d }

func ptr(v int) *int { return &v }

func settings() Settings {
	return Settings{QuickMaxMinutes: 30, Weekend: []time.Weekday{time.Saturday, time.Sunday}, RecentUseDays: 21,
		Target: map[string]int{"meat": 30, "fish": 30, "vegetarian": 40}}
}

func week(start string, n int) []Slot {
	var out []Slot
	d := day(start)
	for len(out) < n {
		out = append(out, Slot{Date: d, Meal: "lunch"})
		if len(out) < n {
			out = append(out, Slot{Date: d, Meal: "dinner"})
		}
		d = d.AddDate(0, 0, 1)
	}
	return out
}

func TestFillNoRepeatAndMealFilter(t *testing.T) {
	var recipes []Recipe
	for i := range 20 {
		meals := []string{"lunch", "dinner"}
		if i%4 == 0 {
			meals = []string{"dinner"}
		}
		recipes = append(recipes, Recipe{ID: fmt.Sprint(i), DishType: []string{"meat", "fish", "vegetarian"}[i%3], Meals: meals})
	}
	p := Planner{Recipes: recipes, Settings: settings(), Rand: rand.New(rand.NewPCG(1, 2))}
	slots := week("2026-11-02", 14)
	p.Fill(slots)
	seen := map[string]bool{}
	for _, s := range slots {
		if s.RecipeID == "" || seen[s.RecipeID] {
			t.Fatalf("empty or repeated slot %+v", s)
		}
		seen[s.RecipeID] = true
		var id int
		fmt.Sscan(s.RecipeID, &id)
		if s.Meal == "lunch" && id%4 == 0 {
			t.Fatalf("dinner-only recipe at lunch: %+v", s)
		}
	}
}

func TestAvoidsRecentAndPrefersSeason(t *testing.T) {
	recipes := []Recipe{
		{ID: "recent", DishType: "meat", Meals: []string{"dinner"}},
		{ID: "summer", DishType: "meat", Meals: []string{"dinner"}, Seasons: []string{"summer"}},
		{ID: "autumn", DishType: "meat", Meals: []string{"dinner"}, Seasons: []string{"autumn"}},
	}
	start := day("2026-11-02")
	for i := range 50 {
		p := Planner{Recipes: recipes, Settings: settings(), Rand: rand.New(rand.NewPCG(uint64(i), 0)),
			LastUsed: map[string]time.Time{"recent": start.AddDate(0, 0, -3)}}
		slots := []Slot{{Date: start, Meal: "dinner"}}
		p.Fill(slots)
		if slots[0].RecipeID != "autumn" {
			t.Fatalf("run %d: got %s, want autumn", i, slots[0].RecipeID)
		}
	}
}

func TestShortagePicksOldest(t *testing.T) {
	start := day("2026-11-02")
	p := Planner{Settings: settings(),
		Recipes:  []Recipe{{ID: "a", DishType: "meat", Meals: []string{"dinner"}}, {ID: "b", DishType: "meat", Meals: []string{"dinner"}}},
		LastUsed: map[string]time.Time{"a": start.AddDate(0, 0, -2), "b": start.AddDate(0, 0, -20)}}
	slots := []Slot{{Date: start, Meal: "dinner"}}
	p.Fill(slots)
	if slots[0].RecipeID != "b" {
		t.Fatalf("got %s, want b", slots[0].RecipeID)
	}
	// No recipe for lunch at all: the slot stays empty.
	slots = []Slot{{Date: start, Meal: "lunch"}}
	p.Fill(slots)
	if slots[0].RecipeID != "" {
		t.Fatalf("lunch filled with %s", slots[0].RecipeID)
	}
}

func TestQuickOnWeekdaysAndLockedKept(t *testing.T) {
	recipes := []Recipe{
		{ID: "slow", DishType: "meat", Meals: []string{"dinner"}, Minutes: ptr(120)},
		{ID: "quick", DishType: "meat", Meals: []string{"dinner"}, Minutes: ptr(20)},
	}
	p := Planner{Recipes: recipes, Settings: settings()}
	slots := []Slot{{Date: day("2026-11-03"), Meal: "dinner"}}
	p.Fill(slots)
	if slots[0].RecipeID != "quick" {
		t.Fatalf("weekday got %s", slots[0].RecipeID)
	}
	slots = []Slot{{Date: day("2026-11-02"), Meal: "dinner", RecipeID: "quick", Locked: true}, {Date: day("2026-11-03"), Meal: "dinner"}}
	p.Fill(slots)
	if slots[0].RecipeID != "quick" || slots[1].RecipeID != "slow" {
		t.Fatalf("locked slot changed or repeat: %+v", slots)
	}
}

func TestBalance(t *testing.T) {
	var recipes []Recipe
	for i := range 30 {
		recipes = append(recipes, Recipe{ID: fmt.Sprint(i), DishType: []string{"meat", "fish", "vegetarian"}[i%3], Meals: []string{"lunch", "dinner"}})
	}
	p := Planner{Recipes: recipes, Settings: settings(), Rand: rand.New(rand.NewPCG(3, 4))}
	slots := week("2026-11-02", 10)
	p.Fill(slots)
	count := map[string]int{}
	for _, s := range slots {
		count[recipes[atoi(s.RecipeID)].DishType]++
	}
	if count["vegetarian"] != 4 || count["meat"] != 3 || count["fish"] != 3 {
		t.Fatalf("split %v, want 4/3/3", count)
	}
}

func atoi(s string) int { var i int; fmt.Sscan(s, &i); return i }

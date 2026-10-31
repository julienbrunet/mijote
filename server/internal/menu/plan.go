// Package menu holds the menu generation algorithm of requirements.md §10.
// It is pure (no database) so it can be tested and tuned in isolation.
package menu

import (
	"math/rand/v2"
	"slices"
	"time"
)

// Score weights (requirements.md §10: "exact weights are kept in code constants").
const (
	// recentPenalty is applied to recipes served within the recent-use window;
	// one point is given back per day since their last use, so that on shortage
	// the one served the longest ago wins.
	recentPenalty = 100
	// slowPenalty applies on weekdays to recipes above the "quick" threshold.
	slowPenalty = 20
	// offSeasonPenalty applies to recipes whose seasons exclude the slot's season.
	offSeasonPenalty = 15
	// balanceWeight multiplies the gap (in percentage points) between the target
	// share of a dish type and its share so far in the period.
	balanceWeight = 0.4
	// pickMargin: candidates scoring within this margin of the best one are drawn
	// at random, weighted by how close they are to the best.
	pickMargin = 12
)

// Recipe is what generation needs to know about a recipe.
type Recipe struct {
	ID       string
	DishType string   // meat, fish, vegetarian
	Minutes  *int     // total time, nil when unknown (treated as quick)
	Seasons  []string // empty = all year
	Meals    []string // lunch, dinner
}

// Slot is one meal of the period. RecipeID is empty for an empty slot.
type Slot struct {
	Date     time.Time // midnight, any location
	Meal     string    // lunch, dinner
	RecipeID string
	Locked   bool
}

// Settings are the family preferences used for scoring.
type Settings struct {
	QuickMaxMinutes int
	Weekend         []time.Weekday
	RecentUseDays   int
	Target          map[string]int // dish type → percentage
}

// Planner fills slots from a recipe base.
type Planner struct {
	Recipes  []Recipe
	LastUsed map[string]time.Time // recipe ID → last date served before the period
	Settings Settings
	Rand     *rand.Rand
}

// Season returns the (northern hemisphere, meteorological) season of a date.
func Season(d time.Time) string {
	switch d.Month() {
	case time.December, time.January, time.February:
		return "winter"
	case time.March, time.April, time.May:
		return "spring"
	case time.June, time.July, time.August:
		return "summer"
	default:
		return "autumn"
	}
}

// Fill assigns a recipe to every slot that is empty and not locked, in order.
// Filled and locked slots are kept and count for balance and recent use.
func (p *Planner) Fill(slots []Slot) {
	for i := range slots {
		if slots[i].RecipeID == "" && !slots[i].Locked {
			slots[i].RecipeID = p.Pick(slots, i, "")
		}
	}
}

// Pick chooses a recipe for slots[i] (the other slots form the period), never
// returning exclude. It returns "" when no recipe suits the meal at all.
func (p *Planner) Pick(slots []Slot, i int, exclude string) string {
	slot := slots[i]
	used := map[string]bool{}
	counts := map[string]int{}
	filled := 0
	byID := map[string]Recipe{}
	for _, r := range p.Recipes {
		byID[r.ID] = r
	}
	for j, s := range slots {
		if j == i || s.RecipeID == "" {
			continue
		}
		used[s.RecipeID] = true
		if r, ok := byID[s.RecipeID]; ok {
			counts[r.DishType]++
			filled++
		}
	}
	type cand struct {
		id    string
		score float64
	}
	var cands []cand
	for _, r := range p.Recipes {
		// Hard filters: suits the meal, not already in the period.
		if r.ID == exclude || used[r.ID] || !slices.Contains(r.Meals, slot.Meal) {
			continue
		}
		cands = append(cands, cand{r.ID, p.score(r, slot, counts, filled)})
	}
	if len(cands) == 0 {
		return ""
	}
	best := cands[0].score
	for _, c := range cands {
		best = max(best, c.score)
	}
	var pool []cand
	total := 0.0
	for _, c := range cands {
		if w := c.score - (best - pickMargin); w > 0 {
			pool = append(pool, cand{c.id, w})
			total += w
		}
	}
	x := p.rand() * total
	for _, c := range pool {
		if x -= c.score; x < 0 {
			return c.id
		}
	}
	return pool[len(pool)-1].id
}

func (p *Planner) rand() float64 {
	if p.Rand == nil {
		return rand.Float64()
	}
	return p.Rand.Float64()
}

func (p *Planner) score(r Recipe, slot Slot, counts map[string]int, filled int) float64 {
	s := 0.0
	if last, ok := p.LastUsed[r.ID]; ok {
		days := int(slot.Date.Sub(last).Hours() / 24)
		if days < p.Settings.RecentUseDays {
			s -= recentPenalty - float64(max(days, 0))
		}
	}
	if !slices.Contains(p.Settings.Weekend, slot.Date.Weekday()) &&
		r.Minutes != nil && *r.Minutes > p.Settings.QuickMaxMinutes {
		s -= slowPenalty
	}
	if len(r.Seasons) > 0 && !slices.Contains(r.Seasons, Season(slot.Date)) {
		s -= offSeasonPenalty
	}
	// Favor the dish type furthest below its target share (the slot being filled counts).
	share := float64(counts[r.DishType]) / float64(filled+1) * 100
	s += balanceWeight * (float64(p.Settings.Target[r.DishType]) - share)
	return s
}

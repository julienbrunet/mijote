package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestMenuProposalIsSavedOnlyOnConfirmation(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	handler, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(handler)
	defer srv.Close()
	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	app := newClient(t, srv.URL)
	_, token := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	access := token["accessToken"].(string)
	for i := 0; i < 12; i++ {
		res, _ := app.api("POST", "/api/v1/recipes", access, map[string]any{"title": "Recette " + string(rune('A'+i)), "dishType": "vegetarian", "mealTypes": []string{"lunch", "dinner"}})
		expect(t, res, http.StatusCreated, "create recipe")
	}
	res, _ := app.api("POST", "/api/v1/menu/generate", access, map[string]any{"start": "2026-11-03", "meals": 2, "plan": "lunch_dinner"})
	expect(t, res, http.StatusOK, "seed saved menu")
	res, manual := app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-03", "meal": "dinner", "title": "Pizza conservée", "replace": true})
	expect(t, res, http.StatusCreated, "seed locked manual meal")
	res, _ = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-06", "meal": "dinner", "title": "Outside period"})
	expect(t, res, http.StatusCreated, "seed outside period")
	// Include versions, soft deletions and audit stamps in the side-effect check.
	snapshot := func() string {
		t.Helper()
		var text string
		if err := pool.QueryRow(ctx, `SELECT coalesce(jsonb_agg(to_jsonb(m) ORDER BY id),'[]'::jsonb)::text FROM menu_slot m`).Scan(&text); err != nil {
			t.Fatal(err)
		}
		return text
	}
	unchanged := func(before string) {
		t.Helper()
		if snapshot() != before {
			t.Fatal("draft operation changed saved meals")
		}
	}
	preview := func() map[string]any {
		t.Helper()
		res, p := app.api("POST", "/api/v1/menu/preview", access, map[string]any{"start": "2026-11-03", "meals": 6, "plan": "lunch_dinner"})
		expect(t, res, http.StatusOK, "preview")
		if p["from"] != "2026-11-03" || p["to"] != "2026-11-05" {
			t.Fatalf("bad range %v", p)
		}
		return p
	}
	before := snapshot()
	p := preview()
	unchanged(before)
	_ = preview()
	unchanged(before) // Abandon and regenerate.
	slots := p["slots"].([]any)
	var first map[string]any
	for _, v := range slots {
		slot := v.(map[string]any)
		if slot["date"] == "2026-11-03" && slot["meal"] == "lunch" {
			first = slot
		}
		if slot["title"] == "Pizza conservée" && slot["locked"] != true {
			t.Fatal("lost manual lock")
		}
	}
	res, replacement := app.api("POST", "/api/v1/menu/preview/recipe", access, map[string]any{"date": first["date"], "meal": first["meal"], "slots": slots, "excludeId": first["recipe"].(map[string]any)["id"]})
	expect(t, res, http.StatusOK, "draft recipe pick")
	if replacement["recipe"].(map[string]any)["id"] == first["recipe"].(map[string]any)["id"] {
		t.Fatal("replacement did not change recipe")
	}
	unchanged(before)
	first["recipe"] = replacement["recipe"]
	first["people"] = 8
	first["locked"] = true
	// Replace a draft meal by free text and remove another one, still without writes.
	edited := []any{}
	for _, v := range slots {
		slot := v.(map[string]any)
		if slot["date"] == "2026-11-05" {
			if slot["meal"] == "lunch" {
				edited = append(edited, map[string]any{"id": "local-only", "date": "2026-11-05", "meal": "lunch", "title": "Repas libre modifié", "locked": true, "people": 3})
			}
			continue
		}
		edited = append(edited, slot)
	}
	p["slots"] = edited
	unchanged(before)
	badJSON, _ := json.Marshal(p)
	var bad map[string]any
	json.Unmarshal(badJSON, &bad)
	badSlots := bad["slots"].([]any)
	badSlots[len(badSlots)-1].(map[string]any)["date"] = "2026-12-01"
	res, _ = app.api("POST", "/api/v1/menu/confirm", access, bad)
	expect(t, res, http.StatusUnprocessableEntity, "invalid confirmation")
	unchanged(before)
	res, saved := app.api("POST", "/api/v1/menu/confirm", access, p)
	expect(t, res, http.StatusOK, "confirm draft")
	if snapshot() == before {
		t.Fatal("confirmation did not save")
	}
	got := saved["slots"].([]any)
	if len(got) != len(edited) {
		t.Fatalf("saved %d, expected %d", len(got), len(edited))
	}
	for _, v := range got {
		slot := v.(map[string]any)
		var want map[string]any
		for _, item := range edited {
			draft := item.(map[string]any)
			if slot["date"] == draft["date"] && slot["meal"] == draft["meal"] {
				want = draft
				break
			}
		}
		if want == nil {
			t.Fatalf("unexpected meal %v", slot)
		}
		for _, key := range []string{"title", "locked", "people"} {
			a, _ := json.Marshal(slot[key])
			b, _ := json.Marshal(want[key])
			if string(a) != string(b) {
				t.Fatalf("wrong %s: %s != %s", key, a, b)
			}
		}
		if want["recipe"] != nil && slot["recipe"].(map[string]any)["id"] != want["recipe"].(map[string]any)["id"] {
			t.Fatal("wrong saved recipe")
		}
	}
	var outside string
	if err := pool.QueryRow(ctx, `SELECT title FROM menu_slot WHERE date='2026-11-06' AND deleted_at IS NULL`).Scan(&outside); err != nil || outside != "Outside period" {
		t.Fatalf("outside period changed: %v", err)
	}
	confirmed := snapshot()
	res, _ = app.api("POST", "/api/v1/menu/confirm", access, p)
	expect(t, res, http.StatusConflict, "repeat confirmation")
	unchanged(confirmed)
	stale := preview()
	res, _ = app.api("PUT", "/api/v1/menu/slots/"+manual["id"].(string)+"/people", access, map[string]any{"people": 9})
	expect(t, res, http.StatusOK, "concurrent member edit")
	concurrent := snapshot()
	res, conflict := app.api("POST", "/api/v1/menu/confirm", access, stale)
	expect(t, res, http.StatusConflict, "stale proposal")
	if conflict["error"] != "menu_changed" {
		t.Fatalf("wrong conflict %v", conflict)
	}
	unchanged(concurrent)
	empty := preview()
	empty["slots"] = []any{}
	res, _ = app.api("POST", "/api/v1/menu/confirm", access, empty)
	expect(t, res, http.StatusOK, "confirm deliberate empty proposal")
	var remaining int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM menu_slot WHERE date BETWEEN '2026-11-03' AND '2026-11-05' AND deleted_at IS NULL`).Scan(&remaining); err != nil {
		t.Fatal(err)
	}
	if remaining != 0 {
		t.Fatal("removed draft meals remained saved")
	}
}

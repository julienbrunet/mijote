package server

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

func TestRecipeWishesNotificationsAndSharedWeeklyLimit(t *testing.T) {
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
	adminToken := token["accessToken"].(string)
	st := store.New(pool)
	admin := mustAdmin(t, st)
	account := func(username, role string) (string, string) {
		t.Helper()
		code, err := st.CreateInvitation(ctx, admin.ID, role, time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		res, tok := app.api("POST", "/api/v1/invitations/redeem", "", map[string]any{"code": code, "username": username, "displayName": username, "password": "test-password"})
		expect(t, res, http.StatusCreated, "create "+role)
		return tok["accessToken"].(string), tok["user"].(map[string]any)["id"].(string)
	}
	reader, readerID := account("lea", "readonly")
	otherReader, _ := account("max", "readonly")
	member, memberID := account("parent", "member")
	_, disabledID := account("absent", "member")
	if _, err := pool.Exec(ctx, `UPDATE app_user SET disabled_at=now() WHERE id::text=$1`, disabledID); err != nil {
		t.Fatal(err)
	}
	var recipes []string
	for i := 0; i < 10; i++ {
		res, recipe := app.api("POST", "/api/v1/recipes", adminToken, map[string]any{"title": "Recette " + string(rune('A'+i)), "dishType": "vegetarian"})
		expect(t, res, http.StatusCreated, "create recipe")
		recipes = append(recipes, recipe["id"].(string))
	}
	wish := func(access, id string, status int) map[string]any {
		t.Helper()
		res, out := app.api("POST", "/api/v1/recipes/"+id+"/wish", access, map[string]any{})
		expect(t, res, status, "request recipe")
		return out
	}
	inbox := func(access string) map[string]any {
		t.Helper()
		res, out := app.api("GET", "/api/v1/notifications", access, nil)
		expect(t, res, http.StatusOK, "inbox")
		return out
	}
	res, settings := app.api("GET", "/api/v1/settings/recipe-wishes", member, nil)
	expect(t, res, http.StatusOK, "member reads shared limit")
	if settings["weeklyLimit"] != float64(5) {
		t.Fatalf("default limit %v", settings)
	}
	for _, path := range []string{"/api/v1/notifications", "/api/v1/settings/recipe-wishes"} {
		res, _ = app.api("GET", path, reader, nil)
		expect(t, res, http.StatusForbidden, "readonly cannot access writer settings/inbox")
	}
	res, _ = app.api("PUT", "/api/v1/settings/recipe-wishes", reader, map[string]any{"weeklyLimit": 99})
	expect(t, res, http.StatusForbidden, "readonly cannot set shared limit")
	wish(member, recipes[0], http.StatusForbidden)
	first := wish(reader, recipes[0], http.StatusCreated)
	if first["remaining"] != float64(4) || first["requested"] != true {
		t.Fatalf("wrong status %v", first)
	}
	retry := wish(reader, recipes[0], http.StatusOK)
	if retry["used"] != float64(1) {
		t.Fatal("retry consumed quota")
	}
	adminInbox, memberInbox := inbox(adminToken), inbox(member)
	if adminInbox["unreadCount"] != float64(1) || memberInbox["unreadCount"] != float64(1) {
		t.Fatal("writers did not each receive a notification")
	}
	notification := adminInbox["items"].([]any)[0].(map[string]any)
	if notification["requesterName"] != "lea" || notification["recipeTitle"] != "Recette A" {
		t.Fatalf("wrong notification %v", notification)
	}
	id := notification["id"].(string)
	res, _ = app.api("DELETE", "/api/v1/notifications/"+id, member, nil)
	expect(t, res, http.StatusNotFound, "cannot delete another recipient's notification")
	res, _ = app.api("PUT", "/api/v1/notifications/"+id+"/read", member, map[string]any{})
	expect(t, res, http.StatusNotFound, "cannot read another recipient's notification")
	res, _ = app.api("PUT", "/api/v1/notifications/"+id+"/read", reader, map[string]any{})
	expect(t, res, http.StatusForbidden, "reader cannot mark writer notifications")
	res, _ = app.api("PUT", "/api/v1/notifications/"+id+"/read", adminToken, map[string]any{})
	expect(t, res, http.StatusNoContent, "mark read")
	res, _ = app.api("PUT", "/api/v1/notifications/"+id+"/read", adminToken, map[string]any{})
	expect(t, res, http.StatusNoContent, "read is idempotent")
	if inbox(adminToken)["unreadCount"] != float64(0) || inbox(member)["unreadCount"] != float64(1) {
		t.Fatal("read state leaked between recipients")
	}
	wish(otherReader, recipes[0], http.StatusCreated)
	for i := 1; i < 5; i++ {
		wish(reader, recipes[i], http.StatusCreated)
	}
	wish(reader, recipes[5], http.StatusConflict)
	res, page := app.api("GET", "/api/v1/notifications?limit=1&offset=0", adminToken, nil)
	expect(t, res, http.StatusOK, "notification pagination")
	if len(page["items"].([]any)) != 1 || page["hasMore"] != true || page["unreadCount"] != float64(5) {
		t.Fatalf("incorrect page %v", page)
	}
	_, nextPage := app.api("GET", "/api/v1/notifications?limit=1&offset=1", adminToken, nil)
	if page["items"].([]any)[0].(map[string]any)["id"] == nextPage["items"].([]any)[0].(map[string]any)["id"] {
		t.Fatal("pagination repeated a notification")
	}
	res, _ = app.api("GET", "/api/v1/notifications?limit=0", adminToken, nil)
	expect(t, res, http.StatusBadRequest, "invalid pagination")
	// Deleting notifications is personal and does not refund the requester's quota.
	for _, access := range []string{adminToken, member} {
		for _, item := range inbox(access)["items"].([]any) {
			n := item.(map[string]any)
			if n["requesterId"] == readerID {
				res, _ = app.api("DELETE", "/api/v1/notifications/"+n["id"].(string), access, nil)
				expect(t, res, http.StatusNoContent, "clear notification")
			}
		}
	}
	wish(reader, recipes[0], http.StatusConflict)
	res, status := app.api("GET", "/api/v1/recipes/"+recipes[0]+"/wish", reader, nil)
	expect(t, res, http.StatusOK, "wish quota status")
	if status["remaining"] != float64(0) || status["used"] != float64(5) || status["requested"] != false {
		t.Fatalf("deletion reset quota %v", status)
	}
	res, _ = app.api("PUT", "/api/v1/settings/recipe-wishes", member, map[string]any{"weeklyLimit": 6})
	expect(t, res, http.StatusOK, "member updates instance limit")
	_, settings = app.api("GET", "/api/v1/settings/recipe-wishes", adminToken, nil)
	if settings["weeklyLimit"] != float64(6) {
		t.Fatal("limit is not shared")
	}
	wish(reader, recipes[0], http.StatusCreated)
	for _, limit := range []int{0, 101} {
		res, _ = app.api("PUT", "/api/v1/settings/recipe-wishes", member, map[string]any{"weeklyLimit": limit})
		expect(t, res, http.StatusUnprocessableEntity, "invalid limit")
	}
	res, _ = app.api("PUT", "/api/v1/settings/recipe-wishes", member, map[string]any{"weeklyLimit": 2})
	expect(t, res, http.StatusOK, "lower quota")
	wish(reader, recipes[6], http.StatusConflict)
	res, _ = app.api("DELETE", "/api/v1/recipes/"+recipes[9], adminToken, nil)
	expect(t, res, http.StatusNoContent, "archive recipe")
	wish(reader, recipes[9], http.StatusNotFound)
	// Requests from the previous week remain history, but do not count this week.
	if _, err := pool.Exec(ctx, `UPDATE recipe_wish SET created_at=now()-interval '8 days' WHERE requester_id::text=$1`, readerID); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	results := make(chan error, 3)
	for _, recipe := range recipes[6:9] {
		wg.Add(1)
		go func(id string) { defer wg.Done(); _, _, err := st.RequestRecipe(ctx, readerID, id); results <- err }(recipe)
	}
	wg.Wait()
	close(results)
	accepted, rejected := 0, 0
	for err := range results {
		if err == nil {
			accepted++
		} else if errors.Is(err, store.ErrWishQuota) {
			rejected++
		} else {
			t.Fatal(err)
		}
	}
	if accepted != 2 || rejected != 1 {
		t.Fatalf("concurrent quota: accepted %d, rejected %d", accepted, rejected)
	}
	var count int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM recipe_wish_notification WHERE recipient_id::text=$1`, disabledID).Scan(&count); err != nil || count != 0 {
		t.Fatalf("disabled member received notifications: %v %d", err, count)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM menu_slot`).Scan(&count); err != nil || count != 0 {
		t.Fatal("wishes must not plan any meal")
	}
	// No receivers: no notification and no quota consumption.
	if _, err := pool.Exec(ctx, `UPDATE app_user SET disabled_at=now() WHERE id::text=$1 OR id::text=$2`, admin.ID, memberID); err != nil {
		t.Fatal(err)
	}
	otherUser, err := st.UserByUsername(ctx, "max")
	if err != nil {
		t.Fatal(err)
	}
	_, _, err = st.RequestRecipe(ctx, otherUser.ID, recipes[5])
	if !errors.Is(err, store.ErrNoWishRecipients) {
		t.Fatalf("unexpected no-recipient result: %v", err)
	}
}

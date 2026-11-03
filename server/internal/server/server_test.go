package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/migrate"
	"github.com/julienbrunet/mijote/server/internal/store"
)

// testDB creates a throwaway database next to MIJOTE_TEST_DATABASE_URL.
func testDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	base := os.Getenv("MIJOTE_TEST_DATABASE_URL")
	if base == "" {
		t.Skip("MIJOTE_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	admin, err := pgx.Connect(ctx, base)
	if err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	name := "mijote_test_" + hex.EncodeToString(b)
	if _, err := admin.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(base)
	u.Path = "/" + name
	pool, err := pgxpool.New(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		admin.Exec(ctx, "DROP DATABASE "+name) //nolint:errcheck
		admin.Close(ctx)
	})
	if err := migrate.Up(ctx, pool); err != nil {
		t.Fatal(err)
	}
	return pool
}

type client struct {
	t    *testing.T
	base string
	http *http.Client
}

func newClient(t *testing.T, base string) *client {
	jar, _ := cookiejar.New(nil)
	return &client{t, base, &http.Client{Jar: jar,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

func (c *client) do(method, path, contentType, body string, header ...string) (*http.Response, string) {
	c.t.Helper()
	req, _ := http.NewRequest(method, c.base+path, strings.NewReader(body))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	res, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res, string(b)
}

func (c *client) form(path string, v url.Values) (*http.Response, string) {
	return c.do("POST", path, "application/x-www-form-urlencoded", v.Encode())
}

func (c *client) json(path string, v any, header ...string) (*http.Response, map[string]any) {
	b, _ := json.Marshal(v)
	res, body := c.do("POST", path, "application/json", string(b), header...)
	var out map[string]any
	_ = json.Unmarshal([]byte(body), &out)
	return res, out
}

func expect(t *testing.T, res *http.Response, status int, what string) {
	t.Helper()
	if res.StatusCode != status {
		t.Fatalf("%s: status %d, want %d", what, res.StatusCode, status)
	}
}

var csrfRe = regexp.MustCompile(`name="csrf" value="([0-9a-f]+)"`)

func TestAuthFlow(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	// Setup wizard.
	web := newClient(t, srv.URL)
	res, _ := web.do("GET", "/admin/", "", "")
	if loc := res.Header.Get("Location"); loc != "/admin/setup" {
		t.Fatalf("fresh instance redirects to %q", loc)
	}
	res, _ = web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	expect(t, res, http.StatusSeeOther, "setup")
	res, _ = web.form("/admin/setup", url.Values{"username": {"evil"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	if _, err := st.UserByUsername(ctx, "evil"); err != store.ErrNotFound {
		t.Fatal("setup accepted a second admin")
	}

	// App: login, access, refresh rotation and reuse detection.
	app := newClient(t, srv.URL)
	res, _ = app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "nope"})
	expect(t, res, http.StatusUnauthorized, "wrong password")
	res, tok := app.json("/api/v1/auth/login", map[string]string{"username": "Julien", "password": "secret-pass", "deviceName": "Pixel"})
	expect(t, res, http.StatusOK, "login")
	access, refresh := tok["accessToken"].(string), tok["refreshToken"].(string)
	res, _ = app.do("GET", "/api/v1/me", "", "", "Authorization", "Bearer "+access)
	expect(t, res, http.StatusOK, "me")
	res, tok2 := app.json("/api/v1/auth/refresh", map[string]string{"refreshToken": refresh})
	expect(t, res, http.StatusOK, "refresh")
	access2 := tok2["accessToken"].(string)
	res, _ = app.json("/api/v1/auth/refresh", map[string]string{"refreshToken": refresh})
	expect(t, res, http.StatusUnauthorized, "refresh reuse")
	res, _ = app.do("GET", "/api/v1/me", "", "", "Authorization", "Bearer "+access2)
	expect(t, res, http.StatusUnauthorized, "access after reuse revoked the family")

	// Admin: password-only login, CSRF, two-factor setup.
	res, _ = web.form("/admin/login", url.Values{"password": {"wrong"}})
	expect(t, res, http.StatusUnauthorized, "admin wrong password")
	res, _ = web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	expect(t, res, http.StatusSeeOther, "admin login")
	res, page := web.do("GET", "/admin/account", "", "")
	expect(t, res, http.StatusOK, "account page")
	csrf := csrfRe.FindStringSubmatch(page)[1]
	res, _ = web.form("/admin/2fa/setup", url.Values{})
	expect(t, res, http.StatusForbidden, "POST without CSRF")
	res, _ = web.form("/admin/2fa/setup", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "2fa setup")
	u, _ := st.Admin(ctx)
	key, _, err := st.TOTPPending(ctx, u.ID, u.Username)
	if err != nil {
		t.Fatal(err)
	}
	secret, _ := auth.Base32.DecodeString(key)
	step := time.Now().Unix() / 30
	res, page = web.form("/admin/2fa/enable", url.Values{"csrf": {csrf}, "code": {auth.TOTPCode(secret, step)}})
	expect(t, res, http.StatusOK, "2fa enable")
	backup := regexp.MustCompile(`<code>([a-z2-7]{5}-[a-z2-7]{5})</code>`).FindStringSubmatch(page)
	if backup == nil {
		t.Fatal("backup codes not shown")
	}

	// Two-step login: the replayed code is refused, a backup code works once.
	other := newClient(t, srv.URL)
	res, _ = other.form("/admin/login", url.Values{"password": {"secret-pass"}})
	if loc := res.Header.Get("Location"); loc != "/admin/login/2fa" {
		t.Fatalf("login with 2fa redirects to %q", loc)
	}
	res, _ = other.do("GET", "/admin/account", "", "")
	expect(t, res, http.StatusSeeOther, "no session before the code")
	res, _ = other.form("/admin/login/2fa", url.Values{"code": {auth.TOTPCode(secret, step)}})
	expect(t, res, http.StatusUnauthorized, "replayed code")
	res, _ = other.form("/admin/login/2fa", url.Values{"code": {backup[1]}})
	expect(t, res, http.StatusSeeOther, "backup code")
	res, _ = other.do("GET", "/admin/sessions", "", "")
	expect(t, res, http.StatusOK, "sessions page")
	res, page = other.do("GET", "/admin/journal", "", "")
	if !strings.Contains(page, "Jeton réutilisé") || !strings.Contains(page, "Code de secours utilisé") {
		t.Fatal("journal is missing events")
	}

	// Rate limiting: 5 failures on an account block it.
	for i := 0; i < 5; i++ {
		app.json("/api/v1/auth/login", map[string]string{"username": "ghost", "password": "x"})
	}
	res, _ = app.json("/api/v1/auth/login", map[string]string{"username": "ghost", "password": "x"})
	expect(t, res, http.StatusTooManyRequests, "rate limit")
	if res.Header.Get("Retry-After") == "" {
		t.Fatal("missing Retry-After")
	}
}

func TestInvitationFlow(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	_, page := web.do("GET", "/admin/invitations", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]

	create := func(days string) string {
		res, page := web.form("/admin/invitations", url.Values{"csrf": {csrf}, "days": {days}})
		expect(t, res, http.StatusOK, "create invitation")
		m := regexp.MustCompile(`value="http[^"]*/invite/([a-z2-7]+)"`).FindStringSubmatch(page)
		if m == nil {
			t.Fatal("invitation link not shown")
		}
		return m[1]
	}
	code := create("7")

	// The shared link is a public page (no session), the same for any well-formed code.
	visitor := newClient(t, srv.URL)
	res, page := visitor.do("GET", "/invite/"+code, "", "")
	expect(t, res, http.StatusOK, "invitation page")
	if !strings.Contains(page, "mijote://invite?server=") || !strings.Contains(page, "code="+code) ||
		!strings.Contains(page, "Je n'ai pas encore l'application") {
		t.Fatal("invitation page is incomplete")
	}
	res, page2 := visitor.do("GET", "/invite/"+strings.Repeat("a", 20), "", "")
	expect(t, res, http.StatusOK, "page does not reveal whether a code is valid")
	if strings.Contains(page2, code) {
		t.Fatal("page shows another code")
	}
	res, _ = visitor.do("GET", "/invite/x", "", "")
	expect(t, res, http.StatusNotFound, "malformed code")

	app := newClient(t, srv.URL)
	redeem := func(code, user, pw string) (*http.Response, map[string]any) {
		return app.json("/api/v1/invitations/redeem", map[string]string{"code": code, "username": user,
			"displayName": "Léa", "password": pw, "deviceName": "Pixel"})
	}
	res, _ = redeem("wrongcode", "lea", "lea-password")
	expect(t, res, http.StatusNotFound, "unknown code")
	res, _ = redeem(code, "lea", "short")
	expect(t, res, http.StatusUnprocessableEntity, "weak password keeps the code usable")
	res, _ = redeem(code, "julien", "lea-password")
	expect(t, res, http.StatusUnprocessableEntity, "username already taken")
	res, tok := redeem(strings.ToUpper(code), "lea", "lea-password")
	expect(t, res, http.StatusCreated, "redeem")
	if tok["user"].(map[string]any)["role"] != "member" {
		t.Fatalf("invited account is not a member: %v", tok["user"])
	}
	res, _ = app.do("GET", "/api/v1/me", "", "", "Authorization", "Bearer "+tok["accessToken"].(string))
	expect(t, res, http.StatusOK, "me with the new account")
	res, _ = redeem(code, "lea2", "lea-password")
	expect(t, res, http.StatusNotFound, "code is single-use")

	// Revoked and expired codes are refused.
	revoked := create("1")
	_, page = web.do("GET", "/admin/invitations", "", "")
	id := regexp.MustCompile(`/admin/invitations/([0-9a-f-]+)/revoke`).FindStringSubmatch(page)[1]
	res, _ = web.form("/admin/invitations/"+id+"/revoke", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "revoke")
	res, _ = redeem(revoked, "bob", "bob-password")
	expect(t, res, http.StatusNotFound, "revoked code")
	expired := create("1")
	if _, err := pool.Exec(ctx, `UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE used_at IS NULL`); err != nil {
		t.Fatal(err)
	}
	res, _ = redeem(expired, "bob", "bob-password")
	expect(t, res, http.StatusNotFound, "expired code")

	// Accounts: a member can be disabled (sessions closed), reactivated, and get a new password.
	lea, _ := st.UserByUsername(ctx, "lea")
	res, _ = web.form("/admin/accounts/"+lea.ID+"/disable", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "disable")
	res, _ = app.do("GET", "/api/v1/me", "", "", "Authorization", "Bearer "+tok["accessToken"].(string))
	expect(t, res, http.StatusUnauthorized, "disabled account loses its session")
	res, _ = app.json("/api/v1/auth/login", map[string]string{"username": "lea", "password": "lea-password"})
	expect(t, res, http.StatusUnauthorized, "disabled account cannot sign in")
	web.form("/admin/accounts/"+lea.ID+"/enable", url.Values{"csrf": {csrf}})
	res, _ = web.form("/admin/accounts/"+lea.ID+"/password", url.Values{"csrf": {csrf}, "password": {"new-password"}})
	expect(t, res, http.StatusSeeOther, "reset password")
	res, _ = app.json("/api/v1/auth/login", map[string]string{"username": "lea", "password": "new-password"})
	expect(t, res, http.StatusOK, "sign in with the new password")
	admin, _ := st.Admin(ctx)
	res, _ = web.form("/admin/accounts/"+admin.ID+"/disable", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusUnprocessableEntity, "admin cannot be disabled")
	res, _ = web.form("/admin/accounts/"+lea.ID+"/disable", url.Values{})
	expect(t, res, http.StatusForbidden, "account action without CSRF")
}

func (c *client) api(method, path, token string, v any) (*http.Response, map[string]any) {
	c.t.Helper()
	var b string
	if v != nil {
		raw, _ := json.Marshal(v)
		b = string(raw)
	}
	res, body := c.do(method, path, "application/json", b, "Authorization", "Bearer "+token)
	var out map[string]any
	_ = json.Unmarshal([]byte(body), &out)
	return res, out
}

func TestRecipes(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	_, page := web.do("GET", "/admin/recipes", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]

	// A member and the admin sign in to the app.
	code, _ := st.CreateInvitation(ctx, mustAdmin(t, st).ID, "member", time.Hour)
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/invitations/redeem", map[string]string{"code": code, "username": "lea", "password": "lea-password"})
	member := tok["accessToken"].(string)
	_, tok = app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	adminTok := tok["accessToken"].(string)

	res, _ := app.api("GET", "/api/v1/recipes", "", nil)
	expect(t, res, http.StatusUnauthorized, "recipes need a token")

	// Create with validation.
	res, _ = app.api("POST", "/api/v1/recipes", member, map[string]any{"title": "Soupe"})
	expect(t, res, http.StatusUnprocessableEntity, "dish type is required")
	res, _ = app.api("POST", "/api/v1/recipes", member, map[string]any{"title": "Soupe", "dishType": "vegetarian",
		"ingredients": []map[string]any{{"name": "sel", "unit": "bogus"}}})
	expect(t, res, http.StatusUnprocessableEntity, "unknown unit")
	res, rec := app.api("POST", "/api/v1/recipes", member, map[string]any{
		"title": "Gratin de courgettes", "sourceUrl": "https://example.com/gratin", "dishType": "vegetarian",
		"prepMinutes": 15, "cookMinutes": 30, "seasons": []string{"summer", "spring", "summer"},
		"tags": []string{"Four", "été", "four"},
		"ingredients": []map[string]any{
			{"quantity": 0.5, "unit": "kg", "name": "courgettes", "note": "en rondelles"},
			{"name": "sel"},
		},
		"steps": []string{"Couper.", "  ", "Cuire."},
	})
	expect(t, res, http.StatusCreated, "create")
	id := rec["id"].(string)
	if rec["totalMinutes"] != float64(45) || rec["version"] != float64(1) {
		t.Fatalf("defaults: %v", rec)
	}
	if got := rec["seasons"].([]any); len(got) != 2 || got[0] != "spring" {
		t.Fatalf("seasons not normalized: %v", got)
	}
	if got := rec["tags"].([]any); len(got) != 2 {
		t.Fatalf("tags not deduplicated: %v", got)
	}
	ings := rec["ingredients"].([]any)
	if len(ings) != 2 || ings[0].(map[string]any)["rawText"] != "0.5 kg courgettes, en rondelles" {
		t.Fatalf("ingredients: %v", ings)
	}
	if len(rec["steps"].([]any)) != 2 {
		t.Fatalf("steps: %v", rec["steps"])
	}
	res, _ = app.api("POST", "/api/v1/recipes", member, map[string]any{"title": "Doublon", "dishType": "fish", "sourceUrl": "https://example.com/gratin"})
	expect(t, res, http.StatusUnprocessableEntity, "duplicate source url")

	// Read, search, update.
	_, list := app.api("GET", "/api/v1/recipes?q=COURG", member, nil)
	if len(list["recipes"].([]any)) != 1 {
		t.Fatalf("search by title: %v", list)
	}
	_, list = app.api("GET", "/api/v1/recipes?q=%25", member, nil)
	if len(list["recipes"].([]any)) != 0 {
		t.Fatal("wildcards must be escaped")
	}
	_, list = app.api("GET", "/api/v1/recipes?tag=ÉTÉ&dishType=vegetarian", member, nil)
	if len(list["recipes"].([]any)) != 1 {
		t.Fatalf("filter by tag: %v", list)
	}
	res, rec = app.api("PUT", "/api/v1/recipes/"+id, member, map[string]any{"title": "Gratin", "dishType": "vegetarian", "version": 1, "tags": []string{"four"}})
	expect(t, res, http.StatusOK, "update")
	if rec["version"] != float64(2) || len(rec["ingredients"].([]any)) != 0 {
		t.Fatalf("update: %v", rec)
	}
	res, rec = app.api("PUT", "/api/v1/recipes/"+id, member, map[string]any{"title": "Gratin 2", "dishType": "meat", "version": 1})
	expect(t, res, http.StatusOK, "last write wins even with an older version")
	if rec["version"] != float64(3) {
		t.Fatalf("version after second update: %v", rec["version"])
	}
	res, _ = app.api("GET", "/api/v1/recipes/00000000-0000-0000-0000-000000000000", member, nil)
	expect(t, res, http.StatusNotFound, "unknown recipe")
	res, _ = app.api("GET", "/api/v1/recipes/nope", member, nil)
	expect(t, res, http.StatusNotFound, "malformed id")

	// Only the admin deletes (archives).
	res, _ = app.api("DELETE", "/api/v1/recipes/"+id, member, nil)
	expect(t, res, http.StatusForbidden, "member cannot delete")
	res, _ = app.api("DELETE", "/api/v1/recipes/"+id, adminTok, nil)
	expect(t, res, http.StatusNoContent, "admin archives")
	_, list = app.api("GET", "/api/v1/recipes", member, nil)
	if len(list["recipes"].([]any)) != 0 {
		t.Fatal("archived recipe still listed")
	}

	// Web admin: archived list, restore, create and edit through the form.
	_, page = web.do("GET", "/admin/recipes?archived=1", "", "")
	if !strings.Contains(page, "Gratin 2") {
		t.Fatal("archived recipe missing from the admin")
	}
	res, _ = web.form("/admin/recipes/"+id+"/restore", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "restore")
	res, _ = web.form("/admin/recipes/"+id+"/restore", url.Values{})
	expect(t, res, http.StatusForbidden, "restore without CSRF")

	form := url.Values{"csrf": {csrf}, "title": {"Tarte"}, "dish_type": {"fish"}, "meal": {"dinner"}, "tags": {"four, rapide"},
		"ing_qty": {"1,5", "", ""}, "ing_unit": {"dl", "", ""}, "ing_name": {"lait", "sel", ""}, "ing_note": {"", "", ""},
		"steps": {"Mélanger\r\nCuire"}, "prep_minutes": {"10"}}
	res, _ = web.form("/admin/recipes", form)
	expect(t, res, http.StatusSeeOther, "create from the form")
	newURL := res.Header.Get("Location")
	_, page = web.do("GET", strings.SplitN(newURL, "?", 2)[0], "", "")
	if !strings.Contains(page, `value="1.5"`) || !strings.Contains(page, "Mélanger") {
		t.Fatal("form values not shown again")
	}
	form.Set("title", "")
	res, page = web.form("/admin/recipes", form)
	expect(t, res, http.StatusUnprocessableEntity, "missing title")
	if !strings.Contains(page, "Le titre est obligatoire") || !strings.Contains(page, `value="1,5"`) {
		t.Fatal("error not shown with the submitted values")
	}
	form.Set("title", "Tarte")
	form.Set("ing_qty", "abc")
	res, _ = web.form("/admin/recipes", form)
	expect(t, res, http.StatusUnprocessableEntity, "unreadable quantity")

	// Tags: rename, conflict, delete.
	tags, _ := st.ListTags(ctx)
	var four, quick store.Tag
	for _, tg := range tags {
		switch strings.ToLower(tg.Name) {
		case "four":
			four = tg
		case "rapide":
			quick = tg
		}
	}
	if four.Recipes != 1 {
		t.Fatalf("tag counts: %+v", tags)
	}
	res, _ = web.form("/admin/tags/"+quick.ID+"/rename", url.Values{"csrf": {csrf}, "name": {"FOUR"}})
	expect(t, res, http.StatusUnprocessableEntity, "tag name taken")
	res, _ = web.form("/admin/tags/"+quick.ID+"/rename", url.Values{"csrf": {csrf}, "name": {"express"}})
	expect(t, res, http.StatusSeeOther, "rename tag")
	res, _ = web.form("/admin/tags/"+four.ID+"/delete", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "delete tag")
	_, got := app.api("GET", "/api/v1/tags", member, nil)
	if n := len(got["tags"].([]any)); n != 2 { // "été" (now unused) and "express"
		t.Fatalf("tags after cleanup: %v", got)
	}
	res, _ = web.do("GET", "/admin/tags", "", "")
	expect(t, res, http.StatusOK, "tags page")
}

func TestRecipeImages(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	dir := t.TempDir()
	h, err := New(ctx, pool, nil, dir, "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)
	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	_, page := web.do("GET", "/admin/recipes", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	token := tok["accessToken"].(string)
	_, rec := app.api("POST", "/api/v1/recipes", token, map[string]any{"title": "Quiche", "dishType": "meat"})
	id := rec["id"].(string)
	if rec["image"] != nil {
		t.Fatal("new recipe has an image")
	}

	pic := func(w, h int) []byte {
		var b bytes.Buffer
		png.Encode(&b, image.NewRGBA(image.Rect(0, 0, w, h)))
		return b.Bytes()
	}
	put := func(body []byte) (*http.Response, map[string]any) {
		res, out := app.do("PUT", "/api/v1/recipes/"+id+"/image", "image/png", string(body), "Authorization", "Bearer "+token)
		var m map[string]any
		json.Unmarshal([]byte(out), &m)
		return res, m
	}
	res, _ := put([]byte("not an image"))
	expect(t, res, http.StatusUnprocessableEntity, "garbage upload")
	res, rec = put(pic(2000, 1000))
	expect(t, res, http.StatusOK, "upload")
	img, _ := rec["image"].(string)
	if !strings.HasPrefix(img, "/api/v1/recipes/"+id+"/image?v=") {
		t.Fatalf("image url: %v", rec["image"])
	}
	res, jpg := app.do("GET", img, "", "", "Authorization", "Bearer "+token)
	expect(t, res, http.StatusOK, "download")
	cfg, err := jpeg.DecodeConfig(strings.NewReader(jpg))
	if err != nil || cfg.Width != 800 || cfg.Height != 400 {
		t.Fatalf("thumbnail: %v %+v", err, cfg)
	}
	res, _ = app.do("GET", img, "", "")
	expect(t, res, http.StatusUnauthorized, "image needs a token")

	// Replacing deletes the previous file; the admin sees the picture too.
	if _, err := os.Stat(filepath.Join(dir, filepath.Base(mustPath(t, st, id)))); err != nil {
		t.Fatal(err)
	}
	first := mustPath(t, st, id)
	res, _ = put(pic(300, 600))
	expect(t, res, http.StatusOK, "replace")
	if _, err := os.Stat(filepath.Join(dir, first)); !os.IsNotExist(err) {
		t.Fatal("previous thumbnail not deleted")
	}
	res, _ = web.do("GET", "/admin/recipes/"+id+"/image", "", "")
	expect(t, res, http.StatusOK, "admin image")

	// Admin upload through the form, then removal.
	var mp bytes.Buffer
	w := multipart.NewWriter(&mp)
	w.WriteField("csrf", csrf)
	fw, _ := w.CreateFormFile("image", "p.png")
	fw.Write(pic(100, 100))
	w.Close()
	res, _ = web.do("POST", "/admin/recipes/"+id+"/image", w.FormDataContentType(), mp.String())
	expect(t, res, http.StatusSeeOther, "admin upload")
	res, _ = web.form("/admin/recipes/"+id+"/image/remove", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "admin remove")
	res, _ = app.do("GET", img, "", "", "Authorization", "Bearer "+token)
	expect(t, res, http.StatusNotFound, "no image after removal")
	if files, _ := os.ReadDir(dir); len(files) != 0 {
		t.Fatalf("files left in the image directory: %v", files)
	}
}

func mustPath(t *testing.T, st *store.Store, id string) string {
	p, err := st.RecipeImagePath(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func mustAdmin(t *testing.T, st *store.Store) store.User {
	u, err := st.Admin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return u
}

func TestAPITokens(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	_, page := web.do("GET", "/admin/tokens", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]

	create := func(name, scope string) string {
		t.Helper()
		res, page := web.form("/admin/tokens", url.Values{"csrf": {csrf}, "name": {name}, "scope": {scope}, "days": {"30"}})
		expect(t, res, http.StatusOK, "create token")
		m := regexp.MustCompile(`mjt_[A-Za-z0-9_-]+`).FindString(page)
		if m == "" {
			t.Fatal("token not shown")
		}
		return m
	}
	res, _ := web.form("/admin/tokens", url.Values{"csrf": {csrf}, "name": {" "}, "scope": {"read"}, "days": {"30"}})
	expect(t, res, http.StatusUnprocessableEntity, "name is required")

	reader, writer := create("lecture", "read"), create("écriture", "write")
	app := newClient(t, srv.URL)

	res, me := app.api("GET", "/api/v1/me", reader, nil)
	expect(t, res, http.StatusOK, "read token reads")
	if me["username"] != "julien" {
		t.Fatalf("token acts as %v", me["username"])
	}
	recipe := map[string]any{"title": "Soupe", "dishType": "vegetarian"}
	res, _ = app.api("POST", "/api/v1/recipes", reader, recipe)
	expect(t, res, http.StatusForbidden, "read token cannot write")
	res, rec := app.api("POST", "/api/v1/recipes", writer, recipe)
	expect(t, res, http.StatusCreated, "write token creates a recipe")
	res, _ = app.api("GET", "/api/v1/recipes/"+rec["id"].(string), reader, nil)
	expect(t, res, http.StatusOK, "read token reads the recipe")
	res, _ = app.api("POST", "/api/v1/auth/logout", writer, nil)
	expect(t, res, http.StatusBadRequest, "no device session to close")
	res, _ = app.api("GET", "/api/v1/me", "mjt_unknown", nil)
	expect(t, res, http.StatusUnauthorized, "unknown token")

	// The page never shows the secret again, and revoking takes effect at once.
	_, page = web.do("GET", "/admin/tokens", "", "")
	if strings.Contains(page, writer) {
		t.Fatal("clear token listed after creation")
	}
	id := regexp.MustCompile(`/admin/tokens/([0-9a-f-]+)/revoke`).FindStringSubmatch(page)[1]
	res, _ = web.form("/admin/tokens/"+id+"/revoke", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "revoke")
	// The newest token (writer) is the first listed, hence the one revoked.
	res, _ = app.api("GET", "/api/v1/me", writer, nil)
	expect(t, res, http.StatusUnauthorized, "revoked token")
	res, _ = app.api("GET", "/api/v1/me", reader, nil)
	expect(t, res, http.StatusOK, "other token still works")

	// Expired tokens and disabled accounts are refused.
	raw, err := st.CreateAPIToken(ctx, mustAdmin(t, st).ID, "court", "read", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE api_token SET expires_at = now() - interval '1 minute'`); err != nil {
		t.Fatal(err)
	}
	res, _ = app.api("GET", "/api/v1/me", raw, nil)
	expect(t, res, http.StatusUnauthorized, "expired token")
}

func TestMenuGeneration(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	access := tok["accessToken"].(string)

	// Preferences: number of people (default 1).
	_, me := app.api("GET", "/api/v1/me", access, nil)
	if me["preferences"].(map[string]any)["people"].(float64) != 1 {
		t.Fatalf("default people %v", me)
	}
	res, _ := app.api("PUT", "/api/v1/me/preferences", access, map[string]any{"people": 0})
	expect(t, res, http.StatusUnprocessableEntity, "people out of range")
	res, me = app.api("PUT", "/api/v1/me/preferences", access, map[string]any{"people": 4})
	expect(t, res, http.StatusOK, "set people")
	if me["preferences"].(map[string]any)["people"].(float64) != 4 {
		t.Fatalf("people not saved %v", me)
	}

	gen := map[string]any{"start": "2026-11-02", "meals": 5, "plan": "dinner"}
	res, out := app.api("POST", "/api/v1/menu/generate", access, gen)
	expect(t, res, http.StatusConflict, "empty recipe base")
	if out["error"] != "not_enough_recipes" || out["need"].(float64) != 10 {
		t.Fatalf("unexpected answer %v", out)
	}

	types := []string{"meat", "fish", "vegetarian"}
	for i := range 12 {
		res, _ = app.api("POST", "/api/v1/recipes", access, map[string]any{
			"title": "Recette " + string(rune('A'+i)), "dishType": types[i%3], "mealTypes": []string{"dinner"}})
		expect(t, res, http.StatusCreated, "create recipe")
	}

	res, out = app.api("POST", "/api/v1/menu/generate", access, map[string]any{"start": "2026-11-02", "meals": 99})
	expect(t, res, http.StatusUnprocessableEntity, "too many meals")

	res, out = app.api("POST", "/api/v1/menu/generate", access, gen)
	expect(t, res, http.StatusOK, "generate")
	slots := out["slots"].([]any)
	if len(slots) != 5 {
		t.Fatalf("got %d slots", len(slots))
	}
	seen := map[string]bool{}
	for i, s := range slots {
		m := s.(map[string]any)
		r := m["recipe"].(map[string]any)
		if m["meal"] != "dinner" || m["date"] != time.Date(2026, 11, 2+i, 0, 0, 0, 0, time.UTC).Format(time.DateOnly) || seen[r["id"].(string)] {
			t.Fatalf("bad slot %v", m)
		}
		seen[r["id"].(string)] = true
	}

	// Lock the first slot: regenerating the period keeps it.
	first := slots[0].(map[string]any)
	if _, err := pool.Exec(ctx, `UPDATE menu_slot SET locked = true WHERE id = $1`, first["id"]); err != nil {
		t.Fatal(err)
	}
	res, out = app.api("POST", "/api/v1/menu/generate", access, gen)
	expect(t, res, http.StatusOK, "regenerate")
	again := out["slots"].([]any)[0].(map[string]any)
	if again["recipe"].(map[string]any)["id"] != first["recipe"].(map[string]any)["id"] || again["locked"] != true {
		t.Fatalf("locked slot changed: %v", again)
	}

	// The next week avoids the recipes just served when it can (12 recipes, 5 recent).
	res, out = app.api("POST", "/api/v1/menu/generate", access, map[string]any{"start": "2026-11-07", "meals": 7, "plan": "dinner"})
	expect(t, res, http.StatusOK, "next week")
	_, cur := app.api("GET", "/api/v1/menu?from=2026-11-02&to=2026-11-06", access, nil)
	recent := map[string]bool{}
	for _, s := range cur["slots"].([]any) {
		recent[s.(map[string]any)["recipe"].(map[string]any)["id"].(string)] = true
	}
	for _, s := range out["slots"].([]any) {
		if recent[s.(map[string]any)["recipe"].(map[string]any)["id"].(string)] {
			t.Fatalf("recent recipe reused: %v", s)
		}
	}
	_, menu := app.api("GET", "/api/v1/menu?from=2026-11-01&to=2026-11-30", access, nil)
	if n := len(menu["slots"].([]any)); n != 12 {
		t.Fatalf("menu has %d slots, want 12", n)
	}
	if menu["generation"].(map[string]any)["recipeCount"].(float64) != 12 {
		t.Fatalf("generation info %v", menu["generation"])
	}

	// Swap: a new recipe, never the same; asking it for a locked meal releases it.
	_, now := app.api("GET", "/api/v1/menu?from=2026-11-03&to=2026-11-03", access, nil)
	free := now["slots"].([]any)[0].(map[string]any)
	res, out = app.api("POST", "/api/v1/menu/slots/"+free["id"].(string)+"/swap", access, nil)
	expect(t, res, http.StatusOK, "swap")
	if out["recipe"].(map[string]any)["id"] == free["recipe"].(map[string]any)["id"] {
		t.Fatalf("swap kept the recipe: %v", out)
	}
	res, out = app.api("POST", "/api/v1/menu/slots/"+first["id"].(string)+"/swap", access, nil)
	expect(t, res, http.StatusOK, "swap a locked meal")
	if out["locked"] != false || out["recipe"].(map[string]any)["id"] == first["recipe"].(map[string]any)["id"] {
		t.Fatalf("swap of a locked meal: %v", out)
	}
	res, _ = app.api("POST", "/api/v1/menu/slots/00000000-0000-0000-0000-000000000000/swap", access, nil)
	expect(t, res, http.StatusNotFound, "swap unknown")

	// Extra meal: a lunch needs a recipe suiting lunch (none here), a dinner is refused when planned.
	res, _ = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-02", "meal": "dinner"})
	expect(t, res, http.StatusConflict, "dinner already planned")
	res, out = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-02", "meal": "lunch"})
	expect(t, res, http.StatusCreated, "extra lunch")
	if out["meal"] != "lunch" || out["date"] != "2026-11-02" {
		t.Fatalf("bad extra meal %v", out)
	}
	res, _ = app.api("DELETE", "/api/v1/menu/slots/"+out["id"].(string), access, nil)
	expect(t, res, http.StatusNoContent, "remove extra meal")
	res, _ = app.api("DELETE", "/api/v1/menu/slots/"+out["id"].(string), access, nil)
	expect(t, res, http.StatusNotFound, "remove twice")

	// Manual meal: free text, kept by regeneration, editable, removable.
	res, out = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-04", "meal": "dinner", "title": "Commander une pizza"})
	expect(t, res, http.StatusConflict, "manual meal on a planned dinner")
	res, out = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-04", "meal": "dinner",
		"title": "  Commander une pizza ", "replace": true})
	expect(t, res, http.StatusCreated, "manual meal replacing a dinner")
	if out["title"] != "Commander une pizza" || out["recipe"] != nil || out["locked"] != true {
		t.Fatalf("bad manual meal %v", out)
	}
	manual := out["id"].(string)
	res, _ = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-05", "meal": "lunch", "title": "   "})
	expect(t, res, http.StatusUnprocessableEntity, "blank manual meal")
	res, out = app.api("PUT", "/api/v1/menu/slots/"+manual, access, map[string]any{"title": "Repas chez des amis"})
	expect(t, res, http.StatusOK, "rename manual meal")
	if out["title"] != "Repas chez des amis" {
		t.Fatalf("manual meal not renamed %v", out)
	}
	res, out = app.api("POST", "/api/v1/menu/generate", access, map[string]any{"start": "2026-11-02", "meals": 5, "plan": "dinner"})
	expect(t, res, http.StatusOK, "regenerate with a manual meal")
	for _, sl := range out["slots"].([]any) {
		m := sl.(map[string]any)
		if m["date"] == "2026-11-04" && (m["title"] != "Repas chez des amis" || m["recipe"] != nil) {
			t.Fatalf("manual meal lost on regeneration: %v", m)
		}
	}

	// A chosen recipe on a day (from the recipe screens), replacing the manual meal.
	_, list := app.api("GET", "/api/v1/recipes?q=Recette%20B", access, nil)
	chosen := list["recipes"].([]any)[0].(map[string]any)["id"].(string)
	res, out = app.api("PUT", "/api/v1/menu/slots/"+manual, access, map[string]any{"recipeId": chosen})
	expect(t, res, http.StatusOK, "manual meal to recipe")
	if out["title"] != nil || out["recipe"].(map[string]any)["id"] != chosen {
		t.Fatalf("manual meal not turned into a recipe %v", out)
	}
	res, out = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-20", "meal": "dinner", "recipeId": chosen})
	expect(t, res, http.StatusCreated, "plan a chosen recipe")
	if out["recipe"].(map[string]any)["id"] != chosen || out["locked"] != true {
		t.Fatalf("chosen recipe not planned %v", out)
	}
	res, _ = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-21", "meal": "dinner",
		"recipeId": chosen, "title": "Pizza"})
	expect(t, res, http.StatusUnprocessableEntity, "recipe and title")
	res, _ = app.api("POST", "/api/v1/menu/slots", access, map[string]any{"date": "2026-11-21", "meal": "dinner",
		"recipeId": "00000000-0000-0000-0000-000000000000"})
	expect(t, res, http.StatusUnprocessableEntity, "unknown recipe")

	// Covers of a meal: the planner's preference (4 here), changed for the whole family.
	_, day := app.api("GET", "/api/v1/menu?from=2026-11-03&to=2026-11-03", access, nil)
	meal := day["slots"].([]any)[0].(map[string]any)
	if meal["people"].(float64) != 4 {
		t.Fatalf("meal people %v, want the planner's 4", meal["people"])
	}
	res, out = app.api("PUT", "/api/v1/menu/slots/"+meal["id"].(string)+"/people", access, map[string]any{"people": 6})
	expect(t, res, http.StatusOK, "set meal people")
	if out["people"].(float64) != 6 {
		t.Fatalf("meal people not saved %v", out)
	}
	res, _ = app.api("PUT", "/api/v1/menu/slots/"+meal["id"].(string)+"/people", access, map[string]any{"people": 0})
	expect(t, res, http.StatusUnprocessableEntity, "meal people out of range")
	res, _ = app.api("PUT", "/api/v1/menu/slots/00000000-0000-0000-0000-000000000000/people", access, map[string]any{"people": 2})
	expect(t, res, http.StatusNotFound, "people of an unknown meal")

	// Lunch-only plan.
	res, out = app.api("POST", "/api/v1/menu/generate", access, map[string]any{"start": "2026-12-01", "meals": 2, "plan": "lunch"})
	expect(t, res, http.StatusOK, "lunch plan")
	for _, sl := range out["slots"].([]any) {
		if sl.(map[string]any)["meal"] != "lunch" {
			t.Fatalf("lunch plan gave %v", sl)
		}
	}
}

func TestReadonlyRoleAndSharedMenus(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})
	_, page := web.do("GET", "/admin/recipes", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]

	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	adminAccess := tok["accessToken"].(string)

	// Admin adds a recipe and generates a menu.
	res, rec := app.api("POST", "/api/v1/recipes", adminAccess, map[string]any{
		"title": "Soupe potiron", "dishType": "vegetarian", "mealTypes": []string{"dinner"},
	})
	expect(t, res, http.StatusCreated, "admin creates recipe")
	recID := rec["id"].(string)

	for i := range 10 {
		res, _ = app.api("POST", "/api/v1/recipes", adminAccess, map[string]any{
			"title": "Recette " + string(rune('A'+i)), "dishType": "vegetarian", "mealTypes": []string{"dinner"},
		})
		expect(t, res, http.StatusCreated, "admin creates recipe")
	}

	res, genOut := app.api("POST", "/api/v1/menu/generate", adminAccess, map[string]any{
		"start": "2026-11-02", "meals": 2, "plan": "dinner",
	})
	expect(t, res, http.StatusOK, "admin generates menu")
	slots := genOut["slots"].([]any)
	slotID := slots[0].(map[string]any)["id"].(string)

	// Create a readonly invitation.
	code, err := st.CreateInvitation(ctx, mustAdmin(t, st).ID, "readonly", time.Hour)
	if err != nil {
		t.Fatal(err)
	}

	// Readonly user redeems invitation and signs in.
	_, roTok := app.json("/api/v1/invitations/redeem", map[string]string{
		"code": code, "username": "enfant", "password": "enfant-password",
	})
	if roTok["user"].(map[string]any)["role"] != "readonly" {
		t.Fatalf("expected role readonly, got %v", roTok["user"])
	}
	roAccess := roTok["accessToken"].(string)

	// Readonly user can read the shared recipes and the shared menu!
	res, recList := app.api("GET", "/api/v1/recipes", roAccess, nil)
	expect(t, res, http.StatusOK, "readonly can list recipes")
	if len(recList["recipes"].([]any)) == 0 {
		t.Fatal("readonly should see recipes")
	}

	res, menuOut := app.api("GET", "/api/v1/menu", roAccess, nil)
	expect(t, res, http.StatusOK, "readonly can view shared menu")
	if len(menuOut["slots"].([]any)) == 0 {
		t.Fatal("readonly should see the shared menu slots")
	}

	// Readonly user cannot add or modify recipes.
	res, _ = app.api("POST", "/api/v1/recipes", roAccess, map[string]any{"title": "Interdit", "dishType": "meat"})
	expect(t, res, http.StatusForbidden, "readonly cannot create recipe")
	res, _ = app.api("PUT", "/api/v1/recipes/"+recID, roAccess, map[string]any{"title": "Interdit", "dishType": "meat"})
	expect(t, res, http.StatusForbidden, "readonly cannot edit recipe")
	res, _ = app.api("DELETE", "/api/v1/recipes/"+recID, roAccess, nil)
	expect(t, res, http.StatusForbidden, "readonly cannot delete recipe")

	// Readonly user cannot modify menu slots or generate menus.
	for _, path := range []string{"/api/v1/menu/preview", "/api/v1/menu/preview/recipe", "/api/v1/menu/confirm"} {
		res, _ = app.api("POST", path, roAccess, map[string]any{})
		expect(t, res, http.StatusForbidden, "readonly cannot propose or confirm menus")
	}
	res, _ = app.api("POST", "/api/v1/menu/generate", roAccess, map[string]any{"start": "2026-11-05", "meals": 2, "plan": "dinner"})
	expect(t, res, http.StatusForbidden, "readonly cannot generate menu")
	res, _ = app.api("POST", "/api/v1/menu/slots", roAccess, map[string]any{"date": "2026-11-10", "meal": "dinner"})
	expect(t, res, http.StatusForbidden, "readonly cannot add menu slot")
	res, _ = app.api("POST", "/api/v1/menu/slots/"+slotID+"/swap", roAccess, nil)
	expect(t, res, http.StatusForbidden, "readonly cannot swap slot")
	res, _ = app.api("DELETE", "/api/v1/menu/slots/"+slotID, roAccess, nil)
	expect(t, res, http.StatusForbidden, "readonly cannot delete slot")

	// Web admin: admin changes user role between readonly and member.
	roUser, err := st.UserByUsername(ctx, "enfant")
	if err != nil {
		t.Fatal(err)
	}
	web.form("/admin/accounts/"+roUser.ID+"/role", url.Values{"csrf": {csrf}, "role": {"member"}})
	updated, _ := st.GetUser(ctx, roUser.ID)
	if updated.Role != "member" {
		t.Fatalf("expected member, got %s", updated.Role)
	}

	// Now as member, can add a menu slot.
	res, _ = app.api("POST", "/api/v1/menu/slots", roAccess, map[string]any{"date": "2026-11-10", "meal": "dinner"})
	expect(t, res, http.StatusCreated, "member can add slot")

	// Switch back to readonly.
	web.form("/admin/accounts/"+roUser.ID+"/role", url.Values{"csrf": {csrf}, "role": {"readonly"}})
	updated, _ = st.GetUser(ctx, roUser.ID)
	if updated.Role != "readonly" {
		t.Fatalf("expected readonly, got %s", updated.Role)
	}
	res, _ = app.api("POST", "/api/v1/menu/slots", roAccess, map[string]any{"date": "2026-11-11", "meal": "dinner"})
	expect(t, res, http.StatusForbidden, "readonly again cannot add slot")
}

// TestAppAdmin covers the administration from the app: invitations and accounts.
func TestAppAdmin(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	access := tok["accessToken"].(string)

	// Create and share an invitation: the link, the app link and a QR code come back once.
	res, inv := app.api("POST", "/api/v1/admin/invitations", access, map[string]any{"role": "readonly", "days": 5})
	expect(t, res, http.StatusUnprocessableEntity, "invalid duration")
	res, inv = app.api("POST", "/api/v1/admin/invitations", access, map[string]any{"role": "readonly", "days": 7})
	expect(t, res, http.StatusCreated, "create invitation")
	code := inv["code"].(string)
	if inv["link"] != srv.URL+"/invite/"+code || !strings.HasPrefix(inv["appLink"].(string), "mijote://invite?server=") || inv["qr"] == nil {
		t.Fatalf("incomplete invitation %v", inv)
	}
	res, list := app.api("GET", "/api/v1/admin/invitations", access, nil)
	expect(t, res, http.StatusOK, "list invitations")
	invs := list["invitations"].([]any)
	if len(invs) != 1 || invs[0].(map[string]any)["status"] != "pending" || invs[0].(map[string]any)["role"] != "readonly" {
		t.Fatalf("bad invitation list %v", list)
	}

	// The invited person joins, then the admin manages the account from the app.
	res, joined := app.json("/api/v1/invitations/redeem", map[string]string{"code": code, "username": "lea", "password": "lea-password"})
	expect(t, res, http.StatusCreated, "redeem")
	memberAccess := joined["accessToken"].(string)
	res, _ = app.api("GET", "/api/v1/admin/users", memberAccess, nil)
	expect(t, res, http.StatusForbidden, "a non-admin cannot administer")
	res, users := app.api("GET", "/api/v1/admin/users", access, nil)
	expect(t, res, http.StatusOK, "list users")
	if len(users["users"].([]any)) != 2 {
		t.Fatalf("users %v", users)
	}
	lea, _ := st.UserByUsername(ctx, "lea")
	res, u := app.api("PUT", "/api/v1/admin/users/"+lea.ID, access, map[string]any{"role": "member"})
	expect(t, res, http.StatusOK, "change role")
	if u["role"] != "member" {
		t.Fatalf("role not changed %v", u)
	}
	res, u = app.api("PUT", "/api/v1/admin/users/"+lea.ID, access, map[string]any{"disabled": true})
	expect(t, res, http.StatusOK, "disable")
	if u["disabled"] != true {
		t.Fatalf("not disabled %v", u)
	}
	res, _ = app.api("GET", "/api/v1/me", memberAccess, nil)
	expect(t, res, http.StatusUnauthorized, "disabled account loses its session")
	app.api("PUT", "/api/v1/admin/users/"+lea.ID, access, map[string]any{"disabled": false})
	res, _ = app.api("PUT", "/api/v1/admin/users/"+lea.ID+"/password", access, map[string]any{"password": "new-password"})
	expect(t, res, http.StatusNoContent, "reset password")
	res, _ = app.json("/api/v1/auth/login", map[string]string{"username": "lea", "password": "new-password"})
	expect(t, res, http.StatusOK, "sign in with the new password")
	admin := mustAdmin(t, st)
	res, _ = app.api("PUT", "/api/v1/admin/users/"+admin.ID, access, map[string]any{"disabled": true})
	expect(t, res, http.StatusUnprocessableEntity, "admin cannot be disabled")
	res, _ = app.api("PUT", "/api/v1/admin/users/00000000-0000-0000-0000-000000000000", access, map[string]any{"disabled": true})
	expect(t, res, http.StatusNotFound, "unknown account")

	// Revoke a pending invitation.
	_, inv = app.api("POST", "/api/v1/admin/invitations", access, map[string]any{})
	_, list = app.api("GET", "/api/v1/admin/invitations", access, nil)
	id := list["invitations"].([]any)[0].(map[string]any)["id"].(string)
	res, _ = app.api("POST", "/api/v1/admin/invitations/"+id+"/revoke", access, nil)
	expect(t, res, http.StatusNoContent, "revoke")
	res, _ = app.json("/api/v1/invitations/redeem", map[string]string{"code": inv["code"].(string), "username": "bob", "password": "bob-password"})
	expect(t, res, http.StatusNotFound, "revoked code")

	// Personal API tokens cannot administer, even the admin's.
	tokenValue, err := st.CreateAPIToken(ctx, admin.ID, "script", store.ScopeWrite, 24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	res, _ = app.api("GET", "/api/v1/admin/users", tokenValue, nil)
	expect(t, res, http.StatusForbidden, "API token cannot administer")
}

func TestAdminMealList(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})

	// The invitation page shows the steps, then the link with a copy button and a single QR code.
	_, page := web.do("GET", "/admin/invitations", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]
	if !strings.Contains(page, "Créer une invitation") || strings.Contains(page, "data-copy") {
		t.Fatal("invitation page before creation")
	}
	res, page := web.form("/admin/invitations", url.Values{"csrf": {csrf}, "days": {"7"}, "role": {"member"}})
	expect(t, res, http.StatusOK, "create invitation")
	if strings.Count(page, "data:image/png;base64,") != 1 || !strings.Contains(page, `data-copy="#invite-link"`) ||
		!strings.Contains(page, `id="invite-link"`) {
		t.Fatal("new invitation must show one QR code and a copy button next to the link")
	}
	code := regexp.MustCompile(`value="http[^"]*/invite/([a-z2-7]+)"`).FindStringSubmatch(page)[1]

	// The copy button script is served from 'self' (the CSP forbids inline scripts).
	res, js := web.do("GET", "/admin/admin.js", "", "")
	expect(t, res, http.StatusOK, "admin script")
	if !strings.HasPrefix(res.Header.Get("Content-Type"), "text/javascript") || !strings.Contains(js, "data-copy") {
		t.Fatalf("admin script: %s", res.Header.Get("Content-Type"))
	}
	res, _ = web.do("GET", "/admin/admin.js", "", "", "If-None-Match", res.Header.Get("ETag"))
	expect(t, res, http.StatusNotModified, "admin script cached")

	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/invitations/redeem", map[string]string{"code": code, "username": "lea",
		"displayName": "Léa", "password": "lea-password"})
	lea := tok["accessToken"].(string)
	_, tok = app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	julien := tok["accessToken"].(string)
	for i := range 10 {
		res, _ := app.api("POST", "/api/v1/recipes", julien, map[string]any{
			"title": "Plat " + string(rune('A'+i)), "dishType": "vegetarian", "mealTypes": []string{"dinner"}})
		expect(t, res, http.StatusCreated, "create recipe")
	}

	// Julien generates three dinners from yesterday; Léa plans a manual lunch tomorrow.
	today, _ := st.Today(ctx)
	day := func(n int) string { return today.AddDate(0, 0, n).Format(time.DateOnly) }
	res, _ = app.api("POST", "/api/v1/menu/generate", julien, map[string]any{"start": day(-1), "meals": 3, "plan": "dinner"})
	expect(t, res, http.StatusOK, "generate")
	res, _ = app.api("POST", "/api/v1/menu/slots", lea, map[string]any{"date": day(1), "meal": "lunch", "title": "Pique-nique"})
	expect(t, res, http.StatusCreated, "manual meal")

	// The home page shows the upcoming meals as cards; the former address redirects there.
	res, _ = web.do("GET", "/admin/meals?period=past", "", "")
	if res.StatusCode != http.StatusMovedPermanently || res.Header.Get("Location") != "/admin/?period=past" {
		t.Fatalf("old meal list address: %d %s", res.StatusCode, res.Header.Get("Location"))
	}
	res, page = web.do("GET", "/admin/", "", "")
	expect(t, res, http.StatusOK, "meal list")
	if !strings.Contains(page, "Pique-nique") || !strings.Contains(page, "Léa") || !strings.Contains(page, "Aujourd&#39;hui") ||
		strings.Count(page, `href="/admin/recipes/`) != 2 || !strings.Contains(page, `class="on" aria-current="page">Accueil</a>`) {
		t.Fatalf("upcoming meals incomplete:\n%s", page)
	}
	leaUser, _ := st.UserByUsername(ctx, "lea")
	_, page = web.do("GET", "/admin/?user="+leaUser.ID, "", "")
	if !strings.Contains(page, "Pique-nique") || strings.Contains(page, `href="/admin/recipes/`) {
		t.Fatal("filter by planner")
	}
	_, page = web.do("GET", "/admin/?period=past", "", "")
	if strings.Count(page, `href="/admin/recipes/`) != 1 || strings.Contains(page, "Pique-nique") || strings.Contains(page, "/admin/meals/") {
		t.Fatal("past meals")
	}
	res, _ = web.do("GET", "/admin/?user=not-a-uuid", "", "")
	expect(t, res, http.StatusOK, "unknown planner")
	res, _ = newClient(t, srv.URL).do("GET", "/admin/meals/x", "", "")
	expect(t, res, http.StatusSeeOther, "meal page needs a session")
}

func TestAdminHomeSystemAndNavigation(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "1.2.3")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	// The version is not disclosed before signing in; the footer links to the repository.
	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	_, page := web.do("GET", "/admin/login", "", "")
	if strings.Contains(page, "1.2.3") || !strings.Contains(page, "https://github.com/julienbrunet/mijote") ||
		!strings.Contains(page, "/admin/wordmark.svg?v=") {
		t.Fatal("login page footer and brand")
	}
	web.form("/admin/login", url.Values{"password": {"secret-pass"}})

	// Static files of the admin.
	for name, ctype := range map[string]string{"admin.css": "text/css", "logo.svg": "image/svg+xml", "wordmark.svg": "image/svg+xml"} {
		res, _ := web.do("GET", "/admin/"+name, "", "")
		expect(t, res, http.StatusOK, name)
		if !strings.HasPrefix(res.Header.Get("Content-Type"), ctype) || res.Header.Get("ETag") == "" {
			t.Fatalf("%s: %s", name, res.Header.Get("Content-Type"))
		}
	}

	// Every page of the navigation renders, with its section highlighted and its tabs.
	for path, want := range map[string][]string{
		"/admin/":              {`aria-current="page">Accueil</a>`, "Prochains repas"},
		"/admin/recipes":       {`aria-current="page">Recettes</a>`, `href="/admin/recipes/stats"`},
		"/admin/tags":          {`aria-current="page">Étiquettes</a>`},
		"/admin/recipes/stats": {`aria-current="page">Statistiques</a>`, "Équilibre des types de plat"},
		"/admin/accounts":      {`aria-current="page">Utilisateurs</a>`, `href="/admin/journal"`},
		"/admin/invitations":   {`aria-current="page">Invitations</a>`},
		"/admin/sessions":      {`aria-current="page">Sessions</a>`},
		"/admin/journal":       {`aria-current="page">Journal des connexions</a>`},
		"/admin/system":        {`aria-current="page">Système</a>`, "1.2.3", "Base de données", "Dossier des images"},
		"/admin/account":       {`aria-current="page">Profil admin</a>`, `href="/admin/tokens"`},
		"/admin/tokens":        {`aria-current="page">Jetons d&#39;API</a>`},
	} {
		res, page := web.do("GET", path, "", "")
		expect(t, res, http.StatusOK, path)
		for _, w := range append(want, "serveur 1.2.3", "Code source sur GitHub") {
			if !strings.Contains(page, w) {
				t.Fatalf("%s: missing %q", path, w)
			}
		}
	}

	// Recipes and planned meals from the app.
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	julien := tok["accessToken"].(string)
	var ids []string
	for i := range 6 {
		res, rec := app.api("POST", "/api/v1/recipes", julien, map[string]any{
			"title": "Plat " + string(rune('A'+i)), "dishType": []string{"meat", "fish", "vegetarian"}[i%3], "mealTypes": []string{"dinner"}})
		expect(t, res, http.StatusCreated, "create recipe")
		ids = append(ids, rec["id"].(string))
	}
	today, _ := st.Today(ctx)
	day := func(n int) string { return today.AddDate(0, 0, n).Format(time.DateOnly) }
	res, _ := app.api("POST", "/api/v1/menu/slots", julien, map[string]any{"date": day(-2), "meal": "dinner", "recipeId": ids[0]})
	expect(t, res, http.StatusCreated, "past meal")
	res, slot := app.api("POST", "/api/v1/menu/slots", julien, map[string]any{"date": day(1), "meal": "dinner", "recipeId": ids[1]})
	expect(t, res, http.StatusCreated, "upcoming meal")
	slotID := slot["id"].(string)

	// The home page shows the meal as a card with its actions.
	_, page = web.do("GET", "/admin/", "", "")
	csrf := csrfRe.FindStringSubmatch(page)[1]
	if !strings.Contains(page, "Demain") || !strings.Contains(page, "Plat B") || !strings.Contains(page, `href="/admin/meals/`+slotID+`"`) ||
		!strings.Contains(page, `action="/admin/meals/`+slotID+`/delete"`) {
		t.Fatalf("meal card:\n%s", page)
	}

	// Change the recipe, turn it into a manual meal, draw another recipe, then remove it.
	res, page = web.do("GET", "/admin/meals/"+slotID, "", "")
	expect(t, res, http.StatusOK, "meal page")
	if !strings.Contains(page, "Pour le soir") || !strings.Contains(page, "Plat F") {
		t.Fatal("meal page lists the recipes")
	}
	res, _ = web.form("/admin/meals/"+slotID+"/recipe", url.Values{"csrf": {csrf}, "recipe": {ids[2]}})
	expect(t, res, http.StatusSeeOther, "set recipe")
	slots, _ := st.MenuSlots(ctx, today.AddDate(0, 0, 1), today.AddDate(0, 0, 1))
	if len(slots) != 1 || slots[0].Recipe == nil || slots[0].Recipe.ID != ids[2] || !slots[0].Locked {
		t.Fatalf("recipe not set: %+v", slots)
	}
	res, _ = web.form("/admin/meals/"+slotID+"/recipe", url.Values{"csrf": {csrf}, "recipe": {"not-a-recipe"}})
	expect(t, res, http.StatusUnprocessableEntity, "unknown recipe")
	res, _ = web.form("/admin/meals/"+slotID+"/title", url.Values{"csrf": {csrf}, "title": {"Pizza"}})
	expect(t, res, http.StatusSeeOther, "manual meal")
	res, _ = web.form("/admin/meals/"+slotID+"/title", url.Values{"csrf": {csrf}, "title": {"  "}})
	expect(t, res, http.StatusUnprocessableEntity, "blank title")
	_, page = web.do("GET", "/admin/?ok=meal_saved", "", "")
	if !strings.Contains(page, "Pizza") || !strings.Contains(page, "repas libre") || !strings.Contains(page, "Repas modifié.") {
		t.Fatal("manual meal on the home page")
	}
	res, page = web.form("/admin/meals/"+slotID+"/swap", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusUnprocessableEntity, "swap below the minimum recipe count")
	if !strings.Contains(page, "6 sur les 10") {
		t.Fatal("swap error message")
	}
	for i := range 4 {
		res, _ := app.api("POST", "/api/v1/recipes", julien, map[string]any{
			"title": "Extra " + string(rune('A'+i)), "dishType": "vegetarian", "mealTypes": []string{"lunch"}})
		expect(t, res, http.StatusCreated, "create recipe")
	}
	res, _ = web.form("/admin/meals/"+slotID+"/swap", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "swap")
	slots, _ = st.MenuSlots(ctx, today.AddDate(0, 0, 1), today.AddDate(0, 0, 1))
	if len(slots) != 1 || slots[0].Recipe == nil || slots[0].Title != nil {
		t.Fatalf("swap: %+v", slots)
	}
	res, _ = web.form("/admin/meals/"+slotID+"/delete", url.Values{})
	expect(t, res, http.StatusForbidden, "delete needs the CSRF token")
	res, _ = web.form("/admin/meals/"+slotID+"/delete", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusSeeOther, "delete")
	res, _ = web.form("/admin/meals/"+slotID+"/delete", url.Values{"csrf": {csrf}})
	expect(t, res, http.StatusNotFound, "already deleted")
	res, _ = web.do("GET", "/admin/meals/"+slotID, "", "")
	expect(t, res, http.StatusNotFound, "deleted meal page")

	// Usage statistics: the past meal counts, the others were never served.
	_, page = web.do("GET", "/admin/recipes/stats?period=30", "", "")
	if !strings.Contains(page, "9 recette(s) active(s) n'ont encore jamais été servies") || !strings.Contains(page, "1 repas · 100 %") {
		t.Fatalf("stats:\n%s", page)
	}

	// The system page counts the content and warns about the admin's missing 2FA.
	_, page = web.do("GET", "/admin/system", "", "")
	for _, w := range []string{"<b>10</b><span>Recettes</span>", "<b>1</b><span>Repas passés</span>", "double authentification",
		"Instance installée le", "PostgreSQL", "Aucun repas planifié à venir."} {
		if !strings.Contains(page, w) {
			t.Fatalf("system page: missing %q", w)
		}
	}
	versions, _ := st.ServerVersions(ctx, 10)
	if len(versions) != 1 || versions[0].Version != "1.2.3" {
		t.Fatalf("server versions: %+v", versions)
	}
}

// TestFavorites checks that favorites are personal and filter the recipe list.
func TestFavorites(t *testing.T) {
	pool := testDB(t)
	ctx := context.Background()
	h, err := New(ctx, pool, nil, t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(h)
	defer srv.Close()
	st := store.New(pool)

	web := newClient(t, srv.URL)
	web.form("/admin/setup", url.Values{"username": {"julien"}, "password": {"secret-pass"}, "confirm": {"secret-pass"}})
	app := newClient(t, srv.URL)
	_, tok := app.json("/api/v1/auth/login", map[string]string{"username": "julien", "password": "secret-pass"})
	admin := tok["accessToken"].(string)
	code, err := st.CreateInvitation(ctx, mustAdmin(t, st).ID, "readonly", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	_, ro := app.json("/api/v1/invitations/redeem", map[string]string{"code": code, "username": "enfant", "password": "enfant-password"})
	child := ro["accessToken"].(string)

	var ids []string
	for _, title := range []string{"Soupe", "Gratin", "Quiche"} {
		_, rec := app.api("POST", "/api/v1/recipes", admin, map[string]any{"title": title, "dishType": "vegetarian"})
		ids = append(ids, rec["id"].(string))
	}
	res, _ := app.api("PUT", "/api/v1/recipes/"+ids[0]+"/favorite", admin, nil)
	expect(t, res, http.StatusNoContent, "add favorite")
	res, _ = app.api("PUT", "/api/v1/recipes/"+ids[0]+"/favorite", admin, nil)
	expect(t, res, http.StatusNoContent, "add favorite again")
	res, _ = app.api("PUT", "/api/v1/recipes/"+ids[1]+"/favorite", child, nil)
	expect(t, res, http.StatusNoContent, "read-only user adds a favorite")
	res, _ = app.api("PUT", "/api/v1/recipes/00000000-0000-0000-0000-000000000000/favorite", admin, nil)
	expect(t, res, http.StatusNotFound, "unknown recipe")

	favs := func(token, query string) []string {
		_, out := app.api("GET", "/api/v1/recipes"+query, token, nil)
		var titles []string
		for _, r := range out["recipes"].([]any) {
			if m := r.(map[string]any); query != "" || m["favorite"] == true {
				titles = append(titles, m["title"].(string))
			}
		}
		return titles
	}
	if got := favs(admin, ""); len(got) != 1 || got[0] != "Soupe" {
		t.Fatalf("admin favorites flag %v", got)
	}
	if got := favs(child, "?favorite=1"); len(got) != 1 || got[0] != "Gratin" {
		t.Fatalf("child favorites filter %v", got)
	}
	_, rec := app.api("GET", "/api/v1/recipes/"+ids[0], admin, nil)
	if rec["favorite"] != true {
		t.Fatalf("recipe not marked favorite %v", rec["favorite"])
	}
	_, rec = app.api("GET", "/api/v1/recipes/"+ids[0], child, nil)
	if rec["favorite"] != false {
		t.Fatal("favorites leak between users")
	}
	res, _ = app.api("DELETE", "/api/v1/recipes/"+ids[0]+"/favorite", admin, nil)
	expect(t, res, http.StatusNoContent, "remove favorite")
	if got := favs(admin, "?favorite=1"); len(got) != 0 {
		t.Fatalf("favorite not removed %v", got)
	}
}

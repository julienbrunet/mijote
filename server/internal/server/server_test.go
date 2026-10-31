package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
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
	h, err := New(ctx, pool, nil)
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

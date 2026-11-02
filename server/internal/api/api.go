// Package api serves the versioned REST API used by the Android app.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/images"
	"github.com/julienbrunet/mijote/server/internal/store"
)

// API holds the dependencies of the REST handlers.
type API struct {
	Store   *store.Store
	Tokens  *auth.Tokens
	Guard   *auth.Guard
	Proxies auth.Proxies
	Images  images.Dir
}

// Routes registers the /api/v1 routes on mux.
func (a *API) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/auth/login", a.login)
	mux.HandleFunc("POST /api/v1/auth/refresh", a.refresh)
	mux.HandleFunc("POST /api/v1/invitations/redeem", a.redeemInvitation)
	mux.Handle("POST /api/v1/auth/logout", a.authenticate(http.HandlerFunc(a.logout)))
	mux.Handle("GET /api/v1/me", a.authenticate(http.HandlerFunc(a.me)))
	mux.Handle("PUT /api/v1/me/preferences", a.authenticate(http.HandlerFunc(a.setPreferences)))
	for pattern, h := range map[string]http.HandlerFunc{
		"GET /api/v1/recipes":               a.listRecipes,
		"POST /api/v1/recipes":              a.requireWrite(a.createRecipe),
		"GET /api/v1/recipes/{id}":          a.getRecipe,
		"PUT /api/v1/recipes/{id}":          a.requireWrite(a.updateRecipe),
		"DELETE /api/v1/recipes/{id}":       a.archiveRecipe,
		"GET /api/v1/tags":                  a.listTags,
		"GET /api/v1/recipes/{id}/image":    a.getImage,
		"PUT /api/v1/recipes/{id}/image":    a.requireWrite(a.putImage),
		"DELETE /api/v1/recipes/{id}/image": a.requireWrite(a.deleteImage),
		"GET /api/v1/menu":                  a.getMenu,
		"POST /api/v1/menu/generate":        a.requireWrite(a.generateMenu),
		"POST /api/v1/menu/slots":           a.requireWrite(a.addSlot),
		"POST /api/v1/menu/slots/{id}/swap": a.requireWrite(a.swapSlot),
		"PUT /api/v1/menu/slots/{id}":       a.requireWrite(a.updateSlot),
		"DELETE /api/v1/menu/slots/{id}":    a.requireWrite(a.removeSlot),
	} {
		mux.Handle(pattern, a.authenticate(h))
	}
	a.adminRoutes(mux)
}

// requireWrite forbids state-changing actions for read-only users.
func (a *API) requireWrite(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if current(r).user.IsReadonly() {
			problem(w, http.StatusForbidden, "forbidden", "les utilisateurs en lecture seule ne peuvent pas modifier les menus ou les recettes")
			return
		}
		next(w, r)
	}
}

type ctxKey struct{}

// session is the authenticated user and the device session of the access token.
type session struct {
	user      store.User
	sessionID string // empty for a personal API token
}

func current(r *http.Request) session {
	s, _ := r.Context().Value(ctxKey{}).(session)
	return s
}

func (a *API) client(r *http.Request) store.Client {
	return store.Client{IP: a.Proxies.ClientIP(r), UserAgent: r.UserAgent()}
}

// authenticate checks the access token and that its session was not revoked
// (revocation from the admin or disabled account take effect immediately).
func (a *API) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := auth.BearerToken(r)
		if strings.HasPrefix(raw, store.APITokenPrefix) {
			a.authenticateAPIToken(w, r, raw, next)
			return
		}
		userID, sid, err := a.Tokens.Verify(raw)
		if err != nil {
			unauthorized(w, "missing, invalid or expired access token")
			return
		}
		u, ok, err := a.Store.AppSessionActive(r.Context(), userID, sid)
		if err != nil {
			fail(w, r, err)
			return
		}
		if !ok {
			unauthorized(w, "session revoked or account disabled")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, session{u, sid})))
	})
}

// authenticateAPIToken handles personal API tokens created in the web admin.
// A "read" token may only use GET and HEAD.
func (a *API) authenticateAPIToken(w http.ResponseWriter, r *http.Request, raw string, next http.Handler) {
	u, scope, ok, err := a.Store.APITokenUser(r.Context(), raw, a.Proxies.ClientIP(r))
	if err != nil {
		fail(w, r, err)
		return
	}
	if !ok {
		unauthorized(w, "invalid, revoked or expired API token")
		return
	}
	if scope == store.ScopeRead && r.Method != http.MethodGet && r.Method != http.MethodHead {
		problem(w, http.StatusForbidden, "read_only_token", "this API token is read-only")
		return
	}
	next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, session{user: u})))
}

func (a *API) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, userJSON(current(r).user))
}

// setPreferences changes the user's own preferences: {"people": 1..20}.
func (a *API) setPreferences(w http.ResponseWriter, r *http.Request) {
	var in struct {
		People int `json:"people"`
	}
	if !body(w, r, &in) {
		return
	}
	u, err := a.Store.SetPeople(r.Context(), current(r).user.ID, in.People)
	if err != nil {
		recipeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, userJSON(u))
}

func userJSON(u store.User) map[string]any {
	return map[string]any{"id": u.ID, "username": u.Username, "displayName": u.DisplayName, "role": u.Role,
		"preferences": map[string]any{"people": u.People}}
}

// ---------- helpers ----------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v) //nolint:errcheck
}

// problem writes an error as {"error": code, "message": detail}.
func problem(w http.ResponseWriter, status int, code, detail string) {
	writeJSON(w, status, map[string]string{"error": code, "message": detail})
}

func unauthorized(w http.ResponseWriter, detail string) {
	w.Header().Set("WWW-Authenticate", `Bearer realm="mijote"`)
	problem(w, http.StatusUnauthorized, "unauthorized", detail)
}

func tooMany(w http.ResponseWriter, wait time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
	problem(w, http.StatusTooManyRequests, "too_many_requests", "too many attempts, retry later")
}

func fail(w http.ResponseWriter, r *http.Request, err error) {
	slog.Error("request failed", "path", r.URL.Path, "err", err)
	problem(w, http.StatusInternalServerError, "internal_error", "internal error")
}

// body decodes a JSON request body of at most 64 KiB.
func body(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			problem(w, http.StatusRequestEntityTooLarge, "too_large", "request body too large")
		} else {
			problem(w, http.StatusBadRequest, "invalid_json", "invalid JSON body")
		}
		return false
	}
	return true
}

func truncate(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// Package api serves the versioned REST API used by the Android app.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/store"
)

// API holds the dependencies of the REST handlers.
type API struct {
	Store   *store.Store
	Tokens  *auth.Tokens
	Guard   *auth.Guard
	Proxies auth.Proxies
}

// Routes registers the /api/v1 routes on mux.
func (a *API) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/auth/login", a.login)
	mux.HandleFunc("POST /api/v1/auth/refresh", a.refresh)
	mux.HandleFunc("POST /api/v1/invitations/redeem", a.redeemInvitation)
	mux.Handle("POST /api/v1/auth/logout", a.authenticate(http.HandlerFunc(a.logout)))
	mux.Handle("GET /api/v1/me", a.authenticate(http.HandlerFunc(a.me)))
}

type ctxKey struct{}

// session is the authenticated user and the device session of the access token.
type session struct {
	user      store.User
	sessionID string
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
		userID, sid, err := a.Tokens.Verify(auth.BearerToken(r))
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

func (a *API) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, userJSON(current(r).user))
}

func userJSON(u store.User) map[string]string {
	return map[string]string{"id": u.ID, "username": u.Username, "displayName": u.DisplayName, "role": u.Role}
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

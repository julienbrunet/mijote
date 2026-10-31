// Package server wires the HTTP routes of the backend.
package server

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/julienbrunet/mijote/server/internal/admin"
	"github.com/julienbrunet/mijote/server/internal/api"
	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/images"
	"github.com/julienbrunet/mijote/server/internal/store"
)

// APIVersions lists the REST API versions this server supports.
var APIVersions = []string{"v1"}

// New returns the root HTTP handler. Signing keys are generated on first start
// and kept in the database (server_secret).
func New(ctx context.Context, pool *pgxpool.Pool, proxies auth.Proxies, imageDir string) (http.Handler, error) {
	st := store.New(pool)
	jwtKey, err := st.Secret(ctx, "jwt_key", 32)
	if err != nil {
		return nil, err
	}
	adminKey, err := st.Secret(ctx, "admin_key", 32)
	if err != nil {
		return nil, err
	}
	// One guard for the app and the admin: the limits apply to both.
	guard := auth.NewGuard()
	imgs := images.Dir{Path: imageDir}
	adm, err := admin.New(st, guard, proxies, adminKey, imgs)
	if err != nil {
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/versions", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"versions": APIVersions})
	})
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := pool.Ping(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "db_unavailable"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.Handle("GET /admin", http.RedirectHandler("/admin/", http.StatusMovedPermanently))
	(&api.API{Store: st, Tokens: &auth.Tokens{Key: jwtKey}, Guard: guard, Proxies: proxies, Images: imgs}).Routes(mux)
	adm.Routes(mux)
	return securityHeaders(proxies, mux), nil
}

// securityHeaders adds defensive headers to every response.
func securityHeaders(proxies auth.Proxies, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "same-origin")
		h.Set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "+
			"frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
		if proxies.IsHTTPS(r) {
			h.Set("Strict-Transport-Security", "max-age=31536000")
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v) //nolint:errcheck
}

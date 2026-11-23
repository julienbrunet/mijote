package api

import (
	"net/http"

	"github.com/julienbrunet/mijote/server/internal/store"
)

const browserSessionPath = "/app/session"
const webRefreshName = "mijote_web_refresh"

// BrowserSessionRoutes keeps the rotating refresh token in an HttpOnly cookie.
// The planner continues to call the versioned API with a short-lived bearer token.
func (a *API) BrowserSessionRoutes(mux Mux, startAdmin func(http.ResponseWriter, *http.Request, store.User) error,
	endAdmin func(http.ResponseWriter, *http.Request) error,
) {
	mux.Handle("POST "+browserSessionPath+"/login", browserRequest(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.loginWith(w, r, startAdmin)
	})))
	mux.Handle("POST "+browserSessionPath+"/refresh", browserRequest(http.HandlerFunc(a.resumeBrowserSession)))
	mux.Handle("POST "+browserSessionPath+"/logout", browserRequest(a.authenticate(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a.endSession(w, r, func() error {
			a.webRefreshCookie(w, r, "", -1)
			return endAdmin(w, r)
		})
	}))))
}

func browserRequest(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		// A cross-origin form cannot send this header. The server offers no CORS access.
		if r.Header.Get("X-Mijote-Web") != "1" {
			problem(w, http.StatusForbidden, "forbidden", "same-origin planner request required")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *API) webRefreshCookie(w http.ResponseWriter, r *http.Request, token string, maxAge int) {
	http.SetCookie(w, &http.Cookie{Name: webRefreshName, Value: token, Path: browserSessionPath,
		MaxAge: maxAge, Secure: a.Proxies.IsHTTPS(r), HttpOnly: true, SameSite: http.SameSiteStrictMode})
}

func (a *API) resumeBrowserSession(w http.ResponseWriter, r *http.Request) {
	cookie, err := r.Cookie(webRefreshName)
	if err != nil {
		problem(w, http.StatusUnauthorized, "invalid_refresh_token", "session expired, sign in again")
		return
	}
	a.rotateRefresh(w, r, cookie.Value)
}

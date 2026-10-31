package api

import (
	"errors"
	"net/http"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/store"
)

type tokenPair struct {
	AccessToken  string            `json:"accessToken"`
	ExpiresIn    int               `json:"expiresIn"`
	RefreshToken string            `json:"refreshToken"`
	User         map[string]string `json:"user"`
}

func (a *API) writeTokens(w http.ResponseWriter, r *http.Request, u store.User, sid, refresh string) {
	access, err := a.Tokens.Issue(u.ID, sid)
	if err != nil {
		fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, tokenPair{AccessToken: access, ExpiresIn: int(auth.AccessTTL.Seconds()),
		RefreshToken: refresh, User: userJSON(u)})
}

func (a *API) login(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Username   string `json:"username"`
		Password   string `json:"password"`
		DeviceName string `json:"deviceName"`
	}
	if !body(w, r, &in) {
		return
	}
	if in.Username == "" || in.Password == "" {
		problem(w, http.StatusBadRequest, "validation_error", "username and password are required")
		return
	}
	c := a.client(r)
	c.Device = truncate(in.DeviceName, 100)
	if wait := a.Guard.Wait(in.Username, c.IP); wait > 0 {
		a.Store.LogAuthEvent(r.Context(), nil, in.Username, "app", "locked", "trop de tentatives", c)
		tooMany(w, wait)
		return
	}
	u, err := a.Store.CheckCredentials(r.Context(), in.Username, in.Password)
	var uid *string
	if u.ID != "" {
		uid = &u.ID
	}
	switch {
	case errors.Is(err, store.ErrInvalidCredentials), errors.Is(err, store.ErrAccountDisabled):
		// Same answer for a disabled account, so the response reveals nothing.
		detail := "identifiants invalides"
		if errors.Is(err, store.ErrAccountDisabled) {
			detail = "compte désactivé"
		}
		a.Guard.Failed(in.Username, c.IP)
		a.Store.LogAuthEvent(r.Context(), uid, in.Username, "app", "login_failed", detail, c)
		problem(w, http.StatusUnauthorized, "invalid_credentials", "wrong username or password")
		return
	case err != nil:
		fail(w, r, err)
		return
	}
	a.Guard.Succeeded(in.Username)
	sid, refresh, err := a.Store.StartAppSession(r.Context(), u.ID, c)
	if err != nil {
		fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), uid, u.Username, "app", "login", c.Device, c)
	a.writeTokens(w, r, u, sid, refresh)
}

func (a *API) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refreshToken"`
	}
	if !body(w, r, &in) {
		return
	}
	c := a.client(r)
	u, sid, refresh, err := a.Store.RotateRefresh(r.Context(), in.RefreshToken, c)
	switch {
	case errors.Is(err, store.ErrRefreshReuse):
		a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "app", "refresh_reuse",
			"jeton déjà utilisé : session révoquée", c)
		problem(w, http.StatusUnauthorized, "invalid_refresh_token", "session expired, sign in again")
	case errors.Is(err, store.ErrInvalidRefresh), errors.Is(err, store.ErrAccountDisabled):
		problem(w, http.StatusUnauthorized, "invalid_refresh_token", "session expired, sign in again")
	case err != nil:
		fail(w, r, err)
	default:
		a.writeTokens(w, r, u, sid, refresh)
	}
}

// logout closes the device session of the access token.
func (a *API) logout(w http.ResponseWriter, r *http.Request) {
	s := current(r)
	if err := a.Store.RevokeFamily(r.Context(), s.sessionID, "logout"); err != nil {
		fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "app", "logout", "", a.client(r))
	w.WriteHeader(http.StatusNoContent)
}

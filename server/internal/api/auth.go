package api

import (
	"errors"
	"net/http"
	"strings"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/store"
)

type tokenPair struct {
	AccessToken  string         `json:"accessToken"`
	ExpiresIn    int            `json:"expiresIn"`
	RefreshToken string         `json:"refreshToken,omitempty"`
	User         map[string]any `json:"user"`
}

func (a *API) writeTokens(w http.ResponseWriter, r *http.Request, status int, u store.User, sid, refresh string) {
	access, err := a.Tokens.Issue(u.ID, sid)
	if err != nil {
		fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	if strings.HasPrefix(r.URL.Path, browserSessionPath+"/") {
		a.webRefreshCookie(w, r, refresh, 0)
		refresh = ""
	}
	writeJSON(w, status, tokenPair{AccessToken: access, ExpiresIn: int(auth.AccessTTL.Seconds()),
		RefreshToken: refresh, User: userJSON(u)})
}

func (a *API) login(w http.ResponseWriter, r *http.Request) {
	a.loginWith(w, r, nil)
}

func (a *API) loginWith(w http.ResponseWriter, r *http.Request, establishAdmin func(http.ResponseWriter, *http.Request, store.User) error) {
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
	if establishAdmin != nil {
		if err := establishAdmin(w, r, u); err != nil {
			fail(w, r, err)
			return
		}
	}
	sid, refresh, err := a.Store.StartAppSession(r.Context(), u.ID, c)
	if err != nil {
		fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), uid, u.Username, "app", "login", c.Device, c)
	a.writeTokens(w, r, http.StatusOK, u, sid, refresh)
}

func (a *API) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refreshToken"`
	}
	if !body(w, r, &in) {
		return
	}
	a.rotateRefresh(w, r, in.RefreshToken)
}

func (a *API) rotateRefresh(w http.ResponseWriter, r *http.Request, raw string) {
	c := a.client(r)
	u, sid, refresh, err := a.Store.RotateRefresh(r.Context(), raw, c)
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
		a.writeTokens(w, r, http.StatusOK, u, sid, refresh)
	}
}

// logout closes the device session of the access token.
func (a *API) logout(w http.ResponseWriter, r *http.Request) {
	a.endSession(w, r, nil)
}

func (a *API) endSession(w http.ResponseWriter, r *http.Request, endBrowser func() error) {
	s := current(r)
	if s.sessionID == "" {
		problem(w, http.StatusBadRequest, "not_a_device_session", "an API token has no session to close; revoke it in the web admin")
		return
	}
	if err := a.Store.RevokeFamily(r.Context(), s.sessionID, "logout"); err != nil {
		fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "app", "logout", "", a.client(r))
	if endBrowser != nil {
		if err := endBrowser(); err != nil {
			fail(w, r, err)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

// redeemInvitation creates a member account from an invitation code (FR-ONB-03)
// and signs the device in, like login.
func (a *API) redeemInvitation(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Code        string `json:"code"`
		Username    string `json:"username"`
		DisplayName string `json:"displayName"`
		Password    string `json:"password"`
		DeviceName  string `json:"deviceName"`
	}
	if !body(w, r, &in) {
		return
	}
	if in.Code == "" || in.Username == "" || in.Password == "" {
		problem(w, http.StatusBadRequest, "validation_error", "code, username and password are required")
		return
	}
	c := a.client(r)
	c.Device = truncate(in.DeviceName, 100)
	if wait := a.Guard.PerIP.Blocked(c.IP); wait > 0 {
		a.Store.LogAuthEvent(r.Context(), nil, in.Username, "app", "locked", "trop de tentatives d'invitation", c)
		tooMany(w, wait)
		return
	}
	u, err := a.Store.RedeemInvitation(r.Context(), in.Code, in.Username, in.DisplayName, in.Password)
	var ve *store.ValidationError
	switch {
	case errors.Is(err, store.ErrInvalidInvitation):
		a.Guard.PerIP.Fail(c.IP)
		a.Store.LogAuthEvent(r.Context(), nil, in.Username, "app", "invite_failed", "code d'invitation invalide", c)
		problem(w, http.StatusNotFound, "invalid_invitation", "invitation invalid, used, revoked or expired")
		return
	case errors.As(err, &ve):
		problem(w, http.StatusUnprocessableEntity, "validation_error", ve.Msg)
		return
	case err != nil:
		fail(w, r, err)
		return
	}
	sid, refresh, err := a.Store.StartAppSession(r.Context(), u.ID, c)
	if err != nil {
		fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "app", "account_created", "invitation utilisée", c)
	a.writeTokens(w, r, http.StatusCreated, u, sid, refresh)
}

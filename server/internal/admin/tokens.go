package admin

import (
	"net/http"
	"strconv"
	"time"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Personal API tokens: created here, used as `Authorization: Bearer mjt_…`.

type newToken struct {
	Raw, Name, Scope string
	Days             int
}

type tokensPage struct {
	Tokens      []store.APIToken
	All         bool
	Days        []int
	DefaultDays int
	New         *newToken // shown once, right after creation
}

func (a *Admin) renderTokens(w http.ResponseWriter, r *http.Request, status int, errMsg string, created *newToken) {
	all := r.URL.Query().Get("all") != ""
	list, err := a.Store.ListAPITokens(r.Context(), all, 200)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "tokens", status, view{Title: "Jetons API", Nav: "tokens", Error: errMsg,
		Data: tokensPage{Tokens: list, All: all, Days: store.APITokenDays,
			DefaultDays: store.DefaultAPITokenDays, New: created}})
}

func (a *Admin) tokensPage(w http.ResponseWriter, r *http.Request) {
	a.renderTokens(w, r, http.StatusOK, "", nil)
}

func (a *Admin) createToken(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.FormValue("days"))
	valid := false
	for _, d := range store.APITokenDays {
		valid = valid || d == days
	}
	if !valid {
		days = store.DefaultAPITokenDays
	}
	scope := r.FormValue("scope")
	name := r.FormValue("name")
	s := current(r)
	raw, err := a.Store.CreateAPIToken(r.Context(), s.user.ID, name, scope, time.Duration(days)*24*time.Hour)
	if msg, isMsg := validationMsg(err); isMsg {
		a.renderTokens(w, r, http.StatusUnprocessableEntity, msg, nil)
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "token_created",
		name+" ("+scope+", "+strconv.Itoa(days)+" j)", a.client(r))
	a.renderTokens(w, r, http.StatusOK, "", &newToken{Raw: raw, Name: name, Scope: scope, Days: days})
}

func (a *Admin) revokeToken(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RevokeAPIToken(r.Context(), r.PathValue("id")); err != nil {
		a.fail(w, r, err)
		return
	}
	s := current(r)
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "token_revoked", "", a.client(r))
	redirect(w, r, "/admin/tokens", "token_revoked")
}

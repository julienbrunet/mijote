package admin

import (
	"crypto/hmac"
	"net/http"

	"github.com/julienbrunet/mijote/server/internal/store"
)

func hmacEqual(a, b []byte) bool { return hmac.Equal(a, b) }

type sessionsPage struct {
	All   bool
	App   []store.AppSession
	Admin []store.AdminSessionInfo
	Self  string
}

func (a *Admin) sessionsPage(w http.ResponseWriter, r *http.Request) {
	all := r.URL.Query().Get("all") != ""
	app, err := a.Store.ListAppSessions(r.Context(), all, 200)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	adm, err := a.Store.ListAdminSessions(r.Context(), all, 200)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "sessions", http.StatusOK, view{Title: "Sessions", Nav: "sessions",
		Data: sessionsPage{All: all, App: app, Admin: adm, Self: current(r).sessionID}})
}

func (a *Admin) revokeAppSession(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RevokeFamily(r.Context(), r.PathValue("id"), "admin"); err != nil {
		a.fail(w, r, err)
		return
	}
	redirect(w, r, "/admin/sessions", "revoked")
}

func (a *Admin) revokeAdminSession(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RevokeAdminSession(r.Context(), r.PathValue("id"), "admin"); err != nil {
		a.fail(w, r, err)
		return
	}
	redirect(w, r, "/admin/sessions", "revoked")
}

type journalPage struct {
	FailuresOnly bool
	Events       []store.AuthEvent
}

func (a *Admin) journalPage(w http.ResponseWriter, r *http.Request) {
	failures := r.URL.Query().Get("failures") != ""
	ev, err := a.Store.ListAuthEvents(r.Context(), failures, 300)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "journal", http.StatusOK, view{Title: "Journal des connexions", Nav: "journal",
		Data: journalPage{FailuresOnly: failures, Events: ev}})
}

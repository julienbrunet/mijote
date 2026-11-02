package admin

import (
	"encoding/base64"
	"html/template"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"rsc.io/qr"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Accounts and invitations (FR-ADM-02, FR-ONB-02..04).

type accountsPage struct {
	Users []store.User
	// Reset is the account whose password form failed, to show the error next to it.
	Reset string
}

func (a *Admin) renderAccounts(w http.ResponseWriter, r *http.Request, status int, errMsg, resetID string) {
	users, err := a.Store.ListUsers(r.Context())
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "accounts", status, view{Title: "Comptes", Nav: "accounts", Error: errMsg,
		Data: accountsPage{Users: users, Reset: resetID}})
}

func (a *Admin) accountsPage(w http.ResponseWriter, r *http.Request) {
	a.renderAccounts(w, r, http.StatusOK, "", "")
}

// accountAction applies a change to a member, logs it and redirects.
func (a *Admin) accountAction(w http.ResponseWriter, r *http.Request, event, detail, ok string,
	do func(id string) (store.User, error)) {
	u, err := do(r.PathValue("id"))
	if err == store.ErrNotFound {
		a.render(w, r, "error", http.StatusNotFound, view{Title: "Compte introuvable", Error: "Ce compte n'existe pas."})
		return
	} else if msg, isMsg := validationMsg(err); isMsg {
		a.renderAccounts(w, r, http.StatusUnprocessableEntity, msg, u.ID)
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	s := current(r)
	a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", event, detail+" par "+s.user.Username, a.client(r))
	redirect(w, r, "/admin/accounts", ok)
}

func (a *Admin) disableAccount(w http.ResponseWriter, r *http.Request) {
	a.accountAction(w, r, "account_disabled", "désactivé", "disabled", func(id string) (store.User, error) {
		return a.Store.SetUserDisabled(r.Context(), id, true)
	})
}

func (a *Admin) enableAccount(w http.ResponseWriter, r *http.Request) {
	a.accountAction(w, r, "account_enabled", "réactivé", "enabled", func(id string) (store.User, error) {
		return a.Store.SetUserDisabled(r.Context(), id, false)
	})
}

func (a *Admin) resetAccountPassword(w http.ResponseWriter, r *http.Request) {
	pw := r.FormValue("password")
	a.accountAction(w, r, "password_reset", "réinitialisé", "reset", func(id string) (store.User, error) {
		return a.Store.ResetMemberPassword(r.Context(), id, pw)
	})
}

func (a *Admin) changeAccountRole(w http.ResponseWriter, r *http.Request) {
	role := r.FormValue("role")
	a.accountAction(w, r, "role_changed", "rôle modifié en "+role, "role_changed", func(id string) (store.User, error) {
		return a.Store.SetUserRole(r.Context(), id, role)
	})
}

// ---------- Invitations ----------

type newInvitation struct {
	Code string
	Link string
	QR   template.URL // QR code PNG of the link, as a data: URL
	Days int
	Role string
}

type invitationsPage struct {
	Invitations []store.Invitation
	All         bool
	TTLs        []int
	DefaultDays int
	New         *newInvitation // shown once, right after creation
}

func (a *Admin) renderInvitations(w http.ResponseWriter, r *http.Request, status int, errMsg string, created *newInvitation) {
	all := r.URL.Query().Get("all") != ""
	list, err := a.Store.ListInvitations(r.Context(), all, 200)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.render(w, r, "invitations", status, view{Title: "Invitations", Nav: "invitations", Error: errMsg,
		Data: invitationsPage{Invitations: list, All: all, TTLs: store.InvitationTTLs,
			DefaultDays: store.DefaultInvitationDays, New: created}})
}

func (a *Admin) invitationsPage(w http.ResponseWriter, r *http.Request) {
	a.renderInvitations(w, r, http.StatusOK, "", nil)
}

// serverURL is the public address the app must use, as seen by the admin's browser.
func (a *Admin) serverURL(r *http.Request) string {
	scheme := "http"
	if a.Proxies.IsHTTPS(r) {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

// invitationPageURL is the link the admin shares (message, mail, QR code): a plain
// web page that works with or without the app installed.
func invitationPageURL(serverURL, code string) string {
	return serverURL + "/invite/" + url.PathEscape(code)
}

// appInvitationLink opens the app directly, with the server address and the code.
func appInvitationLink(serverURL, code string) string {
	return "mijote://invite?server=" + url.QueryEscape(serverURL) + "&code=" + url.QueryEscape(code)
}

func (a *Admin) createInvitation(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.FormValue("days"))
	valid := false
	for _, d := range store.InvitationTTLs {
		valid = valid || d == days
	}
	if !valid {
		days = store.DefaultInvitationDays
	}
	role := r.FormValue("role")
	if role == "" {
		role = store.RoleMember
	}
	s := current(r)
	code, err := a.Store.CreateInvitation(r.Context(), s.user.ID, role, time.Duration(days)*24*time.Hour)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "invite_created",
		"rôle "+role+", valable "+strconv.Itoa(days)+" j", a.client(r))
	link := invitationPageURL(a.serverURL(r), code)
	n := &newInvitation{Code: code, Link: link, Days: days, Role: role}
	if img, err := qr.Encode(link, qr.M); err == nil {
		n.QR = template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(img.PNG()))
	}
	a.renderInvitations(w, r, http.StatusOK, "", n)
}

func (a *Admin) revokeInvitation(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RevokeInvitation(r.Context(), r.PathValue("id")); err != nil {
		a.fail(w, r, err)
		return
	}
	s := current(r)
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "invite_revoked", "", a.client(r))
	redirect(w, r, "/admin/invitations", "inv_revoked")
}

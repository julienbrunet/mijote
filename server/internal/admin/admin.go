// Package admin serves the server-rendered web admin (FR-ADM-01). For now it
// covers the setup wizard, sign-in with TOTP two-factor authentication, the
// account page, session tracking and the login journal.
package admin

import (
	"embed"
	"html/template"
	"log/slog"
	"net/http"
	"time"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/store"
)

//go:embed templates/*.html
var templateFS embed.FS

var funcs = template.FuncMap{
	"date": func(t time.Time) string { return t.Local().Format("02/01/2006 15:04") },
	"event": func(e string) string {
		if l, ok := eventLabels[e]; ok {
			return l
		}
		return e
	},
	"deref": func(s *string) string {
		if s == nil {
			return ""
		}
		return *s
	},
}

var eventLabels = map[string]string{
	"setup": "Configuration initiale", "login": "Connexion", "logout": "Déconnexion",
	"login_failed": "Échec de connexion", "locked": "Blocage (trop de tentatives)",
	"refresh_reuse": "Jeton réutilisé", "password_changed": "Mot de passe changé",
	"2fa_enabled": "2FA activée", "2fa_disabled": "2FA désactivée", "2fa_failed": "Échec 2FA",
	"2fa_backup": "Code de secours utilisé", "2fa_backup_regen": "Codes de secours régénérés",
	"account_created": "Compte créé (invitation)", "invite_failed": "Invitation refusée",
	"invite_created": "Invitation créée", "invite_revoked": "Invitation révoquée",
	"account_disabled": "Compte désactivé", "account_enabled": "Compte réactivé",
	"password_reset": "Mot de passe réinitialisé",
}

// Admin holds the dependencies of the web admin.
type Admin struct {
	Store   *store.Store
	Guard   *auth.Guard
	Proxies auth.Proxies
	// Key signs CSRF tokens and the two-factor challenge cookie.
	Key []byte

	pages map[string]*template.Template
}

// New parses the templates.
func New(st *store.Store, guard *auth.Guard, proxies auth.Proxies, key []byte) (*Admin, error) {
	a := &Admin{Store: st, Guard: guard, Proxies: proxies, Key: key, pages: map[string]*template.Template{}}
	for _, p := range []string{"setup", "login", "login_2fa", "home", "account", "sessions", "journal", "accounts", "invitations", "error"} {
		t, err := template.New("layout.html").Funcs(funcs).ParseFS(templateFS, "templates/layout.html", "templates/"+p+".html")
		if err != nil {
			return nil, err
		}
		a.pages[p] = t
	}
	return a, nil
}

// Routes registers the /admin routes on mux.
func (a *Admin) Routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin/setup", a.setupPage)
	mux.HandleFunc("POST /admin/setup", a.setup)
	mux.HandleFunc("GET /admin/login", a.loginPage)
	mux.HandleFunc("POST /admin/login", a.login)
	mux.HandleFunc("GET /admin/login/2fa", a.twoFALoginPage)
	mux.HandleFunc("POST /admin/login/2fa", a.twoFALogin)

	protected := map[string]http.HandlerFunc{
		"GET /admin/{$}":                      a.home,
		"POST /admin/logout":                  a.logout,
		"GET /admin/account":                  a.accountPage,
		"POST /admin/password":                a.changePassword,
		"POST /admin/logout-everywhere":       a.logoutEverywhere,
		"POST /admin/2fa/setup":               a.twoFASetup,
		"POST /admin/2fa/enable":              a.twoFAEnable,
		"POST /admin/2fa/disable":             a.twoFADisable,
		"POST /admin/2fa/backup":              a.twoFABackup,
		"GET /admin/sessions":                 a.sessionsPage,
		"POST /admin/sessions/app/{id}":       a.revokeAppSession,
		"POST /admin/sessions/admin/{id}":     a.revokeAdminSession,
		"GET /admin/journal":                  a.journalPage,
		"GET /admin/accounts":                 a.accountsPage,
		"POST /admin/accounts/{id}/disable":   a.disableAccount,
		"POST /admin/accounts/{id}/enable":    a.enableAccount,
		"POST /admin/accounts/{id}/password":  a.resetAccountPassword,
		"GET /admin/invitations":              a.invitationsPage,
		"POST /admin/invitations":             a.createInvitation,
		"POST /admin/invitations/{id}/revoke": a.revokeInvitation,
	}
	for pattern, h := range protected {
		mux.Handle(pattern, a.requireSession(h))
	}
}

// view is the data passed to every page.
type view struct {
	Title  string
	Nav    string
	Error  string
	Notice string
	CSRF   string
	User   *store.User
	Data   any
}

func (a *Admin) render(w http.ResponseWriter, r *http.Request, page string, status int, v view) {
	if s, ok := currentSession(r); ok {
		v.User, v.CSRF = &s.user, a.csrfToken(s.sessionID)
	}
	if v.Notice == "" {
		v.Notice = notices[r.URL.Query().Get("ok")]
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := a.pages[page].Execute(w, v); err != nil {
		slog.Error("render", "page", page, "err", err)
	}
}

// notices are the confirmation messages shown after a redirect (?ok=<key>).
var notices = map[string]string{
	"password":     "Mot de passe changé ; vos autres sessions ont été fermées.",
	"everywhere":   "Toutes vos autres sessions ont été fermées.",
	"2fa_disabled": "Double authentification désactivée.",
	"revoked":      "Session révoquée.",
	"disabled":     "Compte désactivé ; ses sessions ont été fermées.",
	"enabled":      "Compte réactivé.",
	"reset":        "Mot de passe modifié ; les sessions du compte ont été fermées.",
	"inv_revoked":  "Invitation révoquée.",
}

func redirect(w http.ResponseWriter, r *http.Request, path, ok string) {
	if ok != "" {
		path += "?ok=" + ok
	}
	http.Redirect(w, r, path, http.StatusSeeOther)
}

func (a *Admin) fail(w http.ResponseWriter, r *http.Request, err error) {
	slog.Error("admin request failed", "path", r.URL.Path, "err", err)
	a.render(w, r, "error", http.StatusInternalServerError, view{Title: "Erreur", Error: "Une erreur interne est survenue."})
}

func validationMsg(err error) (string, bool) {
	if v, ok := err.(*store.ValidationError); ok {
		return v.Msg, true
	}
	return "", false
}

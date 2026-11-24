// Package admin serves the server-rendered web admin (FR-ADM-01): the setup
// wizard, sign-in with TOTP two-factor authentication, the home page with the
// planned meals, recipes (tags, usage statistics), users (accounts,
// invitations, sessions, login journal), the system page and the profile of
// the admin (account, API tokens).
package admin

import (
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"html/template"
	"log/slog"
	"net/http"
	"path"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/images"
	"github.com/julienbrunet/mijote/server/internal/store"
	"github.com/julienbrunet/mijote/server/internal/sysinfo"
	"github.com/julienbrunet/mijote/server/internal/web"
)

//go:embed templates/*.html
var templateFS embed.FS

// Static files of the admin, served from 'self': admin.js is the only script
// (the CSP forbids inline scripts), admin.css the "Sunny Kit" stylesheet, and
// the logo and wordmark are the app's (requirements.md §8).
//
//go:embed static
var staticFS embed.FS

type staticFile struct {
	data        []byte
	contentType string
	etag        string
}

var staticFiles = func() map[string]staticFile {
	types := map[string]string{".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".jpg": "image/jpeg"}
	out := map[string]staticFile{}
	entries, _ := staticFS.ReadDir("static")
	for _, e := range entries {
		b, err := staticFS.ReadFile("static/" + e.Name())
		if err != nil {
			panic(err)
		}
		h := sha256.Sum256(b)
		out[e.Name()] = staticFile{b, types[path.Ext(e.Name())], `"` + hex.EncodeToString(h[:8]) + `"`}
	}
	return out
}()

// RepoURL is the source repository, linked from the footer.
const RepoURL = "https://github.com/julienbrunet/mijote"

var funcs = template.FuncMap{
	"date": func(t time.Time) string { return t.Local().Format("02/01/2006 15:04") },
	"event": func(e string) string {
		if l, ok := eventLabels[e]; ok {
			return l
		}
		return e
	},
	"intp": func(v *int) string {
		if v == nil {
			return ""
		}
		return strconv.Itoa(*v)
	},
	"has": func(list []string, v string) bool { return slices.Contains(list, v) },
	"label": func(opts []store.Option, code string) string {
		for _, o := range opts {
			if o.Code == code {
				return o.Label
			}
		}
		return code
	},
	"deref": func(s *string) string {
		if s == nil {
			return ""
		}
		return *s
	},
	"day": func(t time.Time) string { return t.Format("02/01/2006") },
	// mealrow packs a meal with what its row needs (past list, CSRF token).
	"mealrow": func(m *store.MealListItem, past bool, csrf string) map[string]any {
		return map[string]any{"M": m, "Past": past, "CSRF": csrf}
	},
	"bytes": formatBytes,
	"dec":   func(v float64) string { return strings.Replace(strconv.FormatFloat(v, 'f', 2, 64), ".", ",", 1) },
	"asset": func(name string) string {
		return "/app/admin/" + name + "?v=" + strings.Trim(staticFiles[name].etag, `"`)
	},
	"dish": func(code *string) string {
		if code == nil {
			return ""
		}
		for _, o := range store.DishTypes {
			if o.Code == *code {
				return o.Label
			}
		}
		return *code
	},
	"emoji": func(code *string) string {
		if code == nil {
			return "🍽️"
		}
		switch *code {
		case "meat":
			return "🍗"
		case "fish":
			return "🐟"
		case "vegetarian":
			return "🥗"
		}
		return "🍽️"
	},
}

// formatBytes prints a size with a binary unit, the French way ("1,5 Mo").
func formatBytes(v any) string {
	var n float64
	switch b := v.(type) {
	case int64:
		n = float64(b)
	case uint64:
		n = float64(b)
	case int:
		n = float64(b)
	}
	units := []string{"o", "Ko", "Mo", "Go", "To"}
	i := 0
	for n >= 1024 && i < len(units)-1 {
		n /= 1024
		i++
	}
	prec := 1
	if i == 0 || n >= 100 {
		prec = 0
	}
	return strings.Replace(strconv.FormatFloat(n, 'f', prec, 64), ".", ",", 1) + " " + units[i]
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
	"password_reset": "Mot de passe réinitialisé", "role_changed": "Rôle modifié",
	"token_created": "Jeton d'API créé", "token_revoked": "Jeton d'API révoqué",
}

// Admin holds the dependencies of the web admin.
type Admin struct {
	Store   *store.Store
	Guard   *auth.Guard
	Proxies auth.Proxies
	// Key signs CSRF tokens and the two-factor challenge cookie.
	Key    []byte
	Images images.Dir
	// Version is the server version, shown in the footer and on the system page.
	Version string
	// APIVersions are the REST API versions served (system page).
	APIVersions []string
	// CPU measures the processor time used by the server (system page).
	CPU *sysinfo.Sampler

	pages map[string]*template.Template
}

// New parses the templates.
func New(st *store.Store, guard *auth.Guard, proxies auth.Proxies, key []byte, imgs images.Dir, version string, cpu *sysinfo.Sampler) (*Admin, error) {
	a := &Admin{Store: st, Guard: guard, Proxies: proxies, Key: key, Images: imgs, Version: version, CPU: cpu,
		pages: map[string]*template.Template{}}
	base, err := web.ParseShell(template.New("management").Funcs(funcs))
	if err != nil {
		return nil, err
	}
	for _, p := range []string{"setup", "login", "login_2fa", "home", "meal", "account", "sessions", "journal", "accounts",
		"invitations", "invite", "tokens", "recipes", "recipe", "recipe_stats", "tags", "system", "error"} {
		t, err := base.Clone()
		if err != nil {
			return nil, err
		}
		t, err = t.ParseFS(templateFS, "templates/layout.html", "templates/partials.html", "templates/"+p+".html")
		if err != nil {
			return nil, err
		}
		a.pages[p] = t
	}
	return a, nil
}

// Routes registers the administration pages under /app/admin/.
func (a *Admin) Routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /app/admin/setup", a.setupPage)
	mux.HandleFunc("POST /app/admin/setup", a.setup)
	mux.HandleFunc("GET /app/admin/login", a.loginPage)
	mux.HandleFunc("POST /app/admin/login", a.login)
	mux.HandleFunc("GET /app/admin/login/2fa", a.twoFALoginPage)
	mux.HandleFunc("POST /app/admin/login/2fa", a.twoFALogin)
	// Public page behind the shared invitation link (no session).
	mux.HandleFunc("GET /invite/{code}", a.invitePage)
	for name := range staticFiles {
		mux.HandleFunc("GET /app/admin/"+name, serveStatic(name))
	}

	protected := map[string]http.HandlerFunc{
		"GET /app/admin/{$}":                        a.home,
		"POST /app/admin/logout":                    a.logout,
		"GET /app/admin/account":                    a.accountPage,
		"POST /app/admin/password":                  a.changePassword,
		"POST /app/admin/logout-everywhere":         a.logoutEverywhere,
		"POST /app/admin/2fa/setup":                 a.twoFASetup,
		"POST /app/admin/2fa/enable":                a.twoFAEnable,
		"POST /app/admin/2fa/disable":               a.twoFADisable,
		"POST /app/admin/2fa/backup":                a.twoFABackup,
		"GET /app/admin/sessions":                   a.sessionsPage,
		"POST /app/admin/sessions/app/{id}":         a.revokeAppSession,
		"POST /app/admin/sessions/app/admin/{id}":   a.revokeAdminSession,
		"GET /app/admin/journal":                    a.journalPage,
		"GET /app/admin/accounts":                   a.accountsPage,
		"POST /app/admin/accounts/{id}/disable":     a.disableAccount,
		"POST /app/admin/accounts/{id}/enable":      a.enableAccount,
		"POST /app/admin/accounts/{id}/password":    a.resetAccountPassword,
		"POST /app/admin/accounts/{id}/role":        a.changeAccountRole,
		"GET /app/admin/invitations":                a.invitationsPage,
		"POST /app/admin/invitations":               a.createInvitation,
		"POST /app/admin/invitations/{id}/revoke":   a.revokeInvitation,
		"GET /app/admin/meals":                      a.oldMealsPage,
		"GET /app/admin/meals/{id}":                 a.mealPage,
		"POST /app/admin/meals/{id}/recipe":         a.setMealRecipe,
		"POST /app/admin/meals/{id}/title":          a.setMealTitle,
		"POST /app/admin/meals/{id}/swap":           a.swapMeal,
		"POST /app/admin/meals/{id}/delete":         a.deleteMeal,
		"GET /app/admin/recipes/stats":              a.recipeStatsPage,
		"GET /app/admin/system":                     a.systemPage,
		"GET /app/admin/tokens":                     a.tokensPage,
		"POST /app/admin/tokens":                    a.createToken,
		"POST /app/admin/tokens/{id}/revoke":        a.revokeToken,
		"GET /app/admin/recipes":                    a.recipesPage,
		"GET /app/admin/recipes/new":                a.newRecipePage,
		"POST /app/admin/recipes":                   a.saveRecipe,
		"GET /app/admin/recipes/{id}":               a.editRecipePage,
		"POST /app/admin/recipes/{id}":              a.saveRecipe,
		"POST /app/admin/recipes/{id}/archive":      a.setRecipeArchived(true, "recipe_archived"),
		"POST /app/admin/recipes/{id}/restore":      a.setRecipeArchived(false, "recipe_restored"),
		"GET /app/admin/recipes/{id}/image":         a.recipeImage,
		"POST /app/admin/recipes/{id}/image":        a.uploadRecipeImage,
		"POST /app/admin/recipes/{id}/image/remove": a.removeRecipeImage,
		"GET /app/admin/tags":                       a.tagsPage,
		"POST /app/admin/tags/{id}/rename":          a.renameTag,
		"POST /app/admin/tags/{id}/delete":          a.deleteTag,
	}
	for pattern, h := range protected {
		mux.Handle(pattern, a.requireSession(h))
	}
}

func serveStatic(name string) http.HandlerFunc {
	f := staticFiles[name]
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", f.contentType)
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("ETag", f.etag)
		http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(f.data))
	}
}

// view is the data passed to every page.
type view struct {
	Title  string
	Nav    string // page key: a navigation section or one of its sub-pages
	Error  string
	Notice string
	CSRF   string
	User   *store.User
	Data   any
	Wide   bool // the page uses the whole width of the screen

	// Filled by render.
	Shell   web.Shell
	Section web.NavItem
	Version string
	RepoURL string
}

func (a *Admin) render(w http.ResponseWriter, r *http.Request, page string, status int, v view) {
	v.Section = web.ManagementSection(v.Nav)
	v.Shell = web.Shell{Management: true, Wide: v.Wide, AdminSection: v.Section.Key}
	if s, ok := currentSession(r); ok {
		v.User, v.CSRF = &s.user, a.csrfToken(s.sessionID)
		v.Shell.AdminName = s.user.DisplayName
		if v.Shell.AdminName == "" {
			v.Shell.AdminName = s.user.Username
		}
		// The version is only disclosed to the signed-in admin.
		v.Version = a.Version
	}
	v.RepoURL = RepoURL
	if v.Notice == "" {
		v.Notice = notices[r.URL.Query().Get("ok")]
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := a.pages[page].ExecuteTemplate(w, "shell", v); err != nil {
		slog.Error("render", "page", page, "err", err)
	}
}

// notices are the confirmation messages shown after a redirect (?ok=<key>).
var notices = map[string]string{
	"password":        "Mot de passe changé ; vos autres sessions ont été fermées.",
	"everywhere":      "Toutes vos autres sessions ont été fermées.",
	"2fa_disabled":    "Double authentification désactivée.",
	"revoked":         "Session révoquée.",
	"disabled":        "Compte désactivé ; ses sessions ont été fermées.",
	"enabled":         "Compte réactivé.",
	"role_changed":    "Rôle modifié.",
	"reset":           "Mot de passe modifié ; les sessions du compte ont été fermées.",
	"inv_revoked":     "Invitation révoquée.",
	"token_revoked":   "Jeton révoqué.",
	"recipe_created":  "Recette créée.",
	"recipe_saved":    "Recette enregistrée.",
	"recipe_archived": "Recette archivée : elle n'apparaît plus dans l'app ni dans les menus futurs.",
	"recipe_restored": "Recette restaurée.",
	"tag_renamed":     "Étiquette renommée.",
	"tag_deleted":     "Étiquette supprimée.",
	"meal_saved":      "Repas modifié.",
	"meal_deleted":    "Repas retiré.",
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

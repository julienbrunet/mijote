package admin

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/julienbrunet/mijote/server/internal/images"
	"github.com/julienbrunet/mijote/server/internal/store"
)

const cookieName = "mijote_admin"

type ctxKey struct{}

type adminSession struct {
	user      store.User
	sessionID string
}

func currentSession(r *http.Request) (adminSession, bool) {
	s, ok := r.Context().Value(ctxKey{}).(adminSession)
	return s, ok
}

func current(r *http.Request) adminSession {
	s, _ := currentSession(r)
	return s
}

func (a *Admin) client(r *http.Request) store.Client {
	return store.Client{IP: a.Proxies.ClientIP(r), UserAgent: r.UserAgent()}
}

func (a *Admin) mac(purpose, msg string) []byte {
	m := hmac.New(sha256.New, a.Key)
	m.Write([]byte(purpose + ":" + msg))
	return m.Sum(nil)
}

// csrfToken is derived from the session: a form from another session or site is refused.
func (a *Admin) csrfToken(sessionID string) string {
	return hex.EncodeToString(a.mac("csrf", sessionID))
}

func (a *Admin) setCookie(w http.ResponseWriter, r *http.Request, name, value string, maxAge int) {
	path := "/app/admin"
	if name == cookieName {
		path = "/app"
	}
	http.SetCookie(w, &http.Cookie{Name: name, Value: value, Path: path, MaxAge: maxAge,
		HttpOnly: true, Secure: a.Proxies.IsHTTPS(r), SameSite: http.SameSiteStrictMode})
}

// requireSession protects pages: valid admin session and CSRF token on POST.
func (a *Admin) requireSession(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			limit := int64(1 << 20)
			if strings.HasSuffix(r.URL.Path, "/image") {
				limit = images.MaxUpload + 1<<20 // picture upload plus form fields
			}
			r.Body = http.MaxBytesReader(w, r.Body, limit)
		}
		c, err := r.Cookie(cookieName)
		if err != nil {
			a.toLogin(w, r)
			return
		}
		u, sid, err := a.Store.AdminSession(r.Context(), c.Value, a.client(r))
		if errors.Is(err, store.ErrNotFound) {
			a.setCookie(w, r, cookieName, "", -1)
			a.toLogin(w, r)
			return
		} else if err != nil {
			a.fail(w, r, err)
			return
		}
		if r.Method == http.MethodPost && !hmac.Equal([]byte(r.FormValue("csrf")), []byte(a.csrfToken(sid))) {
			a.render(w, r, "error", http.StatusForbidden, view{Title: "Formulaire expiré",
				Error: "Ce formulaire a expiré ou ne vient pas de cette page : rechargez la page et recommencez."})
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, adminSession{u, sid})))
	})
}

func (a *Admin) toLogin(w http.ResponseWriter, r *http.Request) {
	if setup, err := a.Store.NeedsSetup(r.Context()); err == nil && setup {
		http.Redirect(w, r, "/app/admin/setup", http.StatusSeeOther)
		return
	}
	next := ""
	if r.Method == http.MethodGet && r.URL.Path != "/app/admin/" {
		next = "?next=" + url.QueryEscape(r.URL.RequestURI())
	}
	http.Redirect(w, r, "/app/admin/login"+next, http.StatusSeeOther)
}

// safeNext only accepts a local admin path (no open redirect).
func safeNext(next string) string {
	if strings.HasPrefix(next, "/app/admin/") && !strings.HasPrefix(next, "//") && !strings.Contains(next, "\\") {
		return next
	}
	return "/app/admin/"
}

// ---------- Setup wizard (FR-ONB-01) ----------

func (a *Admin) setupPage(w http.ResponseWriter, r *http.Request) {
	if setup, err := a.Store.NeedsSetup(r.Context()); err != nil {
		a.fail(w, r, err)
		return
	} else if !setup {
		http.Redirect(w, r, "/app/admin/login", http.StatusSeeOther)
		return
	}
	a.render(w, r, "setup", http.StatusOK, view{Title: "Première configuration"})
}

func (a *Admin) setup(w http.ResponseWriter, r *http.Request) {
	username, password := r.FormValue("username"), r.FormValue("password")
	var err error
	if password != r.FormValue("confirm") {
		err = &store.ValidationError{Msg: "les deux saisies du mot de passe diffèrent"}
	} else {
		var u store.User
		u, err = a.Store.CreateFirstAdmin(r.Context(), username, r.FormValue("display_name"), password)
		if err == nil {
			a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", "setup", "compte administrateur créé", a.client(r))
		}
	}
	if errors.Is(err, store.ErrAlreadySetUp) {
		http.Redirect(w, r, "/app/admin/login", http.StatusSeeOther)
		return
	} else if msg, ok := validationMsg(err); ok {
		a.render(w, r, "setup", http.StatusUnprocessableEntity, view{Title: "Première configuration", Error: msg})
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	http.Redirect(w, r, "/app/", http.StatusSeeOther)
}

// ---------- Sign-in ----------

type loginData struct{ Next string }

func (a *Admin) loginPage(w http.ResponseWriter, r *http.Request) {
	next := r.URL.Query().Get("next")
	// Already signed in (bookmark or address suggested by the browser): go straight in.
	if c, err := r.Cookie(cookieName); err == nil {
		if _, _, err := a.Store.AdminSession(r.Context(), c.Value, a.client(r)); err == nil {
			http.Redirect(w, r, safeNext(next), http.StatusSeeOther)
			return
		}
	}
	if r.URL.Query().Get("restart") == "1" {
		a.setCookie(w, r, challengeCookie, "", -1)
	} else if _, _, _, ok := a.readChallenge(r); ok {
		http.Redirect(w, r, "/app/admin/login/2fa?next="+url.QueryEscape(safeNext(next)), http.StatusSeeOther)
		return
	}
	if setup, err := a.Store.NeedsSetup(r.Context()); err == nil && setup {
		http.Redirect(w, r, "/app/admin/setup", http.StatusSeeOther)
		return
	}
	a.render(w, r, "login", http.StatusOK, view{Title: "Connexion", Data: loginData{next}})
}

// adminGuardKey: the sign-in page only asks for the password (single admin), so
// failures are counted under one shared key.
const adminGuardKey = "*admin*"

// login checks the password of the single admin account. With two-factor
// authentication enabled, the session is only created after the code step.
func (a *Admin) login(w http.ResponseWriter, r *http.Request) {
	password, next := r.FormValue("password"), r.FormValue("next")
	remember := r.FormValue("remember") != ""
	c := a.client(r)
	fail := func(status int, msg string) {
		a.render(w, r, "login", status, view{Title: "Connexion", Error: msg, Data: loginData{next}})
	}
	if wait := a.Guard.Wait(adminGuardKey, c.IP); wait > 0 {
		a.Store.LogAuthEvent(r.Context(), nil, "(administrateur)", "admin", "locked", "trop de tentatives", c)
		w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
		fail(http.StatusTooManyRequests, "Trop de tentatives : réessayez dans quelques minutes.")
		return
	}
	u, err := a.adminByPassword(r.Context(), password)
	if errors.Is(err, store.ErrInvalidCredentials) || errors.Is(err, store.ErrAccountDisabled) {
		var uid *string
		if u.ID != "" {
			uid = &u.ID
		}
		a.Guard.Failed(adminGuardKey, c.IP)
		a.Store.LogAuthEvent(r.Context(), uid, "(administrateur)", "admin", "login_failed", "mot de passe incorrect", c)
		fail(http.StatusUnauthorized, "Mot de passe incorrect.")
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Guard.Succeeded(adminGuardKey)
	if st, err := a.Store.TOTPStatus(r.Context(), u.ID); err != nil {
		a.fail(w, r, err)
		return
	} else if st.Enabled {
		a.setCookie(w, r, challengeCookie, a.signChallenge(u.ID, safeNext(next), remember), int(challengeTTL.Seconds()))
		http.Redirect(w, r, "/app/admin/login/2fa", http.StatusSeeOther)
		return
	}
	a.openSession(w, r, u, remember, "", safeNext(next))
}

func (a *Admin) openSession(w http.ResponseWriter, r *http.Request, u store.User, remember bool, detail, next string) {
	if err := a.startSession(w, r, u, remember, detail); err != nil {
		a.fail(w, r, err)
		return
	}
	http.Redirect(w, r, next, http.StatusSeeOther)
}

func (a *Admin) startSession(w http.ResponseWriter, r *http.Request, u store.User, remember bool, detail string) error {
	c := a.client(r)
	raw, ttl, err := a.Store.StartAdminSession(r.Context(), u.ID, c, remember)
	if err != nil {
		return err
	}
	a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", "login", detail, c)
	a.setCookie(w, r, cookieName, raw, int(ttl.Seconds()))
	return nil
}

// VerifiedBrowserLogin receives an account whose password the planner login has checked.
func (a *Admin) VerifiedBrowserLogin(w http.ResponseWriter, r *http.Request, user store.User) error {
	if err := a.EndBrowserSession(w, r); err != nil {
		return err
	}
	if !user.IsAdmin() || a.Guard.Wait(adminGuardKey, a.client(r).IP) > 0 {
		return nil
	}
	status, err := a.Store.TOTPStatus(r.Context(), user.ID)
	if err != nil {
		return err
	}
	if status.Enabled {
		a.setCookie(w, r, challengeCookie, a.signChallenge(user.ID, "/app/admin/", false), int(challengeTTL.Seconds()))
		return nil
	}
	return a.startSession(w, r, user, false, "connexion au planning web")
}

// adminByPassword checks the password against the single admin account.
func (a *Admin) adminByPassword(ctx context.Context, password string) (store.User, error) {
	u, err := a.Store.Admin(ctx)
	if errors.Is(err, store.ErrNotFound) {
		_, _ = a.Store.CheckCredentials(ctx, "", password) // constant response time
		return u, store.ErrInvalidCredentials
	} else if err != nil {
		return u, err
	}
	return a.Store.CheckCredentials(ctx, u.Username, password)
}

func (a *Admin) logout(w http.ResponseWriter, r *http.Request) {
	s := current(r)
	if err := a.Store.RevokeAdminSession(r.Context(), s.sessionID, "logout"); err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "logout", "", a.client(r))
	a.setCookie(w, r, cookieName, "", -1)
	http.Redirect(w, r, "/app/", http.StatusSeeOther)
}

// EndBrowserSession revokes admin access when the family browser account changes or signs out.
func (a *Admin) EndBrowserSession(w http.ResponseWriter, r *http.Request) error {
	a.setCookie(w, r, challengeCookie, "", -1)
	cookie, err := r.Cookie(cookieName)
	if err != nil {
		return nil
	}
	a.setCookie(w, r, cookieName, "", -1)
	client := a.client(r)
	user, id, err := a.Store.AdminSession(r.Context(), cookie.Value, client)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := a.Store.RevokeAdminSession(r.Context(), id, "logout"); err != nil {
		return err
	}
	a.Store.LogAuthEvent(r.Context(), &user.ID, user.Username, "admin", "logout", "", client)
	return nil
}

// ---------- Account ----------

func (a *Admin) changePassword(w http.ResponseWriter, r *http.Request) {
	s := current(r)
	next := r.FormValue("new")
	var err error
	if next != r.FormValue("confirm") {
		err = &store.ValidationError{Msg: "les deux saisies du nouveau mot de passe diffèrent"}
	} else {
		err = a.Store.ChangeOwnPassword(r.Context(), s.user.ID, r.FormValue("current"), next, s.sessionID)
	}
	if msg, ok := validationMsg(err); ok {
		a.renderAccount(w, r, http.StatusUnprocessableEntity, msg, nil)
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "password_changed", "", a.client(r))
	redirect(w, r, "/app/admin/account", "password")
}

func (a *Admin) logoutEverywhere(w http.ResponseWriter, r *http.Request) {
	s := current(r)
	if err := a.Store.RevokeUserSessions(r.Context(), s.user.ID, "admin", s.sessionID); err != nil {
		a.fail(w, r, err)
		return
	}
	redirect(w, r, "/app/admin/account", "everywhere")
}

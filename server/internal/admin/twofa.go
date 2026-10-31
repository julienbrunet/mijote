package admin

import (
	"encoding/base64"
	"errors"
	"html/template"
	"net/http"
	"strconv"
	"strings"
	"time"

	"rsc.io/qr"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// TOTP two-factor sign-in: after the password, a signed challenge cookie (5 min,
// grants nothing else) leads to the code step; the session is created only once
// a TOTP or backup code is accepted.

const (
	challengeCookie = "mijote_admin_2fa"
	challengeTTL    = 5 * time.Minute
)

// signChallenge produces a signed "user | expiry | remember | next" token.
func (a *Admin) signChallenge(userID, next string, remember bool) string {
	body := base64.RawURLEncoding.EncodeToString([]byte(userID + "|" +
		strconv.FormatInt(time.Now().Add(challengeTTL).Unix(), 10) + "|" + strconv.FormatBool(remember) + "|" + next))
	return body + "." + base64.RawURLEncoding.EncodeToString(a.mac("2fa", body))
}

func (a *Admin) readChallenge(r *http.Request) (userID, next string, remember, ok bool) {
	c, err := r.Cookie(challengeCookie)
	if err != nil {
		return
	}
	body, sig, found := strings.Cut(c.Value, ".")
	want, err := base64.RawURLEncoding.DecodeString(sig)
	if !found || err != nil || !hmacEqual(want, a.mac("2fa", body)) {
		return
	}
	raw, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		return
	}
	parts := strings.SplitN(string(raw), "|", 4)
	if len(parts) != 4 {
		return
	}
	exp, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || time.Now().Unix() > exp {
		return
	}
	return parts[0], parts[3], parts[2] == "true", true
}

func (a *Admin) twoFALoginPage(w http.ResponseWriter, r *http.Request) {
	if _, next, _, ok := a.readChallenge(r); ok {
		a.render(w, r, "login_2fa", http.StatusOK, view{Title: "Vérification en deux étapes", Data: loginData{next}})
		return
	}
	http.Redirect(w, r, "/admin/login", http.StatusSeeOther)
}

func (a *Admin) twoFALogin(w http.ResponseWriter, r *http.Request) {
	userID, next, remember, ok := a.readChallenge(r)
	if !ok {
		http.Redirect(w, r, "/admin/login", http.StatusSeeOther)
		return
	}
	c := a.client(r)
	u, err := a.Store.GetUser(r.Context(), userID)
	if err != nil || u.Disabled() || !u.IsAdmin() {
		a.setCookie(w, r, challengeCookie, "", -1)
		http.Redirect(w, r, "/admin/login", http.StatusSeeOther)
		return
	}
	fail := func(status int, msg string) {
		a.render(w, r, "login_2fa", status, view{Title: "Vérification en deux étapes", Error: msg, Data: loginData{next}})
	}
	// Code failures go through the same limit as the password.
	if wait := a.Guard.Wait(adminGuardKey, c.IP); wait > 0 {
		a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", "locked", "trop de tentatives (2FA)", c)
		w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
		fail(http.StatusTooManyRequests, "Trop de tentatives : réessayez dans quelques minutes.")
		return
	}
	backup, err := a.Store.TOTPVerify(r.Context(), u.ID, r.FormValue("code"))
	if errors.Is(err, store.ErrTOTPInvalid) {
		a.Guard.Failed(adminGuardKey, c.IP)
		a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", "2fa_failed", "code incorrect", c)
		fail(http.StatusUnauthorized, "Code incorrect.")
		return
	} else if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Guard.Succeeded(adminGuardKey)
	detail := ""
	if backup {
		detail = "code de secours utilisé"
		a.Store.LogAuthEvent(r.Context(), &u.ID, u.Username, "admin", "2fa_backup", detail, c)
	}
	a.setCookie(w, r, challengeCookie, "", -1)
	a.openSession(w, r, u, remember, detail, safeNext(next))
}

// ---------- Account page: password and two-factor authentication ----------

type accountPage struct {
	TOTP  store.TOTPStatus
	Key   string       // key to type by hand
	QR    template.URL // QR code PNG as a data: URL
	Codes []string     // backup codes, shown once
}

func (a *Admin) renderAccount(w http.ResponseWriter, r *http.Request, status int, errMsg string, codes []string) {
	s := current(r)
	st, err := a.Store.TOTPStatus(r.Context(), s.user.ID)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	d := accountPage{TOTP: st, Codes: codes}
	if st.Pending {
		key, uri, err := a.Store.TOTPPending(r.Context(), s.user.ID, s.user.Username)
		if err != nil {
			a.fail(w, r, err)
			return
		}
		code, err := qr.Encode(uri, qr.M)
		if err != nil {
			a.fail(w, r, err)
			return
		}
		d.Key, d.QR = key, template.URL("data:image/png;base64,"+base64.StdEncoding.EncodeToString(code.PNG()))
	}
	a.render(w, r, "account", status, view{Title: "Mon compte", Nav: "account", Error: errMsg, Data: d})
}

func (a *Admin) accountPage(w http.ResponseWriter, r *http.Request) {
	a.renderAccount(w, r, http.StatusOK, "", nil)
}

func (a *Admin) twoFASetup(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.TOTPSetup(r.Context(), current(r).user.ID); err != nil {
		a.accountError(w, r, err)
		return
	}
	redirect(w, r, "/admin/account", "")
}

func (a *Admin) twoFAEnable(w http.ResponseWriter, r *http.Request) {
	s := current(r)
	codes, err := a.Store.TOTPEnable(r.Context(), s.user.ID, r.FormValue("code"))
	if err != nil {
		a.accountError(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "2fa_enabled", "", a.client(r))
	a.renderAccount(w, r, http.StatusOK, "", codes)
}

// confirmSensitive requires the password and a valid code before disabling
// two-factor authentication or regenerating backup codes.
func (a *Admin) confirmSensitive(w http.ResponseWriter, r *http.Request) bool {
	s := current(r)
	c := a.client(r)
	if wait := a.Guard.Wait(adminGuardKey, c.IP); wait > 0 {
		a.accountError(w, r, &store.ValidationError{Msg: "trop de tentatives : réessayez dans quelques minutes"})
		return false
	}
	ok, err := a.Store.CheckPasswordOf(r.Context(), s.user.ID, r.FormValue("password"))
	if err != nil {
		a.fail(w, r, err)
		return false
	}
	if ok {
		_, err = a.Store.TOTPVerify(r.Context(), s.user.ID, r.FormValue("code"))
		if err != nil && !errors.Is(err, store.ErrTOTPInvalid) {
			a.fail(w, r, err)
			return false
		}
		ok = err == nil
	}
	if !ok {
		a.Guard.Failed(adminGuardKey, c.IP)
		a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "2fa_failed", "confirmation refusée", c)
		a.accountError(w, r, &store.ValidationError{Msg: "mot de passe ou code incorrect"})
		return false
	}
	a.Guard.Succeeded(adminGuardKey)
	return true
}

func (a *Admin) twoFADisable(w http.ResponseWriter, r *http.Request) {
	if !a.confirmSensitive(w, r) {
		return
	}
	s := current(r)
	if err := a.Store.TOTPDisable(r.Context(), s.user.ID); err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "2fa_disabled", "", a.client(r))
	redirect(w, r, "/admin/account", "2fa_disabled")
}

func (a *Admin) twoFABackup(w http.ResponseWriter, r *http.Request) {
	if !a.confirmSensitive(w, r) {
		return
	}
	s := current(r)
	codes, err := a.Store.TOTPRegenerateBackup(r.Context(), s.user.ID)
	if err != nil {
		a.fail(w, r, err)
		return
	}
	a.Store.LogAuthEvent(r.Context(), &s.user.ID, s.user.Username, "admin", "2fa_backup_regen", "", a.client(r))
	a.renderAccount(w, r, http.StatusOK, "", codes)
}

func (a *Admin) accountError(w http.ResponseWriter, r *http.Request, err error) {
	if msg, ok := validationMsg(err); ok {
		a.renderAccount(w, r, http.StatusUnprocessableEntity, msg, nil)
		return
	}
	a.fail(w, r, err)
}

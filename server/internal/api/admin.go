package api

import (
	"encoding/base64"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"time"

	"rsc.io/qr"

	"github.com/julienbrunet/mijote/server/internal/store"
)

// Administration from the app (FR-ADM-02): invitations and accounts, the same
// operations as the web admin. Reserved to the admin signed in on a device:
// personal API tokens cannot use them.

func (a *API) adminRoutes(mux *http.ServeMux) {
	for pattern, h := range map[string]http.HandlerFunc{
		"GET /api/v1/admin/invitations":              a.listInvitations,
		"POST /api/v1/admin/invitations":             a.createInvitation,
		"POST /api/v1/admin/invitations/{id}/revoke": a.revokeInvitation,
		"GET /api/v1/admin/users":                    a.listUsers,
		"PUT /api/v1/admin/users/{id}":               a.updateUser,
		"PUT /api/v1/admin/users/{id}/password":      a.resetPassword,
	} {
		mux.Handle(pattern, a.authenticate(a.requireAdmin(h)))
	}
}

func (a *API) requireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		s := current(r)
		if !s.user.IsAdmin() || s.sessionID == "" {
			problem(w, http.StatusForbidden, "forbidden", "réservé à l'administrateur, depuis l'application")
			return
		}
		next(w, r)
	}
}

// adminLog records an administration action in the login journal, as the web admin does.
func (a *API) adminLog(r *http.Request, target *store.User, event, detail string) {
	u := current(r).user
	id, name := &u.ID, u.Username
	if target != nil {
		id, name = &target.ID, target.Username
		detail += " par " + u.Username
	}
	a.Store.LogAuthEvent(r.Context(), id, name, "app", event, detail, a.client(r))
}

func invitationJSON(i store.Invitation) map[string]any {
	return map[string]any{"id": i.ID, "role": i.Role, "status": i.Status, "createdAt": i.CreatedAt,
		"expiresAt": i.ExpiresAt, "usedBy": i.UsedByName, "createdBy": i.CreatedByName}
}

// listInvitations returns the pending invitations, or all of them with ?all=1.
func (a *API) listInvitations(w http.ResponseWriter, r *http.Request) {
	list, err := a.Store.ListInvitations(r.Context(), r.URL.Query().Get("all") != "", 200)
	if err != nil {
		fail(w, r, err)
		return
	}
	out := make([]map[string]any, len(list))
	for i, inv := range list {
		out[i] = invitationJSON(inv)
	}
	writeJSON(w, http.StatusOK, map[string]any{"invitations": out, "durations": store.InvitationTTLs,
		"defaultDays": store.DefaultInvitationDays})
}

// createInvitation creates an invitation {"role": "member|readonly", "days": 1|7|30}
// and returns its clear code once, with the link to share and its QR code (PNG, base64).
func (a *API) createInvitation(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Role string `json:"role"`
		Days int    `json:"days"`
	}
	if !body(w, r, &in) {
		return
	}
	if in.Days == 0 {
		in.Days = store.DefaultInvitationDays
	}
	if !slices.Contains(store.InvitationTTLs, in.Days) {
		problem(w, http.StatusUnprocessableEntity, "validation_error", "durée de validité invalide (1, 7 ou 30 jours)")
		return
	}
	if in.Role == "" {
		in.Role = store.RoleMember
	}
	code, err := a.Store.CreateInvitation(r.Context(), current(r).user.ID, in.Role, time.Duration(in.Days)*24*time.Hour)
	if err != nil {
		recipeError(w, r, err)
		return
	}
	a.adminLog(r, nil, "invite_created", "rôle "+in.Role+", valable "+strconv.Itoa(in.Days)+" j (application)")
	server := a.Proxies.PublicURL(r)
	link := store.InvitationPageURL(server, code)
	out := map[string]any{"code": code, "link": link, "appLink": store.InvitationAppLink(server, code),
		"server": server, "role": in.Role, "days": in.Days, "qr": nil}
	if img, err := qr.Encode(link, qr.M); err == nil {
		out["qr"] = base64.StdEncoding.EncodeToString(img.PNG())
	}
	writeJSON(w, http.StatusCreated, out)
}

func (a *API) revokeInvitation(w http.ResponseWriter, r *http.Request) {
	if err := a.Store.RevokeInvitation(r.Context(), r.PathValue("id")); err != nil {
		fail(w, r, err)
		return
	}
	a.adminLog(r, nil, "invite_revoked", "application")
	w.WriteHeader(http.StatusNoContent)
}

func adminUserJSON(u store.User) map[string]any {
	return map[string]any{"id": u.ID, "username": u.Username, "displayName": u.DisplayName, "role": u.Role,
		"disabled": u.Disabled(), "createdAt": u.CreatedAt}
}

func (a *API) listUsers(w http.ResponseWriter, r *http.Request) {
	users, err := a.Store.ListUsers(r.Context())
	if err != nil {
		fail(w, r, err)
		return
	}
	out := make([]map[string]any, len(users))
	for i, u := range users {
		out[i] = adminUserJSON(u)
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": out})
}

// userError answers the errors of the account operations.
func userError(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, store.ErrNotFound) {
		problem(w, http.StatusNotFound, "not_found", "account not found")
		return
	}
	recipeError(w, r, err)
}

// updateUser changes a member: {"disabled": bool} and/or {"role": "member|readonly"}.
// Disabling closes its sessions. The admin account cannot be changed.
func (a *API) updateUser(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Disabled *bool  `json:"disabled"`
		Role     string `json:"role"`
	}
	if !body(w, r, &in) {
		return
	}
	id := r.PathValue("id")
	if in.Role != "" {
		u, err := a.Store.SetUserRole(r.Context(), id, in.Role)
		if err != nil {
			userError(w, r, err)
			return
		}
		a.adminLog(r, &u, "role_changed", "rôle modifié en "+in.Role)
	}
	if in.Disabled != nil {
		u, err := a.Store.SetUserDisabled(r.Context(), id, *in.Disabled)
		if err != nil {
			userError(w, r, err)
			return
		}
		event, detail := "account_enabled", "réactivé"
		if *in.Disabled {
			event, detail = "account_disabled", "désactivé"
		}
		a.adminLog(r, &u, event, detail)
	}
	u, err := a.Store.GetUser(r.Context(), id)
	if err != nil {
		userError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, adminUserJSON(u))
}

// resetPassword sets a new password for a member {"password": "…"} and closes its sessions.
func (a *API) resetPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
	}
	if !body(w, r, &in) {
		return
	}
	u, err := a.Store.ResetMemberPassword(r.Context(), r.PathValue("id"), in.Password)
	if err != nil {
		userError(w, r, err)
		return
	}
	a.adminLog(r, &u, "password_reset", "réinitialisé")
	w.WriteHeader(http.StatusNoContent)
}

package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// Sessions: app refresh tokens, web admin sessions and the login journal.

const (
	RefreshTTL       = 60 * 24 * time.Hour // refresh token lifetime
	AdminSessionTTL  = 12 * time.Hour      // admin session maximum lifetime
	AdminSessionIdle = 2 * time.Hour       // logout after inactivity

	// "Stay signed in" (checkbox at login): longer session.
	AdminRememberTTL  = 15 * 24 * time.Hour
	AdminRememberIdle = 5 * 24 * time.Hour
)

var (
	ErrInvalidRefresh = errors.New("invalid_refresh_token")
	// ErrRefreshReuse: an already rotated refresh token was presented (presumed theft).
	ErrRefreshReuse = errors.New("refresh_token_reuse")
)

// Client describes where a request comes from, for tracking.
type Client struct {
	IP        string
	UserAgent string
	Device    string // device name declared by the app
}

// LogAuthEvent appends to the login journal; failures are ignored on purpose.
func (s *Store) LogAuthEvent(ctx context.Context, userID *string, username, client, event, detail string, c Client) {
	_, _ = s.Pool.Exec(ctx, `INSERT INTO auth_event (user_id, username, client, event, detail, ip, user_agent)
		VALUES ($1, $2, $3, $4, nullif($5, ''), nullif($6, ''), nullif($7, ''))`,
		userID, username, client, event, detail, c.IP, c.UserAgent)
}

// ---------- App refresh tokens ----------

func (s *Store) insertRefresh(ctx context.Context, tx pgx.Tx, userID, familyID string, c Client) (string, error) {
	raw, hash := newToken()
	_, err := tx.Exec(ctx, `INSERT INTO refresh_token (user_id, token_hash, device_name, expires_at, family_id, created_ip, last_ip, user_agent)
		VALUES ($1, $2, nullif($3, ''), $4, $5, nullif($6, ''), nullif($6, ''), nullif($7, ''))`,
		userID, hash, c.Device, s.now().Add(RefreshTTL), familyID, c.IP, c.UserAgent)
	return raw, err
}

// StartAppSession creates a refresh token family (a device signing in).
func (s *Store) StartAppSession(ctx context.Context, userID string, c Client) (familyID, refresh string, err error) {
	err = s.tx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `SELECT gen_random_uuid()::text`).Scan(&familyID); err != nil {
			return err
		}
		refresh, err = s.insertRefresh(ctx, tx, userID, familyID, c)
		return err
	})
	return familyID, refresh, err
}

// RotateRefresh exchanges a refresh token for a new one in the same family.
// An already rotated token revokes the whole family and returns ErrRefreshReuse.
func (s *Store) RotateRefresh(ctx context.Context, raw string, c Client) (u User, familyID, refresh string, err error) {
	var reuse bool
	err = s.tx(ctx, func(tx pgx.Tx) error {
		var id string
		var revokedAt *time.Time
		var reason *string
		var expires time.Time
		err := tx.QueryRow(ctx, `SELECT t.id::text, t.family_id::text, t.revoked_at, t.revoke_reason, t.expires_at,
				u.id::text, u.username, u.display_name, u.role::text, u.disabled_at, u.created_at
			FROM refresh_token t JOIN app_user u ON u.id = t.user_id WHERE t.token_hash = $1 FOR UPDATE OF t`, hashToken(raw)).
			Scan(&id, &familyID, &revokedAt, &reason, &expires,
				&u.ID, &u.Username, &u.DisplayName, &u.Role, &u.DisabledAt, &u.CreatedAt)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrInvalidRefresh
		} else if err != nil {
			return err
		}
		switch {
		case revokedAt != nil && reason != nil && *reason == "rotated":
			reuse = true
			return nil
		case revokedAt != nil || s.now().After(expires):
			return ErrInvalidRefresh
		case u.Disabled():
			return ErrAccountDisabled
		}
		if refresh, err = s.insertRefresh(ctx, tx, u.ID, familyID, c); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE refresh_token SET revoked_at = now(), revoke_reason = 'rotated', last_used_at = now(),
			replaced_by = (SELECT id FROM refresh_token WHERE token_hash = $2) WHERE id = $1`, id, hashToken(refresh))
		return err
	})
	if err == nil && reuse {
		_ = s.RevokeFamily(ctx, familyID, "reuse")
		return u, familyID, "", ErrRefreshReuse
	}
	return u, familyID, refresh, err
}

// RevokeFamily revokes every still valid token of a device session.
func (s *Store) RevokeFamily(ctx context.Context, familyID, reason string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE refresh_token SET revoked_at = now(), revoke_reason = $2
		WHERE family_id::text = $1 AND revoked_at IS NULL`, familyID, reason)
	return err
}

// AppSessionActive reports whether the device session is still valid for an active
// account. Checked on every request so that a revocation takes effect immediately.
func (s *Store) AppSessionActive(ctx context.Context, userID, familyID string) (User, bool, error) {
	u, err := scanUser(s.Pool.QueryRow(ctx, `SELECT `+userCols+` FROM app_user u WHERE u.id::text = $1 AND u.disabled_at IS NULL
		AND EXISTS (SELECT 1 FROM refresh_token t WHERE t.family_id::text = $2 AND t.user_id = u.id
		            AND t.revoked_at IS NULL AND t.expires_at > now())`, userID, familyID))
	if errors.Is(err, ErrNotFound) {
		return u, false, nil
	}
	return u, err == nil, err
}

// ---------- Web admin sessions ----------

// StartAdminSession opens an admin session; remember extends it. It returns the
// cookie value and the cookie lifetime.
func (s *Store) StartAdminSession(ctx context.Context, userID string, c Client, remember bool) (string, time.Duration, error) {
	ttl, idle := AdminSessionTTL, AdminSessionIdle
	if remember {
		ttl, idle = AdminRememberTTL, AdminRememberIdle
	}
	raw, hash := newToken()
	_, err := s.Pool.Exec(ctx, `INSERT INTO admin_session (user_id, token_hash, expires_at, idle_seconds, ip, user_agent)
		VALUES ($1, $2, $3, $4, nullif($5, ''), nullif($6, ''))`,
		userID, hash, s.now().Add(ttl), int(idle.Seconds()), c.IP, c.UserAgent)
	return raw, ttl, err
}

// AdminSession returns the account of a valid admin session and records its activity.
func (s *Store) AdminSession(ctx context.Context, raw string, c Client) (u User, sessionID string, err error) {
	u, err = scanUser(s.Pool.QueryRow(ctx, `UPDATE admin_session a SET last_seen_at = now(), ip = coalesce(nullif($2, ''), a.ip)
		FROM app_user u
		WHERE a.token_hash = $1 AND u.id = a.user_id AND a.revoked_at IS NULL AND a.expires_at > now()
		  AND a.last_seen_at > now() - a.idle_seconds * interval '1 second' AND u.disabled_at IS NULL AND u.role = 'admin'
		RETURNING u.id::text, u.username, u.display_name, u.role::text, u.disabled_at, u.created_at, a.id::text`,
		hashToken(raw), c.IP), &sessionID)
	return u, sessionID, err
}

func (s *Store) RevokeAdminSession(ctx context.Context, id, reason string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE admin_session SET revoked_at = now(), revoke_reason = $2
		WHERE id::text = $1 AND revoked_at IS NULL`, id, reason)
	return err
}

// RevokeUserSessions revokes every session (app and admin) of an account, except exceptAdmin.
func (s *Store) RevokeUserSessions(ctx context.Context, userID, reason, exceptAdmin string) error {
	return s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `UPDATE refresh_token SET revoked_at = now(), revoke_reason = $2
			WHERE user_id = $1 AND revoked_at IS NULL`, userID, reason); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `UPDATE admin_session SET revoked_at = now(), revoke_reason = $2
			WHERE user_id = $1 AND revoked_at IS NULL AND id::text <> $3`, userID, reason, exceptAdmin)
		return err
	})
}

// PurgeAuth deletes old traces: journal older than 180 days, tokens and sessions expired for 30 days.
func (s *Store) PurgeAuth(ctx context.Context) error {
	for _, q := range []string{
		`DELETE FROM auth_event WHERE at < now() - interval '180 days'`,
		`DELETE FROM refresh_token WHERE expires_at < now() - interval '30 days'`,
		`DELETE FROM admin_session WHERE expires_at < now() - interval '30 days'`,
		`DELETE FROM api_token WHERE expires_at < now() - interval '30 days'`,
	} {
		if _, err := s.Pool.Exec(ctx, q); err != nil {
			return err
		}
	}
	return nil
}

// ---------- Tracking (web admin) ----------

// AppSession summarizes a device session (refresh token family).
type AppSession struct {
	FamilyID, UserID, Username string
	Device, IP, UserAgent      *string
	CreatedAt, LastUsedAt      time.Time
	Active                     bool
	RevokeReason               *string
	Rotations                  int
}

// ListAppSessions lists device sessions, active first.
func (s *Store) ListAppSessions(ctx context.Context, includeEnded bool, limit int) ([]AppSession, error) {
	rows, err := s.Pool.Query(ctx, `SELECT t.family_id::text, u.id::text, u.username,
			(array_agg(t.device_name ORDER BY t.created_at DESC))[1],
			(array_agg(t.last_ip ORDER BY t.created_at DESC))[1],
			(array_agg(t.user_agent ORDER BY t.created_at DESC))[1],
			min(t.created_at), max(coalesce(t.last_used_at, t.created_at)),
			bool_or(t.revoked_at IS NULL AND t.expires_at > now()),
			(array_agg(t.revoke_reason ORDER BY t.revoked_at DESC NULLS LAST) FILTER (WHERE t.revoke_reason IS DISTINCT FROM 'rotated'))[1],
			count(*)::int - 1
		FROM refresh_token t JOIN app_user u ON u.id = t.user_id
		GROUP BY t.family_id, u.id, u.username
		HAVING $1 OR bool_or(t.revoked_at IS NULL AND t.expires_at > now())
		ORDER BY 9 DESC, 8 DESC
		LIMIT $2`, includeEnded, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (AppSession, error) {
		var a AppSession
		err := r.Scan(&a.FamilyID, &a.UserID, &a.Username, &a.Device, &a.IP, &a.UserAgent, &a.CreatedAt, &a.LastUsedAt,
			&a.Active, &a.RevokeReason, &a.Rotations)
		return a, err
	})
}

type AdminSessionInfo struct {
	ID, Username          string
	IP, UserAgent         *string
	CreatedAt, LastSeenAt time.Time
	Active                bool
	RevokeReason          *string
}

func (s *Store) ListAdminSessions(ctx context.Context, includeEnded bool, limit int) ([]AdminSessionInfo, error) {
	rows, err := s.Pool.Query(ctx, `SELECT a.id::text, u.username, a.ip, a.user_agent, a.created_at, a.last_seen_at,
			a.revoked_at IS NULL AND a.expires_at > now() AND a.last_seen_at > now() - a.idle_seconds * interval '1 second' AS active,
			a.revoke_reason
		FROM admin_session a JOIN app_user u ON u.id = a.user_id
		WHERE $1 OR (a.revoked_at IS NULL AND a.expires_at > now() AND a.last_seen_at > now() - a.idle_seconds * interval '1 second')
		ORDER BY a.last_seen_at DESC LIMIT $2`, includeEnded, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (AdminSessionInfo, error) {
		var a AdminSessionInfo
		err := r.Scan(&a.ID, &a.Username, &a.IP, &a.UserAgent, &a.CreatedAt, &a.LastSeenAt, &a.Active, &a.RevokeReason)
		return a, err
	})
}

type AuthEvent struct {
	At                      time.Time
	Username, Client, Event string
	Detail, IP, UserAgent   *string
}

// ListAuthEvents returns the login journal, newest first.
func (s *Store) ListAuthEvents(ctx context.Context, failuresOnly bool, limit int) ([]AuthEvent, error) {
	rows, err := s.Pool.Query(ctx, `SELECT at, username, client, event, detail, ip, user_agent FROM auth_event
		WHERE NOT $1 OR event NOT IN ('login', 'logout', '2fa_enabled')
		ORDER BY at DESC, id DESC LIMIT $2`, failuresOnly, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (AuthEvent, error) {
		var e AuthEvent
		err := r.Scan(&e.At, &e.Username, &e.Client, &e.Event, &e.Detail, &e.IP, &e.UserAgent)
		return e, err
	})
}

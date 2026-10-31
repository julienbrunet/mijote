package store

import (
	"context"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
)

// Personal API tokens: long-lived bearer tokens created by the admin.

// APITokenPrefix starts every API token, so it is told apart from a JWT access token.
const APITokenPrefix = "mjt_"

// API token scopes: "read" allows GET and HEAD only, "write" allows everything.
const (
	ScopeRead  = "read"
	ScopeWrite = "write"
)

// APITokenDays are the lifetimes an admin can pick, in days.
var APITokenDays = []int{7, 30, 90, 365}

// DefaultAPITokenDays is the preselected lifetime.
const DefaultAPITokenDays = 90

const maxTokenNameLen = 60

// APIToken is a row of the api_token table, without the secret (only its hash is stored).
type APIToken struct {
	ID, UserID, Username, Name, Prefix, Scope string
	CreatedAt, ExpiresAt                      time.Time
	LastUsedAt, RevokedAt                     *time.Time
	LastIP                                    *string
	Status                                    string // active, revoked or expired
}

// CreateAPIToken creates a token for the user and returns its clear value,
// which is not stored and cannot be shown again.
func (s *Store) CreateAPIToken(ctx context.Context, userID, name, scope string, ttl time.Duration) (string, error) {
	name = strings.TrimSpace(name)
	switch {
	case name == "":
		return "", invalid("le nom du jeton est obligatoire")
	case utf8.RuneCountInString(name) > maxTokenNameLen:
		return "", invalid("le nom du jeton est trop long (60 caractères maximum)")
	case scope != ScopeRead && scope != ScopeWrite:
		return "", invalid("l'accès demandé est inconnu")
	}
	secret, _ := newToken()
	raw := APITokenPrefix + secret
	hash := hashToken(raw)
	_, err := s.Pool.Exec(ctx, `INSERT INTO api_token (user_id, name, token_hash, prefix, scope, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6)`, userID, name, hash, raw[:len(APITokenPrefix)+4], scope, s.now().Add(ttl))
	return raw, err
}

// ListAPITokens returns the tokens, newest first. Ended ones (revoked, expired)
// are included only when includeEnded is set.
func (s *Store) ListAPITokens(ctx context.Context, includeEnded bool, limit int) ([]APIToken, error) {
	rows, err := s.Pool.Query(ctx, `SELECT t.id::text, t.user_id::text, u.username, t.name, t.prefix, t.scope,
			t.created_at, t.expires_at, t.last_used_at, t.revoked_at, t.last_ip,
			CASE WHEN t.revoked_at IS NOT NULL THEN 'revoked'
			     WHEN t.expires_at <= $3 THEN 'expired' ELSE 'active' END
		FROM api_token t JOIN app_user u ON u.id = t.user_id
		WHERE $1 OR (t.revoked_at IS NULL AND t.expires_at > $3)
		ORDER BY t.created_at DESC LIMIT $2`, includeEnded, limit, s.now())
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (APIToken, error) {
		var t APIToken
		err := r.Scan(&t.ID, &t.UserID, &t.Username, &t.Name, &t.Prefix, &t.Scope,
			&t.CreatedAt, &t.ExpiresAt, &t.LastUsedAt, &t.RevokedAt, &t.LastIP, &t.Status)
		return t, err
	})
}

// RevokeAPIToken cancels a token; it stops working immediately.
func (s *Store) RevokeAPIToken(ctx context.Context, id string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE api_token SET revoked_at = now() WHERE id::text = $1 AND revoked_at IS NULL`, id)
	return err
}

// APITokenUser resolves a clear API token to its (active) account and scope.
// ok is false for an unknown, revoked or expired token or a disabled account.
func (s *Store) APITokenUser(ctx context.Context, raw, ip string) (u User, scope string, ok bool, err error) {
	var id, userID string
	var last *time.Time
	err = s.Pool.QueryRow(ctx, `SELECT id::text, user_id::text, scope, last_used_at FROM api_token
		WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > $2`, hashToken(raw), s.now()).
		Scan(&id, &userID, &scope, &last)
	if err == pgx.ErrNoRows {
		return u, "", false, nil
	} else if err != nil {
		return u, "", false, err
	}
	u, err = s.GetUser(ctx, userID)
	if err != nil || u.Disabled() {
		return u, "", false, err
	}
	// Record the use at most once a minute, to avoid a write on every request.
	if last == nil || s.now().Sub(*last) > time.Minute {
		_, _ = s.Pool.Exec(ctx, `UPDATE api_token SET last_used_at = now(), last_ip = nullif($2, '') WHERE id::text = $1`, id, ip)
	}
	return u, scope, true, nil
}

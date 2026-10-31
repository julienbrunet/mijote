package store

import (
	"context"
	"crypto/rand"
	"encoding/base32"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/julienbrunet/mijote/server/internal/auth"
)

// Invitations (FR-ONB-02..04) and account management (FR-ADM-02).

// ErrInvalidInvitation is returned for an unknown, used, revoked or expired
// code; the cases are not told apart so the answer reveals nothing.
var ErrInvalidInvitation = errors.New("invalid_invitation")

// InvitationTTLs are the lifetimes an admin can pick, in days.
var InvitationTTLs = []int{1, 7, 30}

// DefaultInvitationDays is the preselected lifetime.
const DefaultInvitationDays = 7

const maxUsernameLen = 50

var codeEncoding = base32.StdEncoding.WithPadding(base32.NoPadding)

// newInvitationCode returns a 128-bit random code, easy to type (base32, lower case).
func newInvitationCode() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return strings.ToLower(codeEncoding.EncodeToString(b))
}

func normalizeCode(code string) string {
	return strings.ToLower(strings.TrimSpace(code))
}

// Invitation is a row of the invitation table, without the code (only its hash is stored).
type Invitation struct {
	ID                   string
	CreatedBy            string
	CreatedAt, ExpiresAt time.Time
	UsedBy               *string
	UsedAt, RevokedAt    *time.Time
	UsedByName           *string
	Status               string // pending, used, revoked or expired
	CreatedByName        string
}

// CreateInvitation creates a single-use invitation for a new member and returns
// its clear code, which is not stored and cannot be shown again.
func (s *Store) CreateInvitation(ctx context.Context, adminID string, ttl time.Duration) (string, error) {
	code := newInvitationCode()
	_, err := s.Pool.Exec(ctx, `INSERT INTO invitation (code_hash, created_by, expires_at) VALUES ($1, $2, $3)`,
		hashToken(code), adminID, s.now().Add(ttl))
	return code, err
}

// ListInvitations returns the invitations, newest first. Ended ones (used,
// revoked, expired) are included only when includeEnded is set.
func (s *Store) ListInvitations(ctx context.Context, includeEnded bool, limit int) ([]Invitation, error) {
	rows, err := s.Pool.Query(ctx, `SELECT i.id::text, i.created_by::text, c.username, i.created_at, i.expires_at,
			i.used_by::text, u.username, i.used_at, i.revoked_at,
			CASE WHEN i.used_at IS NOT NULL THEN 'used'
			     WHEN i.revoked_at IS NOT NULL THEN 'revoked'
			     WHEN i.expires_at <= $3 THEN 'expired' ELSE 'pending' END
		FROM invitation i JOIN app_user c ON c.id = i.created_by LEFT JOIN app_user u ON u.id = i.used_by
		WHERE $1 OR (i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > $3)
		ORDER BY i.created_at DESC LIMIT $2`, includeEnded, limit, s.now())
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Invitation, error) {
		var i Invitation
		err := r.Scan(&i.ID, &i.CreatedBy, &i.CreatedByName, &i.CreatedAt, &i.ExpiresAt,
			&i.UsedBy, &i.UsedByName, &i.UsedAt, &i.RevokedAt, &i.Status)
		return i, err
	})
}

// RevokeInvitation cancels a pending invitation; a used one is left untouched.
func (s *Store) RevokeInvitation(ctx context.Context, id string) error {
	_, err := s.Pool.Exec(ctx, `UPDATE invitation SET revoked_at = now()
		WHERE id::text = $1 AND used_at IS NULL AND revoked_at IS NULL`, id)
	return err
}

func checkNewUsername(username string) error {
	switch {
	case username == "":
		return invalid("l'identifiant est obligatoire")
	case utf8.RuneCountInString(username) > maxUsernameLen:
		return invalid("l'identifiant est trop long (50 caractères maximum)")
	case strings.ContainsFunc(username, func(r rune) bool { return r <= ' ' || r == 0x7f }):
		return invalid("l'identifiant ne peut pas contenir d'espace ni de caractère de contrôle")
	}
	return nil
}

func isUniqueViolation(err error) bool {
	var pg *pgconn.PgError
	return errors.As(err, &pg) && pg.Code == "23505"
}

// RedeemInvitation creates the member account for a valid code and consumes the
// code, atomically. It returns ErrInvalidInvitation for a bad code and a
// ValidationError when the identifier or password is refused (the code stays usable).
func (s *Store) RedeemInvitation(ctx context.Context, code, username, displayName, password string) (User, error) {
	username, displayName = strings.TrimSpace(username), strings.TrimSpace(displayName)
	if err := checkNewUsername(username); err != nil {
		return User{}, err
	}
	if displayName == "" {
		displayName = username
	}
	if utf8.RuneCountInString(displayName) > 100 {
		return User{}, invalid("le nom affiché est trop long (100 caractères maximum)")
	}
	if err := checkNewPassword(password); err != nil {
		return User{}, err
	}
	hash := auth.HashPassword(password) // slow: done outside the transaction
	var u User
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var id string
		err := tx.QueryRow(ctx, `SELECT id::text FROM invitation
			WHERE code_hash = $1 AND used_at IS NULL AND revoked_at IS NULL AND expires_at > $2
			FOR UPDATE`, hashToken(normalizeCode(code)), s.now()).Scan(&id)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrInvalidInvitation
		} else if err != nil {
			return err
		}
		u, err = scanUser(tx.QueryRow(ctx, `INSERT INTO app_user (username, display_name, password_hash, role)
			VALUES ($1, $2, $3, 'member') RETURNING `+userCols, username, displayName, hash))
		if isUniqueViolation(err) {
			return invalid("cet identifiant est déjà pris")
		} else if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE invitation SET used_by = $2, used_at = now() WHERE id = $1`, id, u.ID)
		return err
	})
	return u, err
}

// ---------- Accounts ----------

// ListUsers returns every account, the admin first.
func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := s.Pool.Query(ctx, `SELECT `+userCols+` FROM app_user ORDER BY role, lower(username)`)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (User, error) { return scanUser(r) })
}

// SetUserDisabled deactivates or reactivates a member. Deactivating closes all
// its sessions. The admin account cannot be disabled.
func (s *Store) SetUserDisabled(ctx context.Context, id string, disabled bool) (User, error) {
	u, err := s.GetUser(ctx, id)
	if err != nil {
		return u, err
	}
	if u.IsAdmin() {
		return u, invalid("le compte administrateur ne peut pas être désactivé")
	}
	if _, err := s.Pool.Exec(ctx, `UPDATE app_user SET disabled_at = CASE WHEN $2 THEN coalesce(disabled_at, now()) END
		WHERE id = $1`, id, disabled); err != nil {
		return u, err
	}
	if disabled {
		err = s.RevokeUserSessions(ctx, id, "disabled", "")
	}
	return u, err
}

// ResetMemberPassword sets a new password chosen by the admin for a member and
// closes all its sessions. The admin resets their own password from "Mon compte".
func (s *Store) ResetMemberPassword(ctx context.Context, id, password string) (User, error) {
	u, err := s.GetUser(ctx, id)
	if err != nil {
		return u, err
	}
	if u.IsAdmin() {
		return u, invalid("le mot de passe de l'administrateur se change depuis « Mon compte »")
	}
	if err := checkNewPassword(password); err != nil {
		return u, err
	}
	if _, err := s.Pool.Exec(ctx, `UPDATE app_user SET password_hash = $2 WHERE id = $1`, id, auth.HashPassword(password)); err != nil {
		return u, err
	}
	return u, s.RevokeUserSessions(ctx, id, "password", "")
}

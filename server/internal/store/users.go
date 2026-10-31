package store

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/julienbrunet/mijote/server/internal/auth"
)

var (
	ErrInvalidCredentials = errors.New("invalid_credentials")
	ErrAccountDisabled    = errors.New("account_disabled")
	ErrAlreadySetUp       = errors.New("already_set_up")
)

// User is an account.
type User struct {
	ID, Username, DisplayName, Role string
	DisabledAt                      *time.Time
	CreatedAt                       time.Time
}

func (u User) IsAdmin() bool  { return u.Role == "admin" }
func (u User) Disabled() bool { return u.DisabledAt != nil }

const userCols = `id::text, username, display_name, role::text, disabled_at, created_at`

func scanUser(row pgx.Row, extra ...any) (User, error) {
	var u User
	err := row.Scan(append([]any{&u.ID, &u.Username, &u.DisplayName, &u.Role, &u.DisabledAt, &u.CreatedAt}, extra...)...)
	return u, notFound(err)
}

// dummyHash keeps the response time constant when the username does not exist.
var dummyHash = auth.HashPassword("mijote")

// CheckCredentials verifies a username and password.
func (s *Store) CheckCredentials(ctx context.Context, username, password string) (User, error) {
	var hash string
	u, err := scanUser(s.Pool.QueryRow(ctx, `SELECT `+userCols+`, password_hash FROM app_user
		WHERE lower(username) = lower($1)`, username), &hash)
	if errors.Is(err, ErrNotFound) {
		auth.CheckPassword(dummyHash, password)
		return u, ErrInvalidCredentials
	} else if err != nil {
		return u, err
	}
	if !auth.CheckPassword(hash, password) {
		return u, ErrInvalidCredentials
	}
	if u.Disabled() {
		return u, ErrAccountDisabled
	}
	return u, nil
}

// Admin returns the instance's single admin account.
func (s *Store) Admin(ctx context.Context) (User, error) {
	return scanUser(s.Pool.QueryRow(ctx, `SELECT `+userCols+` FROM app_user WHERE role = 'admin'`))
}

func (s *Store) GetUser(ctx context.Context, id string) (User, error) {
	return scanUser(s.Pool.QueryRow(ctx, `SELECT `+userCols+` FROM app_user WHERE id = $1`, id))
}

func (s *Store) UserByUsername(ctx context.Context, username string) (User, error) {
	return scanUser(s.Pool.QueryRow(ctx, `SELECT `+userCols+` FROM app_user WHERE lower(username) = lower($1)`, username))
}

// NeedsSetup reports whether the instance has no account yet (setup wizard, FR-ONB-01).
func (s *Store) NeedsSetup(ctx context.Context) (bool, error) {
	var exists bool
	err := s.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM app_user)`).Scan(&exists)
	return !exists, err
}

func checkNewPassword(pw string) error {
	if utf8.RuneCountInString(pw) < auth.MinPasswordLength {
		return invalid("le mot de passe doit faire au moins 8 caractères")
	}
	return nil
}

// CreateFirstAdmin creates the admin account, only while the instance has no account.
func (s *Store) CreateFirstAdmin(ctx context.Context, username, displayName, password string) (User, error) {
	username, displayName = strings.TrimSpace(username), strings.TrimSpace(displayName)
	if username == "" {
		return User{}, invalid("l'identifiant est obligatoire")
	}
	if displayName == "" {
		displayName = username
	}
	if err := checkNewPassword(password); err != nil {
		return User{}, err
	}
	u, err := scanUser(s.Pool.QueryRow(ctx, `INSERT INTO app_user (username, display_name, password_hash, role)
		SELECT $1, $2, $3, 'admin' WHERE NOT EXISTS (SELECT 1 FROM app_user)
		RETURNING `+userCols, username, displayName, auth.HashPassword(password)))
	if errors.Is(err, ErrNotFound) {
		return u, ErrAlreadySetUp
	}
	return u, err
}

// CheckPasswordOf verifies the password of an identified account.
func (s *Store) CheckPasswordOf(ctx context.Context, userID, password string) (bool, error) {
	var hash string
	if err := s.Pool.QueryRow(ctx, `SELECT password_hash FROM app_user WHERE id = $1`, userID).Scan(&hash); err != nil {
		return false, notFound(err)
	}
	return auth.CheckPassword(hash, password), nil
}

// ChangeOwnPassword changes a password after checking the current one, and
// closes the account's other sessions (keepAdminSession is kept).
func (s *Store) ChangeOwnPassword(ctx context.Context, userID, current, next, keepAdminSession string) error {
	ok, err := s.CheckPasswordOf(ctx, userID, current)
	if err != nil {
		return err
	}
	if !ok {
		return invalid("mot de passe actuel incorrect")
	}
	if err := checkNewPassword(next); err != nil {
		return err
	}
	if next == current {
		return invalid("le nouveau mot de passe doit être différent de l'actuel")
	}
	if _, err := s.Pool.Exec(ctx, `UPDATE app_user SET password_hash = $2 WHERE id = $1`, userID, auth.HashPassword(next)); err != nil {
		return err
	}
	return s.RevokeUserSessions(ctx, userID, "password", keepAdminSession)
}

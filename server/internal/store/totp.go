package store

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"strings"

	"github.com/jackc/pgx/v5"

	"github.com/julienbrunet/mijote/server/internal/auth"
)

// TOTP two-factor authentication for the web admin. Secrets are sealed with
// AES-GCM using a key kept in server_secret: this only protects against a
// partial leak of the table, not a full database leak.

const backupCodeCount = 10

var ErrTOTPInvalid = errors.New("totp_invalid")

// TOTPStatus is the two-factor state of an account.
type TOTPStatus struct {
	Enabled    bool
	Pending    bool // secret generated but not confirmed yet
	BackupLeft int
}

func (s *Store) totpAEAD(ctx context.Context) (cipher.AEAD, error) {
	k, err := s.Secret(ctx, "totp_key", 32)
	if err != nil {
		return nil, err
	}
	blk, err := aes.NewCipher(k)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(blk)
}

func (s *Store) seal(ctx context.Context, secret []byte) ([]byte, error) {
	g, err := s.totpAEAD(ctx)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, g.NonceSize())
	_, _ = rand.Read(nonce)
	return g.Seal(nonce, nonce, secret, nil), nil
}

func (s *Store) open(ctx context.Context, sealed []byte) ([]byte, error) {
	g, err := s.totpAEAD(ctx)
	if err != nil {
		return nil, err
	}
	if len(sealed) < g.NonceSize() {
		return nil, errors.New("unreadable TOTP secret")
	}
	return g.Open(nil, sealed[:g.NonceSize()], sealed[g.NonceSize():], nil)
}

func (s *Store) TOTPStatus(ctx context.Context, userID string) (TOTPStatus, error) {
	var st TOTPStatus
	err := s.Pool.QueryRow(ctx, `SELECT enabled_at IS NOT NULL,
			(SELECT count(*)::int FROM user_totp_backup b WHERE b.user_id = t.user_id AND b.used_at IS NULL)
		FROM user_totp t WHERE user_id = $1`, userID).Scan(&st.Enabled, &st.BackupLeft)
	if errors.Is(err, pgx.ErrNoRows) {
		return st, nil
	}
	st.Pending = !st.Enabled
	return st, err
}

// TOTPSetup generates a secret awaiting confirmation (replacing an unconfirmed
// one). Refused when two-factor authentication is already enabled.
func (s *Store) TOTPSetup(ctx context.Context, userID string) error {
	if st, err := s.TOTPStatus(ctx, userID); err != nil {
		return err
	} else if st.Enabled {
		return invalid("la double authentification est déjà activée")
	}
	secret := make([]byte, 20)
	_, _ = rand.Read(secret)
	sealed, err := s.seal(ctx, secret)
	if err != nil {
		return err
	}
	_, err = s.Pool.Exec(ctx, `INSERT INTO user_totp (user_id, secret) VALUES ($1, $2)
		ON CONFLICT (user_id) DO UPDATE SET secret = EXCLUDED.secret, created_at = now()`, userID, sealed)
	return err
}

func (s *Store) totpSecret(ctx context.Context, userID string, enabled bool) ([]byte, error) {
	var sealed []byte
	err := s.Pool.QueryRow(ctx, `SELECT secret FROM user_totp WHERE user_id = $1 AND (enabled_at IS NOT NULL) = $2`,
		userID, enabled).Scan(&sealed)
	if err != nil {
		return nil, notFound(err)
	}
	return s.open(ctx, sealed)
}

// TOTPPending returns the key and otpauth URI of an ongoing setup.
func (s *Store) TOTPPending(ctx context.Context, userID, account string) (key, uri string, err error) {
	secret, err := s.totpSecret(ctx, userID, false)
	if err != nil {
		return "", "", err
	}
	return auth.Base32.EncodeToString(secret), auth.TOTPURI(secret, account), nil
}

// TOTPEnable confirms the setup with a first code and returns the backup codes (shown once).
func (s *Store) TOTPEnable(ctx context.Context, userID, code string) ([]string, error) {
	secret, err := s.totpSecret(ctx, userID, false)
	if errors.Is(err, ErrNotFound) {
		return nil, invalid("aucune activation en cours : recommencez")
	} else if err != nil {
		return nil, err
	}
	step := auth.TOTPMatch(secret, code, s.now())
	if step == 0 {
		return nil, invalid("code incorrect : vérifiez l'heure du téléphone et réessayez")
	}
	var codes []string
	err = s.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `UPDATE user_totp SET enabled_at = now(), last_step = $2 WHERE user_id = $1`, userID, step); err != nil {
			return err
		}
		codes, err = insertBackupCodes(ctx, tx, userID)
		return err
	})
	return codes, err
}

func insertBackupCodes(ctx context.Context, tx pgx.Tx, userID string) ([]string, error) {
	if _, err := tx.Exec(ctx, `DELETE FROM user_totp_backup WHERE user_id = $1`, userID); err != nil {
		return nil, err
	}
	codes := make([]string, backupCodeCount)
	for i := range codes {
		b := make([]byte, 6)
		_, _ = rand.Read(b)
		raw := strings.ToLower(auth.Base32.EncodeToString(b)) // 10 characters
		codes[i] = raw[:5] + "-" + raw[5:]
		if _, err := tx.Exec(ctx, `INSERT INTO user_totp_backup (user_id, code_hash) VALUES ($1, $2)`, userID, backupHash(codes[i])); err != nil {
			return nil, err
		}
	}
	return codes, nil
}

func backupHash(code string) []byte {
	code = strings.ReplaceAll(strings.ToLower(strings.TrimSpace(code)), "-", "")
	h := sha256.Sum256([]byte("totp-backup:" + code))
	return h[:]
}

// TOTPVerify checks a TOTP code (single use per time step) or a backup code.
func (s *Store) TOTPVerify(ctx context.Context, userID, code string) (usedBackup bool, err error) {
	secret, err := s.totpSecret(ctx, userID, true)
	if errors.Is(err, ErrNotFound) {
		return false, ErrTOTPInvalid
	} else if err != nil {
		return false, err
	}
	if step := auth.TOTPMatch(secret, code, s.now()); step != 0 {
		// Anti-replay: the step must be strictly newer than the last accepted one.
		tag, err := s.Pool.Exec(ctx, `UPDATE user_totp SET last_step = $2 WHERE user_id = $1 AND last_step < $2`, userID, step)
		if err != nil {
			return false, err
		}
		if tag.RowsAffected() == 1 {
			return false, nil
		}
		return false, ErrTOTPInvalid
	}
	if clean := strings.ReplaceAll(strings.TrimSpace(code), "-", ""); len(clean) == 10 {
		tag, err := s.Pool.Exec(ctx, `UPDATE user_totp_backup SET used_at = now()
			WHERE user_id = $1 AND code_hash = $2 AND used_at IS NULL`, userID, backupHash(code))
		if err != nil {
			return false, err
		}
		if tag.RowsAffected() == 1 {
			return true, nil
		}
	}
	return false, ErrTOTPInvalid
}

// TOTPRegenerateBackup replaces the backup codes (the caller checks a code first).
func (s *Store) TOTPRegenerateBackup(ctx context.Context, userID string) ([]string, error) {
	var codes []string
	err := s.tx(ctx, func(tx pgx.Tx) error {
		var err error
		codes, err = insertBackupCodes(ctx, tx, userID)
		return err
	})
	return codes, err
}

// TOTPDisable removes the two-factor authentication of an account.
func (s *Store) TOTPDisable(ctx context.Context, userID string) error {
	_, err := s.Pool.Exec(ctx, `DELETE FROM user_totp WHERE user_id = $1`, userID)
	return err
}

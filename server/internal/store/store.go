// Package store holds the PostgreSQL queries of the backend.
package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrNotFound = errors.New("not_found")

// ValidationError carries a message meant for the user.
type ValidationError struct{ Msg string }

func (e *ValidationError) Error() string { return e.Msg }

func invalid(msg string) error { return &ValidationError{Msg: msg} }

// Store wraps the connection pool.
type Store struct {
	Pool *pgxpool.Pool
	Now  func() time.Time // for tests; defaults to time.Now
}

func New(pool *pgxpool.Pool) *Store { return &Store{Pool: pool} }

func (s *Store) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Store) tx(ctx context.Context, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.Pool, fn)
}

func notFound(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	return err
}

// newToken returns a random 256-bit token and its SHA-256 hash (only the hash is stored).
func newToken() (raw string, hash []byte) {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	raw = base64.RawURLEncoding.EncodeToString(b)
	return raw, hashToken(raw)
}

func hashToken(raw string) []byte {
	h := sha256.Sum256([]byte(raw))
	return h[:]
}

// Secret returns the named server secret, created randomly on first use.
func (s *Store) Secret(ctx context.Context, name string, size int) ([]byte, error) {
	b := make([]byte, size)
	_, _ = rand.Read(b)
	var v []byte
	err := s.Pool.QueryRow(ctx, `INSERT INTO server_secret (name, value) VALUES ($1, $2)
		ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING value`, name, b).Scan(&v)
	return v, err
}

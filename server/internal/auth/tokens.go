// Package auth holds the authentication building blocks: JWT access tokens,
// password hashing, TOTP, login rate limiting and client identification
// behind a reverse proxy.
package auth

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// AccessTTL is the lifetime of an access token.
const AccessTTL = 15 * time.Minute

const issuer = "mijote"

// Tokens signs and verifies access tokens (HS256).
type Tokens struct {
	Key []byte
	Now func() time.Time
}

// Claims: sub = user, sid = device session (refresh token family).
type Claims struct {
	SessionID string `json:"sid"`
	jwt.RegisteredClaims
}

func (t *Tokens) now() time.Time {
	if t.Now != nil {
		return t.Now()
	}
	return time.Now()
}

// Issue creates an access token.
func (t *Tokens) Issue(userID, sessionID string) (string, error) {
	now := t.now()
	c := Claims{SessionID: sessionID, RegisteredClaims: jwt.RegisteredClaims{
		Subject: userID, Issuer: issuer,
		IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(AccessTTL)),
	}}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(t.Key)
}

// Verify validates an access token and returns (user, session).
func (t *Tokens) Verify(raw string) (string, string, error) {
	var c Claims
	_, err := jwt.ParseWithClaims(raw, &c, func(*jwt.Token) (any, error) { return t.Key, nil },
		jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer(issuer), jwt.WithTimeFunc(t.now),
		jwt.WithExpirationRequired())
	if err != nil {
		return "", "", err
	}
	if c.Subject == "" || c.SessionID == "" {
		return "", "", errors.New("incomplete token")
	}
	return c.Subject, c.SessionID, nil
}

// BearerToken extracts the token from the Authorization header.
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

package auth

import (
	"crypto/hmac"
	"crypto/sha1" //nolint:gosec // required by RFC 6238: authenticator apps use HMAC-SHA1
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"net/url"
	"strings"
	"time"
)

// TOTP (RFC 6238): 6 digits, 30 s step, one step of tolerance on each side.
const (
	totpPeriod = 30
	totpDigits = 6
	totpIssuer = "Mijote"
)

// Base32 encodes TOTP secrets and backup codes.
var Base32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// TOTPCode computes the code of a secret for a time step.
func TOTPCode(secret []byte, step int64) string {
	m := hmac.New(sha1.New, secret)
	var c [8]byte
	binary.BigEndian.PutUint64(c[:], uint64(step))
	m.Write(c[:])
	sum := m.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	n := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	return fmt.Sprintf("%0*d", totpDigits, n%1_000_000)
}

// TOTPMatch returns the step whose code matches (within ±1 step of now), or 0.
func TOTPMatch(secret []byte, code string, now time.Time) int64 {
	code = strings.ReplaceAll(strings.TrimSpace(code), " ", "")
	if len(code) != totpDigits {
		return 0
	}
	cur := now.Unix() / totpPeriod
	found := int64(0)
	for _, step := range []int64{cur - 1, cur, cur + 1} {
		// Constant-time comparison over all three steps.
		if hmac.Equal([]byte(TOTPCode(secret, step)), []byte(code)) {
			found = step
		}
	}
	return found
}

// TOTPURI returns the otpauth:// URI to encode as a QR code.
func TOTPURI(secret []byte, account string) string {
	label := url.PathEscape(totpIssuer + ":" + account)
	q := url.Values{"secret": {Base32.EncodeToString(secret)}, "issuer": {totpIssuer},
		"algorithm": {"SHA1"}, "digits": {"6"}, "period": {"30"}}
	return "otpauth://totp/" + label + "?" + q.Encode()
}

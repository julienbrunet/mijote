package auth

import (
	"net/http/httptest"
	"testing"
	"time"
)

func TestTokens(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	tk := &Tokens{Key: []byte("k"), Now: func() time.Time { return now }}
	raw, err := tk.Issue("u1", "s1")
	if err != nil {
		t.Fatal(err)
	}
	if u, s, err := tk.Verify(raw); err != nil || u != "u1" || s != "s1" {
		t.Fatalf("Verify = %q %q %v", u, s, err)
	}
	if _, _, err := (&Tokens{Key: []byte("other")}).Verify(raw); err == nil {
		t.Fatal("wrong key accepted")
	}
	now = now.Add(AccessTTL + time.Second)
	if _, _, err := tk.Verify(raw); err == nil {
		t.Fatal("expired token accepted")
	}
}

func TestPassword(t *testing.T) {
	h := HashPassword("correct horse")
	if !CheckPassword(h, "correct horse") || CheckPassword(h, "wrong") || CheckPassword("garbage", "x") {
		t.Fatal("CheckPassword mismatch")
	}
}

func TestTOTP(t *testing.T) {
	// RFC 6238 test vector (SHA-1, T = 59 s): 94287082 → last 6 digits.
	secret := []byte("12345678901234567890")
	if got := TOTPCode(secret, 1); got != "287082" {
		t.Fatalf("TOTPCode = %s", got)
	}
	now := time.Unix(90, 0) // step 3
	for step := int64(2); step <= 4; step++ {
		if TOTPMatch(secret, TOTPCode(secret, step), now) != step {
			t.Fatalf("step %d not matched", step)
		}
	}
	if TOTPMatch(secret, TOTPCode(secret, 5), now) != 0 {
		t.Fatal("step outside tolerance matched")
	}
}

func TestLimiter(t *testing.T) {
	now := time.Unix(0, 0)
	l := NewLimiter(2, time.Minute)
	l.Now = func() time.Time { return now }
	l.Fail("a")
	if l.Blocked("a") != 0 {
		t.Fatal("blocked too early")
	}
	l.Fail("a")
	if l.Blocked("a") != time.Minute {
		t.Fatalf("Blocked = %v", l.Blocked("a"))
	}
	now = now.Add(61 * time.Second)
	if l.Blocked("a") != 0 {
		t.Fatal("window not sliding")
	}
}

func TestClientIP(t *testing.T) {
	p, err := ParseProxies("10.0.0.1, 192.168.0.0/16")
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "10.0.0.1:1234"
	r.Header.Set("X-Forwarded-For", "6.6.6.6, 1.2.3.4, 192.168.1.2")
	if got := p.ClientIP(r); got != "1.2.3.4" {
		t.Fatalf("trusted proxy: %s", got)
	}
	r.RemoteAddr = "8.8.8.8:1"
	if got := p.ClientIP(r); got != "8.8.8.8" {
		t.Fatalf("untrusted proxy: %s", got)
	}
}

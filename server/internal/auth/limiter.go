package auth

import (
	"strings"
	"sync"
	"time"
)

// Limiter counts login failures per key over a sliding window. Counters live
// in memory and reset on restart.
type Limiter struct {
	Max    int
	Window time.Duration
	Now    func() time.Time

	mu       sync.Mutex
	failures map[string][]time.Time
}

func NewLimiter(max int, window time.Duration) *Limiter {
	return &Limiter{Max: max, Window: window, failures: map[string][]time.Time{}}
}

func (l *Limiter) now() time.Time {
	if l.Now != nil {
		return l.Now()
	}
	return time.Now()
}

func (l *Limiter) recent(key string, now time.Time) []time.Time {
	list := l.failures[key]
	i := 0
	for i < len(list) && now.Sub(list[i]) > l.Window {
		i++
	}
	list = list[i:]
	if len(list) == 0 {
		delete(l.failures, key)
	} else {
		l.failures[key] = list
	}
	return list
}

// Blocked returns the remaining wait when the key reached the failure limit.
func (l *Limiter) Blocked(key string) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	list := l.recent(key, now)
	if len(list) < l.Max {
		return 0
	}
	return l.Window - now.Sub(list[len(list)-l.Max])
}

func (l *Limiter) Fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.failures[key] = append(l.recent(key, now), now)
	if len(l.failures) > 10000 { // memory guard
		for k := range l.failures {
			l.recent(k, now)
		}
	}
}

func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.failures, key)
}

// Guard combines the per-account and per-address limits.
type Guard struct {
	PerUser *Limiter
	PerIP   *Limiter
}

// NewGuard blocks for 15 minutes after 5 failures on an account or 20 from an address.
func NewGuard() *Guard {
	return &Guard{PerUser: NewLimiter(5, 15*time.Minute), PerIP: NewLimiter(20, 15*time.Minute)}
}

// Wait returns the delay imposed before a new attempt (0 = allowed).
func (g *Guard) Wait(username, ip string) time.Duration {
	return max(g.PerUser.Blocked(strings.ToLower(username)), g.PerIP.Blocked(ip))
}

func (g *Guard) Failed(username, ip string) {
	g.PerUser.Fail(strings.ToLower(username))
	g.PerIP.Fail(ip)
}

func (g *Guard) Succeeded(username string) { g.PerUser.Reset(strings.ToLower(username)) }

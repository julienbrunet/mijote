package store

import (
	"testing"
	"time"
)

func TestWishWeekUsesFamilyMondayAndDaylightSaving(t *testing.T) {
	for _, tc := range []struct {
		now, start, end string
		hours           int
	}{
		{"2026-11-01T23:30:00Z", "2026-11-01T23:00:00Z", "2026-11-08T23:00:00Z", 168},
		{"2026-03-29T01:30:00Z", "2026-03-22T23:00:00Z", "2026-03-29T22:00:00Z", 167},
		{"2026-10-25T12:00:00Z", "2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z", 169},
	} {
		now, _ := time.Parse(time.RFC3339, tc.now)
		start, end, err := wishWeek(now, "Europe/Paris")
		if err != nil || start.UTC().Format(time.RFC3339) != tc.start || end.UTC().Format(time.RFC3339) != tc.end || int(end.Sub(start).Hours()) != tc.hours {
			t.Fatalf("%s: %v %v %v", tc.now, start, end, err)
		}
	}
}

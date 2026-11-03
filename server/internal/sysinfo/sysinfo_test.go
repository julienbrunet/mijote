package sysinfo

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestReadDir(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "a.jpg"), []byte("12345"), 0o600)      //nolint:errcheck
	os.Mkdir(filepath.Join(dir, "sub"), 0o700)                             //nolint:errcheck
	os.WriteFile(filepath.Join(dir, "sub", "b.jpg"), []byte("123"), 0o600) //nolint:errcheck
	u, err := ReadDir(dir)
	if err != nil || u.Files != 2 || u.Bytes != 8 || !u.Names["a.jpg"] || !u.Names["sub/b.jpg"] {
		t.Fatalf("ReadDir = %+v, %v", u, err)
	}
	u, err = ReadDir(filepath.Join(dir, "missing"))
	if err != nil || u.Files != 0 {
		t.Fatalf("missing dir = %+v, %v", u, err)
	}
}

func TestReadings(t *testing.T) {
	p := ReadProcess()
	if p.HeapAlloc == 0 || p.Goroutines == 0 {
		t.Fatalf("process = %+v", p)
	}
	h := ReadHost()
	if h.CPUs == 0 {
		t.Fatalf("host = %+v", h)
	}
	if runtime.GOOS == "linux" {
		if p.RSS == 0 || p.CPUTime < 0 || h.MemTotal == 0 || h.Load1 < 0 {
			t.Fatalf("linux readings: %+v %+v", p, h)
		}
		if d := ReadDisk(t.TempDir()); d.Total == 0 || d.UsedPct() > 100 {
			t.Fatalf("disk = %+v", d)
		}
	}
}

func TestSamplerWindow(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := NewSampler(ctx)
	start := time.Now().Add(-3 * time.Minute)
	s.Started, s.samples = start, nil
	for i := 0; i <= 12; i++ {
		s.add(start.Add(time.Duration(i)*sampleEvery), 0)
	}
	if got := s.samples[len(s.samples)-1].at.Sub(s.samples[0].at); got > window+sampleEvery || got < window {
		t.Fatalf("window kept %v of samples", got)
	}
	recent, avg := s.CPUPercent()
	if runtime.GOOS == "linux" && (recent <= 0 || avg <= 0 || recent < avg) {
		t.Fatalf("cpu = %v %v", recent, avg)
	}
}

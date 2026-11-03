// Package sysinfo reads the resource usage of the server process and of its
// host for the "Système" page of the web admin. Standard library only: the
// figures come from the Go runtime and, on Linux, from /proc and statfs.
package sysinfo

import (
	"context"
	"io/fs"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// Process is the resource usage of the server process.
type Process struct {
	RSS        uint64 // resident memory in bytes (0 when unknown)
	HeapAlloc  uint64 // live Go heap
	GoSys      uint64 // memory obtained from the OS by the Go runtime
	Goroutines int
	NumGC      uint32
	CPUTime    time.Duration // user + system time since start
}

// Host describes the machine the server runs on.
type Host struct {
	OS, Arch     string
	CPUs         int
	MemTotal     uint64 // bytes, 0 when unknown
	MemAvailable uint64
	Load1        float64 // load averages, -1 when unknown
	Load5        float64
	Load15       float64
	Uptime       time.Duration // 0 when unknown
}

// Disk is the space of the file system holding a directory.
type Disk struct {
	Total, Free uint64 // bytes, 0 when unknown
}

// UsedPct is the used share of the file system, in percent.
func (d Disk) UsedPct() int {
	if d.Total == 0 {
		return 0
	}
	return int((d.Total - d.Free) * 100 / d.Total)
}

// ReadProcess returns the current usage of the process.
func ReadProcess() Process {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return Process{RSS: rss(), HeapAlloc: m.HeapAlloc, GoSys: m.Sys, Goroutines: runtime.NumGoroutine(),
		NumGC: m.NumGC, CPUTime: cpuTime()}
}

// ReadHost returns the state of the machine.
func ReadHost() Host {
	h := Host{OS: runtime.GOOS, Arch: runtime.GOARCH, CPUs: runtime.NumCPU(), Load1: -1, Load5: -1, Load15: -1}
	readHost(&h)
	return h
}

// ReadDisk returns the space of the file system holding dir.
func ReadDisk(dir string) Disk { return readDisk(dir) }

// DirUsage counts the regular files of a directory tree and their size.
type DirUsage struct {
	Files int
	Bytes int64
	Names map[string]bool // file names relative to the directory
}

// ReadDir walks dir; a missing directory is empty.
func ReadDir(dir string) (DirUsage, error) {
	u := DirUsage{Names: map[string]bool{}}
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if path == dir && errorIsNotExist(err) {
				return filepath.SkipDir
			}
			return err
		}
		if !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(dir, path)
		u.Files++
		u.Bytes += info.Size()
		u.Names[filepath.ToSlash(rel)] = true
		return nil
	})
	return u, err
}

// Sampler measures the CPU used by the process over the last minute.
type Sampler struct {
	Started time.Time

	mu      sync.Mutex
	samples []sample // oldest first, at most one minute apart from the newest
}

type sample struct {
	at  time.Time
	cpu time.Duration
}

const (
	sampleEvery = 15 * time.Second
	window      = time.Minute
)

// NewSampler starts sampling until ctx ends.
func NewSampler(ctx context.Context) *Sampler {
	s := &Sampler{Started: time.Now()}
	s.add(time.Now(), cpuTime())
	go func() {
		t := time.NewTicker(sampleEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-t.C:
				s.add(now, cpuTime())
			}
		}
	}()
	return s
}

func (s *Sampler) add(at time.Time, cpu time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.samples = append(s.samples, sample{at, cpu})
	for len(s.samples) > 2 && at.Sub(s.samples[1].at) >= window {
		s.samples = s.samples[1:]
	}
}

// CPUPercent is the CPU used by the process, in percent of one core, over the
// last minute (recent) and since start (average). Negative when unknown.
func (s *Sampler) CPUPercent() (recent, average float64) {
	now, cpu := time.Now(), cpuTime()
	if cpu < 0 {
		return -1, -1
	}
	s.mu.Lock()
	first := s.samples[0]
	s.mu.Unlock()
	recent = percent(cpu-first.cpu, now.Sub(first.at))
	average = percent(cpu, now.Sub(s.Started))
	return recent, average
}

func percent(cpu, wall time.Duration) float64 {
	if wall <= 0 {
		return 0
	}
	return float64(cpu) * 100 / float64(wall)
}

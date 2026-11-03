package sysinfo

import (
	"bufio"
	"errors"
	"io/fs"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func errorIsNotExist(err error) bool { return errors.Is(err, fs.ErrNotExist) }

func cpuTime() time.Duration {
	var ru syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &ru); err != nil {
		return -1
	}
	return time.Duration(ru.Utime.Nano() + ru.Stime.Nano())
}

// kB reads "<key>: <value> kB" lines of a /proc file.
func kB(path string, keys ...string) map[string]uint64 {
	out := map[string]uint64{}
	f, err := os.Open(path)
	if err != nil {
		return out
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), ":")
		if !ok {
			continue
		}
		for _, want := range keys {
			if k == want {
				fields := strings.Fields(v)
				if len(fields) > 0 {
					if n, err := strconv.ParseUint(fields[0], 10, 64); err == nil {
						out[k] = n * 1024
					}
				}
			}
		}
	}
	return out
}

func rss() uint64 { return kB("/proc/self/status", "VmRSS")["VmRSS"] }

func readHost(h *Host) {
	m := kB("/proc/meminfo", "MemTotal", "MemAvailable")
	h.MemTotal, h.MemAvailable = m["MemTotal"], m["MemAvailable"]
	if b, err := os.ReadFile("/proc/loadavg"); err == nil {
		if f := strings.Fields(string(b)); len(f) >= 3 {
			h.Load1, _ = strconv.ParseFloat(f[0], 64)
			h.Load5, _ = strconv.ParseFloat(f[1], 64)
			h.Load15, _ = strconv.ParseFloat(f[2], 64)
		}
	}
	if b, err := os.ReadFile("/proc/uptime"); err == nil {
		if f := strings.Fields(string(b)); len(f) >= 1 {
			if s, err := strconv.ParseFloat(f[0], 64); err == nil {
				h.Uptime = time.Duration(s * float64(time.Second))
			}
		}
	}
}

func readDisk(dir string) Disk {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return Disk{}
	}
	bs := uint64(st.Bsize) //nolint:unconvert // int32 on some architectures
	return Disk{Total: uint64(st.Blocks) * bs, Free: uint64(st.Bavail) * bs}
}

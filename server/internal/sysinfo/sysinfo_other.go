//go:build !linux

package sysinfo

import (
	"errors"
	"io/fs"
	"time"
)

// Outside Linux (development machines), only the Go runtime figures are known.

func errorIsNotExist(err error) bool { return errors.Is(err, fs.ErrNotExist) }
func cpuTime() time.Duration         { return -1 }
func rss() uint64                    { return 0 }
func readHost(*Host)                 {}
func readDisk(string) Disk           { return Disk{} }

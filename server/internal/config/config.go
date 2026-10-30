// Package config loads the backend configuration from environment variables.
package config

import (
	"errors"
	"os"
)

// Config holds the runtime settings of the backend.
type Config struct {
	// DatabaseURL is a PostgreSQL connection string (MIJOTE_DATABASE_URL).
	DatabaseURL string
	// ListenAddr is the HTTP listen address (MIJOTE_LISTEN_ADDR).
	ListenAddr string
	// ImageDir is the directory holding recipe thumbnails (MIJOTE_IMAGE_DIR).
	ImageDir string
}

// Load reads the configuration from the environment and applies defaults.
func Load() (Config, error) {
	c := Config{
		DatabaseURL: os.Getenv("MIJOTE_DATABASE_URL"),
		ListenAddr:  getenv("MIJOTE_LISTEN_ADDR", ":8080"),
		ImageDir:    getenv("MIJOTE_IMAGE_DIR", "/data/images"),
	}
	if c.DatabaseURL == "" {
		return c, errors.New("MIJOTE_DATABASE_URL is required")
	}
	return c, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

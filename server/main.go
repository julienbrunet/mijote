// Mijote server runs the Mijote backend: it migrates the database, then
// serves the REST API.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/julienbrunet/mijote/server/internal/config"
	"github.com/julienbrunet/mijote/server/internal/migrate"
	"github.com/julienbrunet/mijote/server/internal/server"
)

// version is set at build time with -X main.version.
var version = "dev"

func main() {
	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run() error {
	slog.Info("starting mijote server", "version", version)
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	poolCfg, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return err
	}
	// Small pool: the target is a Raspberry Pi with a low max_connections.
	if poolCfg.MaxConns > 4 {
		poolCfg.MaxConns = 4
	}
	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return err
	}
	defer pool.Close()

	if err := waitForDB(ctx, pool); err != nil {
		return err
	}
	if err := migrate.Up(ctx, pool); err != nil {
		return err
	}
	v, err := migrate.Version(ctx, pool)
	if err != nil {
		return err
	}
	slog.Info("database ready", "schema_version", v)

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           server.New(pool),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(shutdownCtx) //nolint:errcheck
	}()
	slog.Info("listening", "addr", cfg.ListenAddr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// waitForDB retries until PostgreSQL answers, so the backend can start
// alongside the database container.
func waitForDB(ctx context.Context, pool *pgxpool.Pool) error {
	for i := 0; ; i++ {
		err := pool.Ping(ctx)
		if err == nil {
			return nil
		}
		if i >= 30 {
			return err
		}
		slog.Warn("database not ready, retrying", "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
}

// Mijote server runs the Mijote backend: it migrates the database, then
// serves the REST API and the web admin.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/julienbrunet/mijote/server/internal/auth"
	"github.com/julienbrunet/mijote/server/internal/config"
	"github.com/julienbrunet/mijote/server/internal/migrate"
	"github.com/julienbrunet/mijote/server/internal/server"
	"github.com/julienbrunet/mijote/server/internal/store"
)

// version is set at build time with -X main.version.
var version = "dev"

func main() {
	var err error
	if len(os.Args) > 1 && os.Args[1] == "reset-2fa" {
		err = reset2FA(os.Args[2:])
	} else {
		err = run()
	}
	if err != nil {
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
	proxies, err := auth.ParseProxies(cfg.TrustedProxies)
	if err != nil {
		return fmt.Errorf("MIJOTE_TRUSTED_PROXIES: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := openDB(ctx, cfg.DatabaseURL)
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

	if err := os.MkdirAll(cfg.ImageDir, 0o750); err != nil {
		return fmt.Errorf("MIJOTE_IMAGE_DIR: %w", err)
	}
	handler, err := server.New(ctx, pool, proxies, cfg.ImageDir, version)
	if err != nil {
		return err
	}
	go purge(ctx, store.New(pool))

	srv := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           handler,
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

func openDB(ctx context.Context, url string) (*pgxpool.Pool, error) {
	poolCfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, err
	}
	// Small pool: the target is a Raspberry Pi with a low max_connections.
	if poolCfg.MaxConns > 4 {
		poolCfg.MaxConns = 4
	}
	return pgxpool.NewWithConfig(ctx, poolCfg)
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

// purge deletes old authentication traces once a day.
func purge(ctx context.Context, st *store.Store) {
	t := time.NewTicker(24 * time.Hour)
	defer t.Stop()
	for {
		if err := st.PurgeAuth(ctx); err != nil && ctx.Err() == nil {
			slog.Error("auth purge failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// reset2FA: "mijote reset-2fa <username>" disables the two-factor authentication
// of an account (lost phone, no backup code left). Run it on the server with
// the same MIJOTE_DATABASE_URL as the service.
func reset2FA(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: mijote reset-2fa <username>   (database: MIJOTE_DATABASE_URL)")
	}
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx := context.Background()
	pool, err := openDB(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	st := store.New(pool)
	u, err := st.UserByUsername(ctx, args[0])
	if errors.Is(err, store.ErrNotFound) {
		return fmt.Errorf("unknown account: %s", args[0])
	} else if err != nil {
		return err
	}
	if err := st.TOTPDisable(ctx, u.ID); err != nil {
		return err
	}
	st.LogAuthEvent(ctx, &u.ID, u.Username, "cli", "2fa_disabled", "réinitialisée en ligne de commande", store.Client{})
	fmt.Printf("Two-factor authentication disabled for %s.\n", u.Username)
	return nil
}

package migrate

import (
	"context"
	"os"
	"testing"
	"testing/fstest"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestLoadOrdersAndValidates(t *testing.T) {
	fsys := fstest.MapFS{
		"m/0002_b.sql": {Data: []byte("B")},
		"m/0001_a.sql": {Data: []byte("A")},
		"m/README":     {Data: []byte("ignored")},
	}
	ms, err := load(fsys, "m")
	if err != nil {
		t.Fatal(err)
	}
	if len(ms) != 2 || ms[0].Name != "0001_a" || ms[1].Version != 2 {
		t.Fatalf("unexpected migrations: %+v", ms)
	}

	bad := fstest.MapFS{"m/x_a.sql": {}}
	if _, err := load(bad, "m"); err == nil {
		t.Fatal("expected error for bad name")
	}
	dup := fstest.MapFS{"m/0001_a.sql": {}, "m/1_b.sql": {}}
	if _, err := load(dup, "m"); err == nil {
		t.Fatal("expected error for duplicate version")
	}
}

func TestEmbeddedMigrationsLoad(t *testing.T) {
	ms, err := Load()
	if err != nil || len(ms) == 0 {
		t.Fatalf("Load() = %d migrations, %v", len(ms), err)
	}
}

// TestUp runs against a real database when MIJOTE_TEST_DATABASE_URL is set.
// The database is expected to be empty and disposable.
func TestUp(t *testing.T) {
	url := os.Getenv("MIJOTE_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("MIJOTE_TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()

	for i := 0; i < 2; i++ { // second run must be a no-op
		if err := Up(ctx, pool); err != nil {
			t.Fatalf("run %d: %v", i, err)
		}
	}
	ms, _ := Load()
	if v, err := Version(ctx, pool); err != nil || v != ms[len(ms)-1].Version {
		t.Fatalf("Version() = %d, %v", v, err)
	}

	// A database ahead of the binary is refused.
	if err := apply(ctx, pool, ms[:0]); err != nil {
		t.Fatalf("empty list should be accepted: %v", err)
	}
	if len(ms) > 0 {
		older := []Migration{{Version: 0, Name: "0000_none"}}
		if err := apply(ctx, pool, older); err == nil {
			t.Fatal("expected downgrade error")
		}
	}
}

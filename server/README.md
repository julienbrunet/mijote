# Mijote server

Go server: REST API, database migrations at startup.

## Configuration (environment variables)
| Variable | Default | Description |
|---|---|---|
| `MIJOTE_DATABASE_URL` | (required) | PostgreSQL URL, e.g. `postgres://mijote:secret@db:5432/mijote` |
| `MIJOTE_LISTEN_ADDR` | `:8080` | HTTP listen address |
| `MIJOTE_IMAGE_DIR` | `/data/images` | Recipe thumbnails (back it up with the database) |
| `MIJOTE_TRUSTED_PROXIES` | (none) | Reverse proxies whose `X-Forwarded-*` headers are trusted, e.g. `192.168.1.10` |

## Run
```bash
MIJOTE_DATABASE_URL=postgres://... go run .
```
Endpoints so far: `GET /healthz`, `GET /api/versions`, `POST /api/v1/auth/{login,refresh,logout}`,
`POST /api/v1/invitations/redeem`, `GET /api/v1/me`, the recipe API and the web admin under `/admin/` (setup wizard on first start, recipes, tags, accounts, invitations, sessions, login journal).

Recipe API (bearer token, JSON, camelCase; shape in `internal/store/recipes.go`):
`GET /api/v1/recipes?q=&tag=&dishType=&limit=&offset=` (summaries: no ingredients or steps),
`POST /api/v1/recipes`, `GET|PUT /api/v1/recipes/{id}` (full recipe; `PUT` replaces the content, last write wins),
`DELETE /api/v1/recipes/{id}` (archives; admin only), `GET /api/v1/tags`.
Images are uploaded, never linked: `PUT /api/v1/recipes/{id}/image` (raw JPEG/PNG/GIF body, 10 MB max; stored as a JPEG thumbnail of 800 px max in `MIJOTE_IMAGE_DIR`), `GET` it back (the recipe's `image` field is its path), `DELETE` removes it.
Security model: `requirements.md` §13. Lost 2FA phone: `mijote reset-2fa <username>`.

## Database migrations
To change the schema, add
`internal/migrate/migrations/NNNN_description.sql` with the next number; never edit
an applied file. Migrations run automatically on startup.

## Tests
```bash
go test ./...
```
Set `MIJOTE_TEST_DATABASE_URL` to a disposable database to also run the migration
and auth flow tests (the latter creates and drops its own temporary database).

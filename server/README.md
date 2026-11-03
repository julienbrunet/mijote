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
## API documentation
- [`API.md`](API.md): guide for scripts, tools and LLM assistants (access, conventions,
  errors, recipe data, adding a recipe from a URL, planning menus).
- [`openapi.yaml`](openapi.yaml): OpenAPI 3.1 reference of every endpoint. `go test`
  fails when a route is missing from it or documented but not served.

Every API change updates both files in the same commit.

Other routes: `GET /invite/<code>` (public invitation page) and the web admin under
`/admin/` (setup wizard on first start; menu *Accueil*: upcoming meals as cards, editable;
*Recettes*: recipes, tags, usage statistics; *Utilisateurs*: accounts, invitations, sessions, login
journal; *Système*: instance figures, versions, memory/CPU, database and image directory sizes;
*Mon profil*: password, 2FA, API tokens).
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

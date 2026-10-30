# Mijote server

Go server: REST API, database migrations at startup.

## Configuration (environment variables)
| Variable | Default | Description |
|---|---|---|
| `MIJOTE_DATABASE_URL` | (required) | PostgreSQL URL, e.g. `postgres://mijote:secret@db:5432/mijote` |
| `MIJOTE_LISTEN_ADDR` | `:8080` | HTTP listen address |
| `MIJOTE_IMAGE_DIR` | `/data/images` | Thumbnail directory |

## Run
```bash
MIJOTE_DATABASE_URL=postgres://... go run .
```
Endpoints so far: `GET /healthz`, `GET /api/versions`.

## Database migrations
To change the schema, add
`internal/migrate/migrations/NNNN_description.sql` with the next number; never edit
an applied file. Migrations run automatically on startup.

## Tests
```bash
go test ./...
```
Set `MIJOTE_TEST_DATABASE_URL` to an empty, disposable database to also run the
migrations against PostgreSQL.

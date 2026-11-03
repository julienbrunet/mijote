-- Server versions that ran on this instance, recorded at startup, so the web
-- admin shows since when the current version runs and the update history.
CREATE TABLE server_version (
    version          text PRIMARY KEY,
    first_started_at timestamptz NOT NULL DEFAULT now(),
    last_started_at  timestamptz NOT NULL DEFAULT now()
);

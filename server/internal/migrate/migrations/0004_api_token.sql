-- Personal API tokens created by the admin from the web admin, for scripts and
-- assistants that read or write data through /api/v1 without a device session.
-- Only the SHA-256 hash is stored; the clear token is shown once.
CREATE TABLE api_token (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name         text NOT NULL,
    token_hash   bytea NOT NULL UNIQUE,
    prefix       text NOT NULL,
    scope        text NOT NULL CHECK (scope IN ('read', 'write')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    last_ip      text,
    revoked_at   timestamptz
);
CREATE INDEX api_token_user_idx ON api_token (user_id);

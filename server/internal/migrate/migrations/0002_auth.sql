-- Authentication: app refresh tokens (replace api_token), web admin sessions,
-- TOTP two-factor authentication, login journal and server secrets.

DROP TABLE api_token;

CREATE TABLE server_secret (
    name       text PRIMARY KEY,
    value      bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One row per refresh token; a family is one device session. Rotation revokes
-- the old token with reason 'rotated' and points it to its replacement.
CREATE TABLE refresh_token (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id     uuid NOT NULL,
    user_id       uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash    bytea NOT NULL UNIQUE,
    device_name   text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    last_used_at  timestamptz,
    created_ip    text,
    last_ip       text,
    user_agent    text,
    revoked_at    timestamptz,
    revoke_reason text,
    replaced_by   uuid REFERENCES refresh_token (id) ON DELETE SET NULL
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);

CREATE TABLE admin_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash    bytea NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    idle_seconds  integer NOT NULL,
    ip            text,
    user_agent    text,
    revoked_at    timestamptz,
    revoke_reason text
);
CREATE INDEX admin_session_user_idx ON admin_session (user_id);

CREATE TABLE user_totp (
    user_id    uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    secret     bytea NOT NULL, -- AES-GCM sealed with server_secret 'totp_key'
    created_at timestamptz NOT NULL DEFAULT now(),
    enabled_at timestamptz,    -- null = setup not confirmed yet
    last_step  bigint NOT NULL DEFAULT 0 -- anti-replay
);

CREATE TABLE user_totp_backup (
    user_id   uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash bytea NOT NULL,
    used_at   timestamptz,
    PRIMARY KEY (user_id, code_hash)
);

CREATE TABLE auth_event (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at         timestamptz NOT NULL DEFAULT now(),
    user_id    uuid REFERENCES app_user (id) ON DELETE SET NULL,
    username   text NOT NULL,
    client     text NOT NULL, -- 'app', 'admin' or 'cli'
    event      text NOT NULL,
    detail     text,
    ip         text,
    user_agent text
);
CREATE INDEX auth_event_at_idx ON auth_event (at);
CREATE INDEX auth_event_user_idx ON auth_event (user_id, at);

-- Initial schema, from requirements.md §9.

CREATE TYPE user_role AS ENUM ('admin', 'member');
CREATE TYPE dish_type AS ENUM ('meat', 'fish', 'vegetarian');
CREATE TYPE season AS ENUM ('spring', 'summer', 'autumn', 'winter');
CREATE TYPE meal AS ENUM ('lunch', 'dinner');
CREATE TYPE day_meals AS ENUM ('lunch_dinner', 'dinner', 'none');
CREATE TYPE unit AS ENUM (
    'g', 'kg', 'ml', 'cl', 'dl', 'l', 'tbsp', 'tsp',
    'pinch', 'dash', 'handful',
    'piece', 'clove', 'slice', 'bunch', 'sprig', 'leaf', 'can', 'pack', 'jar'
);

CREATE TABLE app_user (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username      text NOT NULL,
    display_name  text NOT NULL,
    password_hash text NOT NULL,
    role          user_role NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    disabled_at   timestamptz
);
CREATE UNIQUE INDEX app_user_username_key ON app_user (lower(username));
-- Exactly one admin per instance.
CREATE UNIQUE INDEX app_user_single_admin ON app_user (role) WHERE role = 'admin';

CREATE TABLE api_token (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES app_user (id),
    token_hash   bytea NOT NULL UNIQUE,
    device_name  text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX api_token_user_id_idx ON api_token (user_id);

CREATE TABLE invitation (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash  bytea NOT NULL UNIQUE,
    created_by uuid NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_by    uuid REFERENCES app_user (id),
    used_at    timestamptz,
    revoked_at timestamptz
);

CREATE TABLE recipe (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title         text NOT NULL CHECK (title <> ''),
    source_url    text UNIQUE,
    image_url     text,
    image_path    text,
    prep_minutes  integer CHECK (prep_minutes >= 0),
    cook_minutes  integer CHECK (cook_minutes >= 0),
    total_minutes integer CHECK (total_minutes >= 0),
    servings      integer CHECK (servings > 0),
    dish_type     dish_type NOT NULL,
    seasons       season[] NOT NULL DEFAULT '{}',
    meal_types    meal[] NOT NULL DEFAULT '{lunch,dinner}',
    notes         text,
    created_by    uuid REFERENCES app_user (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer NOT NULL DEFAULT 1,
    archived_at   timestamptz
);
CREATE INDEX recipe_updated_at_idx ON recipe (updated_at);

CREATE TABLE recipe_ingredient (
    recipe_id uuid NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    position  integer NOT NULL,
    raw_text  text NOT NULL,
    quantity  numeric,
    unit      unit,
    name      text NOT NULL,
    note      text,
    PRIMARY KEY (recipe_id, position)
);

CREATE TABLE recipe_step (
    recipe_id uuid NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    position  integer NOT NULL,
    text      text NOT NULL,
    PRIMARY KEY (recipe_id, position)
);

CREATE TABLE tag (
    id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (name <> '')
);
CREATE UNIQUE INDEX tag_name_key ON tag (lower(name));

CREATE TABLE recipe_tag (
    recipe_id uuid NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    tag_id    uuid NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (recipe_id, tag_id)
);
CREATE INDEX recipe_tag_tag_id_idx ON recipe_tag (tag_id);

-- Single row, enforced by the boolean primary key.
CREATE TABLE family_settings (
    id                 boolean PRIMARY KEY DEFAULT true CHECK (id),
    -- Default meals per ISO weekday, Monday first (7 items).
    default_meals      day_meals[] NOT NULL DEFAULT '{lunch_dinner,lunch_dinner,lunch_dinner,lunch_dinner,lunch_dinner,lunch_dinner,lunch_dinner}'
                       CHECK (cardinality(default_meals) = 7),
    horizon_days       integer NOT NULL DEFAULT 7 CHECK (horizon_days > 0),
    quick_max_minutes  integer NOT NULL DEFAULT 30 CHECK (quick_max_minutes > 0),
    -- ISO weekdays (1 = Monday … 7 = Sunday).
    weekend_days       smallint[] NOT NULL DEFAULT '{6,7}',
    recent_use_days    integer NOT NULL DEFAULT 21 CHECK (recent_use_days >= 0),
    target_meat_pct    integer NOT NULL DEFAULT 30,
    target_fish_pct    integer NOT NULL DEFAULT 30,
    target_veg_pct     integer NOT NULL DEFAULT 40,
    min_recipes        integer NOT NULL DEFAULT 10 CHECK (min_recipes >= 0),
    timezone           text NOT NULL DEFAULT 'Europe/Paris',
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer NOT NULL DEFAULT 1,
    CHECK (target_meat_pct >= 0 AND target_fish_pct >= 0 AND target_veg_pct >= 0
           AND target_meat_pct + target_fish_pct + target_veg_pct = 100)
);
INSERT INTO family_settings DEFAULT VALUES;

CREATE TABLE menu_slot (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    date       date NOT NULL,
    meal       meal NOT NULL,
    recipe_id  uuid REFERENCES recipe (id),
    locked     boolean NOT NULL DEFAULT false,
    updated_by uuid REFERENCES app_user (id),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    integer NOT NULL DEFAULT 1,
    deleted_at timestamptz,
    UNIQUE (date, meal)
);
CREATE INDEX menu_slot_updated_at_idx ON menu_slot (updated_at);
CREATE INDEX menu_slot_recipe_id_idx ON menu_slot (recipe_id);

-- Wishes keep their quota history independently of recipients deleting notifications.
ALTER TABLE family_settings ADD COLUMN recipe_wishes_per_week integer NOT NULL DEFAULT 5
    CHECK (recipe_wishes_per_week BETWEEN 1 AND 100);

CREATE TABLE recipe_wish (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    recipe_id uuid NOT NULL REFERENCES recipe(id) ON DELETE CASCADE,
    requester_name text NOT NULL,
    recipe_title text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recipe_wish_quota_idx ON recipe_wish(requester_id, created_at);
CREATE INDEX recipe_wish_recipe_idx ON recipe_wish(requester_id, recipe_id);

CREATE TABLE recipe_wish_notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    wish_id uuid NOT NULL REFERENCES recipe_wish(id) ON DELETE CASCADE,
    recipient_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    read_at timestamptz,
    UNIQUE(wish_id, recipient_id)
);
CREATE INDEX recipe_wish_notification_recipient_idx ON recipe_wish_notification(recipient_id);

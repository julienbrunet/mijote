-- Personal favorite recipes: each user keeps their own list (not shared with the family).
CREATE TABLE recipe_favorite (
    user_id    uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    recipe_id  uuid NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, recipe_id)
);
CREATE INDEX recipe_favorite_recipe_id_idx ON recipe_favorite (recipe_id);

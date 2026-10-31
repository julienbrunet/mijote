-- Per-user preference: number of people to cook for. Recipes are stored for
-- one person; quantities are multiplied by this number.
ALTER TABLE app_user ADD COLUMN people integer NOT NULL DEFAULT 1 CHECK (people BETWEEN 1 AND 20);

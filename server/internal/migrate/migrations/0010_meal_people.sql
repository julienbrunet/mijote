-- Number of people (covers) of each planned meal, shared by the family. Null for meals
-- planned before this migration: the app then uses the reader's own preference.
ALTER TABLE menu_slot ADD COLUMN people smallint CHECK (people BETWEEN 1 AND 20);

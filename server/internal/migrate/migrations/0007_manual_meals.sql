-- Manual meals: a planned meal can be a free text ("Commander une pizza")
-- instead of a recipe. A slot holds a recipe, a title, or nothing (empty slot).
ALTER TABLE menu_slot ADD COLUMN title text;
ALTER TABLE menu_slot ADD CONSTRAINT menu_slot_recipe_or_title
    CHECK (recipe_id IS NULL OR title IS NULL);

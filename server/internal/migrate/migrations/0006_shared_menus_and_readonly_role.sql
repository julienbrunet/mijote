-- Read-only role for family members (e.g. children) who can view menus and
-- recipes without modifying them, and clean transition for shared menus.
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'readonly';

ALTER TABLE invitation ADD COLUMN role user_role NOT NULL DEFAULT 'member';

-- Delete existing menus so all members start with a clean shared slate.
TRUNCATE TABLE menu_slot;

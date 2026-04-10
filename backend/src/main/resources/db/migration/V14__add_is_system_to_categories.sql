ALTER TABLE categories ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;

-- Mark the existing "Хотелки" row as system if it exists
-- This name must match SystemCategory.WISHLIST_NAME
UPDATE categories SET is_system = true WHERE name = 'Хотелки';

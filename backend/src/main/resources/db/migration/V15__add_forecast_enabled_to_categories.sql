-- V15: Add forecast_enabled flag to categories for spend prediction feature
ALTER TABLE categories ADD COLUMN forecast_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Enable for variable expense categories only (mandatory excluded — no spending "pace")
UPDATE categories
SET forecast_enabled = TRUE
WHERE type = 'EXPENSE'
  AND is_deleted = FALSE
  AND name IN (
    'Еда / Продукты',
    'Кафе / Рестораны',
    'Транспорт',
    'Одежда',
    'Развлечения',
    'Подписки',
    'Прочее'
  );

-- Наследует приоритет категории для событий, у которых он не задан явно (был MEDIUM по умолчанию)
UPDATE financial_events fe
SET priority = c.priority
FROM categories c
WHERE fe.category_id = c.id
  AND fe.priority = 'MEDIUM'
  AND c.priority != 'MEDIUM';

-- Срез 3 п. 13 (ANO-230, ANO-259): системные категории и служебная копилка — признаком, а не именем.
-- Спека: docs/superpowers/specs/2026-11-18-slice3-journal-input-design.md, п. 13.
--
-- Продукт пишет в четыре категории сам — хотелки, переводы в копилки, траты на цели, платежи по кредиту — и
-- находил их по имени: своя категория с таким именем становилась системной, а переименовать системную было
-- нельзя. Теперь у категории признак вида; миграция проставляет его по именам один раз, дальше продукт ищет
-- по признаку (CategoryService.systemCategory). Признак получает каждая категория с этим именем — и
-- несистемная тоже: продукт до сих пор писал именно в неё («живая категория с этим именем берётся как есть»),
-- и запись идёт тем же путём, что вчера.
--
-- Файл повторяемый: его исполняет SystemKindMigrationIT поверх уже прогнанной схемы.

ALTER TABLE categories ADD COLUMN IF NOT EXISTS system_kind VARCHAR(32);

UPDATE categories c
   SET system_kind = CASE c.name
           WHEN 'Хотелки' THEN 'WISHLIST'
           WHEN 'Переводы в копилки' THEN 'FUND_TRANSFER'
           WHEN 'Цели' THEN 'GOAL_SPENDING'
           WHEN 'Кредит' THEN 'CREDIT'
       END
 WHERE c.name IN ('Хотелки', 'Переводы в копилки', 'Цели', 'Кредит');

-- У человека одна категория каждого вида.
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_owner_system_kind ON categories (user_id, system_kind)
    WHERE system_kind IS NOT NULL;

-- ANO-259: «Переводы в копилки» — «Накопление». Своя «Накопление» у человека уже есть (имя уникально у
-- владельца, uq_categories_owner_name) — системная остаётся под старым именем, миграция не падает.
UPDATE categories c
   SET name = 'Накопление'
 WHERE c.system_kind = 'FUND_TRANSFER'
   AND c.name <> 'Накопление'
   AND NOT EXISTS (SELECT 1 FROM categories o WHERE o.user_id = c.user_id AND o.name = 'Накопление');

-- Служебная копилка «POCKET» — кармашек первых версий: в обзоре копилок её нет. Код её больше не заводит;
-- признак ставится по имени один раз, своя копилка «POCKET» после этого видна (TargetFundService.getOverview).
ALTER TABLE target_funds ADD COLUMN IF NOT EXISTS is_service BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE target_funds SET is_service = TRUE WHERE name = 'POCKET' AND is_service = FALSE;

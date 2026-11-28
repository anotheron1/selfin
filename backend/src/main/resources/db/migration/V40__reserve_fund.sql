-- Срез 4, п. 7 (ANO-263, решение 23): НЗ — копилка. Спека: docs/superpowers/specs/2026-11-19-slice4-money-design.md, п. 7.
--
-- У цели признак запаса. Каждому, у кого нет живого НЗ, — цель «НЗ»: «сколько держать» — из настройки bufferAmount
-- (ключ «pocket» в user_settings), у кого её нет или она не число — 0; без счёта — лежит на основной карте. Первой в
-- «Целях» её ставит TargetFundService.getOverview. Настройку миграция не трогает: после неё код её не читает и не пишет.
--
-- Файл повторяемый: его исполняет ReserveFundMigrationIT поверх уже прогнанной схемы. Столбцы — все явно, как в V38: у
-- базы, восстановленной из дампа, умолчаний может не быть.

ALTER TABLE target_funds ADD COLUMN IF NOT EXISTS is_reserve BOOLEAN NOT NULL DEFAULT FALSE;

-- У человека один живой НЗ.
CREATE UNIQUE INDEX IF NOT EXISTS uq_target_funds_owner_reserve ON target_funds (user_id)
    WHERE is_reserve AND NOT is_deleted;

INSERT INTO target_funds (id, user_id, name, target_amount, current_balance, status, priority, is_deleted, created_at,
                          purchase_type, is_service, is_reserve)
SELECT gen_random_uuid(), u.id, 'НЗ',
       COALESCE((SELECT CASE WHEN jsonb_typeof(s.settings_value -> 'bufferAmount') = 'number'
                             THEN GREATEST((s.settings_value ->> 'bufferAmount')::numeric, 0) END
                   FROM user_settings s
                  WHERE s.user_id = u.id AND s.settings_key = 'pocket'), 0),
       0, 'FUNDING', 0, FALSE, now(), 'SAVINGS', FALSE, TRUE
  FROM users u
 WHERE NOT EXISTS (SELECT 1 FROM target_funds f WHERE f.user_id = u.id AND f.is_reserve AND NOT f.is_deleted);

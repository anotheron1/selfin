-- Срез 4, п. 5 (решение 59): «Наличные» — карта для трат у каждого.
-- Спека: docs/superpowers/specs/2026-11-19-slice4-money-design.md, п. 5.
--
-- Каждому, у кого нет живого счёта CASH без остатка, — карта «Наличные»: CASH, остаток не ведётся, категория
-- расхода «Наличные». Наличные с остатком, если они есть, не трогаются: они считаются в «Свободно», как раньше,
-- и карта для трат встаёт рядом. Новому человеку то же заводит Registration.
--
-- Категория карты. Живая расходная «Наличные» — берётся она. Имени нет ни у одной категории — заводится. Имя
-- занято доходной или удалённой категорией (uq_categories_owner_name держит и удалённые) — карта без категории:
-- «записано за месяц» у неё не посчитать, но миграция не падает, а чужую категорию карта не присваивает.
--
-- Файл повторяемый: его исполняет CashCardMigrationIT поверх уже прогнанной схемы.

INSERT INTO categories (id, user_id, name, type, priority)
SELECT gen_random_uuid(), u.id, 'Наличные', 'EXPENSE', 'MEDIUM'
  FROM users u
 WHERE NOT EXISTS (SELECT 1 FROM accounts a
                    WHERE a.user_id = u.id AND a.kind = 'CASH' AND NOT a.track_balance AND NOT a.is_deleted)
   AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.user_id = u.id AND c.name = 'Наличные');

INSERT INTO accounts (id, user_id, name, kind, track_balance, purpose_category_id)
SELECT gen_random_uuid(), u.id, 'Наличные', 'CASH', FALSE,
       (SELECT c.id FROM categories c
         WHERE c.user_id = u.id AND c.name = 'Наличные' AND c.type = 'EXPENSE' AND NOT c.is_deleted)
  FROM users u
 WHERE NOT EXISTS (SELECT 1 FROM accounts a
                    WHERE a.user_id = u.id AND a.kind = 'CASH' AND NOT a.track_balance AND NOT a.is_deleted);

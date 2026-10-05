-- Р10-А (ANO-212): копилка без счёта — доля остатка основной карты. Перевод в неё остаток карты
-- больше не двигает (AnchorWindow.balanceEffect), а «потрачено на цель» пишет трату сегодняшним днём
-- (TargetFundService.delete, решение владельца 05.10).
--
-- Копилки, удалённые через «потрачено» до этой правки, ушли движением без траты: остаток карты им
-- уменьшали переводы. Теперь переводы его не двигают, и без траты эти деньги вернулись бы на карту —
-- в «на счёте», в свободные и в капитал. Каждой такой копилке пишется трата, какую с правки пишет сам
-- продукт: на сумму движения-списания, тем же днём и временем записи, с именем копилки, в категории
-- «Цели». Время записи нужно правилу дня сверки (ANO-82, AnchorWindow): трата, записанная до сверки
-- того же дня, уже внутри числа банка. Числа истории не меняются — спека
-- 2026-10-05-envelope-share-design.md, раздел «Старые „потрачено“: миграция V29».
--
-- Опознание: движение на минус у удалённой копилки без счёта, у которого нет события с тем же ключом.
-- «Вернуть» и «Снять» пишут перевод с ключом движения, факт перевода — тоже; без события остаются
-- только «потрачено» и компенсации V24 — деньги копилок, удалённых до ANO-86, которые и капитал
-- считал ушедшими. Трата берёт ключ движения: повторный прогон и трата, записанная продуктом после
-- правки, под условие уже не попадают.

-- Переименованные переводы — первыми, пока у движения-списания ещё нет траты. Прежняя ветка
-- «потрачено» переименовывала их в имя копилки, чтобы журнал назвал деньги тратой; теперь трату
-- называет сама трата, а переводы получают слова «Пополнить».
UPDATE financial_events e
   SET description = 'В копилку: ' || f.name, updated_at = CURRENT_TIMESTAMP
  FROM target_funds f
 WHERE e.target_fund_id = f.id
   AND e.type = 'FUND_TRANSFER'
   AND e.description = f.name
   AND f.is_deleted = TRUE
   AND f.account_id IS NULL
   AND EXISTS (SELECT 1 FROM fund_transactions t
                WHERE t.fund_id = f.id AND t.is_deleted = FALSE AND t.amount < 0
                  AND NOT EXISTS (SELECT 1 FROM financial_events x WHERE x.idempotency_key = t.idempotency_key));

-- Категория — только если тратить есть что: системная категория видна в «Настройках», и без старых
-- «потрачено» её заведёт сам продукт при первой такой трате. Имя уникально во всей таблице, вместе
-- с удалёнными, а удаляет категории сам человек: удалённая «Цели» возвращается системной категорией
-- расходов, а не остаётся под тратами удалённой (ревью Codex на #139) — так же поступает продукт.
UPDATE categories
   SET is_deleted = FALSE, is_system = TRUE, type = 'EXPENSE', primary_income = FALSE
 WHERE name = 'Цели' AND is_deleted = TRUE
   AND EXISTS (SELECT 1 FROM fund_transactions t JOIN target_funds f ON f.id = t.fund_id
                WHERE t.is_deleted = FALSE AND t.amount < 0
                  AND f.is_deleted = TRUE AND f.account_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM financial_events x WHERE x.idempotency_key = t.idempotency_key));

INSERT INTO categories (id, name, type, is_deleted, is_system)
SELECT gen_random_uuid(), 'Цели', 'EXPENSE', FALSE, TRUE
 WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Цели')
   AND EXISTS (SELECT 1 FROM fund_transactions t JOIN target_funds f ON f.id = t.fund_id
                WHERE t.is_deleted = FALSE AND t.amount < 0
                  AND f.is_deleted = TRUE AND f.account_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM financial_events x WHERE x.idempotency_key = t.idempotency_key));

INSERT INTO financial_events
    (id, idempotency_key, date, category_id, type, fact_amount, status, priority, description,
     created_at, event_kind, is_deleted)
SELECT gen_random_uuid(), t.idempotency_key, t.transaction_date,
       (SELECT c.id FROM categories c WHERE c.name = 'Цели'),
       'EXPENSE', -t.amount, 'EXECUTED', 'MEDIUM', f.name, t.created_at, 'FACT', FALSE
  FROM fund_transactions t
  JOIN target_funds f ON f.id = t.fund_id
 WHERE t.is_deleted = FALSE
   AND t.amount < 0
   AND f.is_deleted = TRUE
   AND f.account_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM financial_events x WHERE x.idempotency_key = t.idempotency_key);

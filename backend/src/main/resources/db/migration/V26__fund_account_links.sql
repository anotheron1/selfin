-- ANO-163: история привязок копилки к счёту.
--
-- Запрос суммы копилок-конвертов отбирал движения по признаку «у копилки СЕЙЧАС нет
-- счёта» (t.fund.accountId IS NULL), и сегодняшний признак применялся ко всем прошлым
-- датам. Привязка копилки сегодня уменьшала капитал за прошлые месяцы на её сумму,
-- отвязка возвращала. Тот же класс, что ANO-156 вылечила для флага «удалена».
--
-- Теперь привязка — не признак, а история: с какого по какое число копилка жила на
-- счёте. Запрос на дату t смотрит, была ли копилка привязана в тот день.
-- Спека: docs/superpowers/specs/2026-09-24-fund-link-history-design.md.
--
-- linked_from — первый день на счёте, linked_to — первый день снова конвертом;
-- NULL — копилка на счёте и сейчас.
--
-- ON DELETE CASCADE: продукт копилки не стирает (удаление мягкое), но тесты чистят
-- target_funds через DELETE, и история без каскада держала бы их внешним ключом.
CREATE TABLE fund_account_links (
    id          UUID      PRIMARY KEY,
    fund_id     UUID      NOT NULL REFERENCES target_funds(id) ON DELETE CASCADE,
    account_id  UUID      NOT NULL REFERENCES accounts(id),
    linked_from DATE      NOT NULL,
    linked_to   DATE,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_fund_account_links_period
        CHECK (linked_to IS NULL OR linked_to >= linked_from)
);

-- Открытая привязка у копилки одна: account_id в target_funds тоже один.
CREATE UNIQUE INDEX uq_fund_account_links_open
    ON fund_account_links (fund_id) WHERE linked_to IS NULL;

-- Копилки, привязанные ДО этой правки. Дата привязки неизвестна: у target_funds нет
-- updated_at, журнала изменений нет. Считаем их привязанными с рождения — ровно так их
-- считал старый запрос (исключал за все даты), поэтому миграция не сдвигает ни одного
-- числа. Прошлое старых привязок остаётся таким, каким человек его видел.
--
-- Удалённые копилки со счётом переносятся тоже: старый запрос исключал и их.
INSERT INTO fund_account_links (id, fund_id, account_id, linked_from, linked_to, created_at)
SELECT gen_random_uuid(), f.id, f.account_id, f.created_at::date, NULL, now()
FROM target_funds f
WHERE f.account_id IS NOT NULL;

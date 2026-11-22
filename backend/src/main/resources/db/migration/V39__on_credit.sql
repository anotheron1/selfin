-- Срез 4, п. 1 (ANO-261): отметка «в кредит» у записи. Расход с отметкой остаток основной не вычитает и к ожиданию
-- не встаёт. Спека: docs/superpowers/specs/2026-11-19-slice4-money-design.md, п. 1.
--
-- Новый столбец со своим умолчанием: прежние записи — не в кредит, вставки кода задают его явно.

ALTER TABLE financial_events ADD COLUMN on_credit BOOLEAN NOT NULL DEFAULT FALSE;

-- МУТАЦИЯ ДЛЯ ПРОВЕРКИ CI (ANO-26), не вливать: портит живые данные, не вызывая ни одной ошибки.
-- Столбец мягкого удаления в схеме — is_deleted (поле deleted есть только в Java).
UPDATE financial_events SET is_deleted = true;

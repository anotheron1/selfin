-- МУТАЦИЯ ДЛЯ ПРОВЕРКИ CI (ANO-26), не вливать: портит живые данные, не вызывая ни одной ошибки.
UPDATE financial_events SET deleted = true;

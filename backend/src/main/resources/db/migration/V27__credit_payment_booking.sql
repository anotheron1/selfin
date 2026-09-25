-- ANO-188: платёж по кредиту — бронь.
--
-- Конверсия хотелки в кредит создавала правило «<имя> — платёж по кредиту» с характером
-- «Ожидание» (MEDIUM). По канону правил продукта у такого платежа сумма и дата известны
-- заранее — это бронь, как ипотека. С «Ожиданием» пропущенный платёж выпадал из резерва
-- кармашка: findOverdueMandatoryExpenses берёт только брони (HIGH).
--
-- Правило само копилку не помнит — её несут события (target_fund_id). Поэтому правило
-- опознаётся по событиям: событие в категории «Кредит», ссылающееся на копилку-кредит. Категорию
-- конверсия ищет по имени (creditCategory), поэтому и здесь — по имени, а не по признаку
-- системной: человек мог завести «Кредит» сам до первой конверсии. Переводятся само правило и
-- ВСЕ его события — и перегенерированные, у которых ссылки на копилку нет.
--
-- Не трогаются: категория с другим именем, копилка-накопление, разовая покупка без правила.
-- Перевода в копилку правилом не бывает: у правила event_type только INCOME или EXPENSE (V16).
-- На базе владельца 24.09 и на стенде 25.09 таких правил ноль — миграция для баз, где конверсия
-- в кредит уже была.
--
-- Изменяющие подзапросы WITH выполняются ровно один раз, даже если основной запрос их не читает.
WITH credit_rules AS (
    SELECT DISTINCT e.recurring_rule_id AS rule_id
      FROM financial_events e
      JOIN categories   c ON c.id = e.category_id
      JOIN target_funds f ON f.id = e.target_fund_id
     WHERE c.name = 'Кредит'
       AND f.purchase_type = 'CREDIT'
),
rules AS (
    UPDATE recurring_rule r
       SET priority = 'HIGH', updated_at = CURRENT_TIMESTAMP
     WHERE r.id IN (SELECT rule_id FROM credit_rules)
    RETURNING r.id
)
UPDATE financial_events e
   SET priority = 'HIGH', updated_at = CURRENT_TIMESTAMP
 WHERE e.recurring_rule_id IN (SELECT rule_id FROM credit_rules);

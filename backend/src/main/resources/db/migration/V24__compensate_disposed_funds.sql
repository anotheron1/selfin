-- ANO-156. Копилки, удалённые ДО перехода на компенсирующее движение.
--
-- Снятие фильтра t.fund.deleted означает, что движения всех удалённых копилок снова
-- считаются. У копилки, удалённой через «потрачено на цель» (или ещё раньше, одной
-- строкой setDeleted), компенсации нет — и её деньги вернулись бы в текущий капитал,
-- воскресив ANO-86 ровно для неё.
--
-- Компенсируется СУММА ДВИЖЕНИЙ, а не current_balance: именно движения складывает запрос
-- sumEnvelopeFundsByTransactionDateLessThanEqual. Копилка с ненулевым балансом, но без
-- движений, от компенсации по балансу ушла бы в минус — миграция создала бы отрицательные
-- деньги там, где лечила лишние.
--
-- Дата — сегодняшняя, и это вынужденно: у target_funds нет updated_at, даты удаления в
-- схеме не существует. Продукт правит свои книги сегодня; история до сегодняшнего дня
-- остаётся такой, какой человек её видел.
--
-- Копилки, удалённые через «вернуть», сюда не попадают: doTransfer уже обнулил им движения.
-- Копилки со счётом не попадают тоже: своих денег у них нет.
INSERT INTO fund_transactions
    (id, fund_id, idempotency_key, amount, transaction_date, is_deleted, created_at)
SELECT gen_random_uuid(), f.id, gen_random_uuid(), -SUM(t.amount), CURRENT_DATE, false, now()
FROM target_funds f
JOIN fund_transactions t ON t.fund_id = f.id AND t.is_deleted = false
WHERE f.is_deleted = true
  AND f.account_id IS NULL
GROUP BY f.id
HAVING SUM(t.amount) <> 0;

-- Поле и сумма движений не имеют права разъезжаться: сервис с этой правки обнуляет и его.
UPDATE target_funds
SET current_balance = 0
WHERE is_deleted = true
  AND account_id IS NULL
  AND COALESCE(current_balance, 0) <> 0;

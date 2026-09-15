-- ANO-138: вернуть в обсуждение хотелки, чья конверсия создала событие с пустой датой.
--
-- До этой починки convertFromEvent брал src.getDate() без проверки. У хотелки без срока
-- (законное «когда-нибудь», WishlistCreateDto.date) это давало плановое событие с
-- date = NULL: его не видно ни в Бюджете, ни в /strategy, ни в кармашке — все выборки
-- идут по диапазону дат. Исходная при этом уходила в FIXED. Человек получал исчезнувшую
-- хотелку и не появившийся план.
--
-- Возвращаем ровно то, что было до нажатия: артефакт помечаем удалённым, источник
-- отпускаем обратно в OPEN. Дату не выдумываем (ANO-29) — человек поставит её сам
-- при повторной конверсии, теперь диалог её спросит.
--
-- Ссылку converted_to_event_id снять ОБЯЗАТЕЛЬНО, и не из-за схемы: ограничение
-- chk_event_converted_only_fixed снято миграцией V22. Причина в ensureNotConverted —
-- он отдаёт 409 на любой записи с непустой ссылкой, и хотелка вернулась бы в список
-- неработоспособной.
--
-- Сироты (событие с пустой датой, на которое никто не ссылается) НЕ трогаются:
-- доказать, что такая запись родилась из конверсии, нечем, а удалять по совпадению
-- признаков — значит трогать данные, которых мы не понимаем.
WITH broken AS (
    SELECT s.id AS src_id, e.id AS event_id
    FROM financial_events s
    JOIN financial_events e ON e.id = s.converted_to_event_id
    WHERE s.is_deleted = FALSE
      AND e.is_deleted = FALSE
      AND e.date IS NULL
),
killed AS (
    UPDATE financial_events e
       SET is_deleted = TRUE, updated_at = CURRENT_TIMESTAMP
      FROM broken b
     WHERE e.id = b.event_id
    RETURNING e.id
)
UPDATE financial_events s
   SET wishlist_status = 'OPEN',
       converted_to_event_id = NULL,
       updated_at = CURRENT_TIMESTAMP
  FROM broken b
 WHERE s.id = b.src_id;

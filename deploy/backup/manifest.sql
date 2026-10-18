-- Манифест копии (шаг 5 плана спринта 6): по строке на таблицу нашей базы — количество и отпечаток (md5 строк по
-- порядку), потом удаления из журнала. Тот же запрос — у копии (backup) и у проверки восстановления
-- (tools/restore-check.mjs): сверяется восстановленное с тем, что было при копии, а не с живой базой.
-- Запускает владелец таблиц: RLS его не ограничивает. Спека: docs/superpowers/specs/2026-10-18-backups-design.md
SELECT format('table %s.%s %s %s', table_schema, table_name,
        (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', table_schema, table_name),
                false, true, '')))[1]::text,
        (xpath('/row/h/text()', query_to_xml(format(
                'SELECT md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) AS h FROM %I.%I t',
                '|', '', table_schema, table_name), false, true, '')))[1]::text)
  FROM information_schema.tables
 WHERE table_schema IN ('public', 'ops') AND table_type = 'BASE TABLE'
 ORDER BY table_schema, table_name;

SELECT format('deletion %s %s %s', to_char(deleted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), kc_sub,
        coalesce(person_id::text, '-'))
  FROM ops.deletions
 ORDER BY deleted_at;

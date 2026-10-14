-- ANO-144: ограничитель изоляции. На каждой таблице с владельцем — политика RLS: строки видит и пишет только
-- владелец сеанса. Приложение ходит ролью selfin_app, на которую политики действуют; суперпользователь и
-- владелец таблиц RLS обходят — приложение под ними видело бы всё. Миграции идут прежней ролью.
-- Спека: docs/superpowers/specs/2026-10-14-row-level-security-design.md

-- Владелец сеанса — переменная selfin.user_id: её ставит пул приложения на каждое выданное соединение.
-- Пустая строка — NULL: с NULL не совпадёт ни одна строка, без владельца — ни строки и ни записи.
CREATE OR REPLACE FUNCTION current_owner() RETURNS UUID
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('selfin.user_id', true), '')::uuid $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'selfin_app') THEN
        -- Пароль разработки, стенда и CI; на сервере — свой (ANO-197): ALTER ROLE и SELFIN_DB_APP_PASSWORD.
        CREATE ROLE selfin_app LOGIN PASSWORD 'selfin-app-dev';
    END IF;
END $$;

-- budget_snapshots — ничья и не используется: доступа у приложения к ней нет.
GRANT USAGE ON SCHEMA public TO selfin_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    accounts, balance_checkpoints, capital_items, capital_revaluations, categories, financial_events,
    fund_account_links, fund_transactions, recurring_rule, target_funds, user_settings, users
    TO selfin_app;
-- Новым таблицам — те же права. Политику новой таблице с владельцем требует RowLevelSecurityIT.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO selfin_app;

-- users — без RLS: в ней сервер ищет владельца запроса, ещё его не зная.
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['accounts', 'balance_checkpoints', 'capital_items', 'capital_revaluations',
                               'categories', 'financial_events', 'fund_account_links', 'fund_transactions',
                               'recurring_rule', 'target_funds', 'user_settings']
    LOOP
        -- Переход до шага 8 плана спринта 5: без владельца — синтетический. Роли владельца таблиц (миграции,
        -- данные IT) так пишется как прежде; роли приложения такую строку отвергнет WITH CHECK — она не своя.
        EXECUTE format('ALTER TABLE %I ALTER COLUMN user_id SET DEFAULT COALESCE(current_owner(), %L::uuid)',
                       tbl, '00000000-0000-0000-0000-000000000001');
        -- Без FORCE: владелец таблиц — роль миграций — должен видеть всё, иначе миграция данных молча
        -- не нашла бы ни строки.
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('CREATE POLICY owner_rows ON %I USING (user_id = current_owner()) '
                       'WITH CHECK (user_id = current_owner())', tbl);
    END LOOP;
END $$;

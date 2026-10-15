-- ANO-147: второй слой изоляции в схеме. Ссылка — только на строку того же владельца, а владельца записи
-- ставит приложение (@TenantId), без синтетического запасного.
-- Спека: docs/superpowers/specs/2026-10-15-tenant-id-second-layer-design.md

-- Родителям — уникальность (user_id, id): на неё ссылается составной ключ. Она же индекс по владельцу —
-- прежние одиночные индексы четырёх родителей лишние.
ALTER TABLE accounts         ADD CONSTRAINT uq_accounts_owner_id         UNIQUE (user_id, id);
ALTER TABLE capital_items    ADD CONSTRAINT uq_capital_items_owner_id    UNIQUE (user_id, id);
ALTER TABLE categories       ADD CONSTRAINT uq_categories_owner_id       UNIQUE (user_id, id);
ALTER TABLE financial_events ADD CONSTRAINT uq_financial_events_owner_id UNIQUE (user_id, id);
ALTER TABLE recurring_rule   ADD CONSTRAINT uq_recurring_rule_owner_id   UNIQUE (user_id, id);
ALTER TABLE target_funds     ADD CONSTRAINT uq_target_funds_owner_id     UNIQUE (user_id, id);
DROP INDEX idx_accounts_owner, idx_capital_items_owner, idx_recurring_rule_owner, idx_target_funds_owner;

-- Каждая ссылка — составным ключом: проверка внешнего ключа идёт в обход RLS, и одиночный ключ пускал
-- ссылку на строку другого человека. Одиночный снимается по смыслу, а не по имени: у базы владельца имена
-- от Hibernate (как в V31). Удаление — как было; SET NULL — только колонке ссылки, иначе обнулился бы владелец.
DO $$
DECLARE
    ref RECORD;
    con RECORD;
BEGIN
    FOR ref IN
        SELECT * FROM (VALUES ('accounts',             'purpose_category_id',   'categories',       ''),
                              ('balance_checkpoints',  'account_id',            'accounts',         ''),
                              ('capital_revaluations', 'item_id',               'capital_items',    ''),
                              ('financial_events',     'category_id',           'categories',       ''),
                              ('financial_events',     'recurring_rule_id',     'recurring_rule',   ''),
                              ('financial_events',     'parent_event_id',       'financial_events', ''),
                              ('financial_events',     'target_fund_id',        'target_funds',     ''),
                              ('financial_events',     'converted_to_event_id', 'financial_events', 'ON DELETE SET NULL (converted_to_event_id)'),
                              ('financial_events',     'converted_to_fund_id',  'target_funds',     'ON DELETE SET NULL (converted_to_fund_id)'),
                              ('fund_account_links',   'fund_id',               'target_funds',     'ON DELETE CASCADE'),
                              ('fund_account_links',   'account_id',            'accounts',         ''),
                              ('fund_transactions',    'fund_id',               'target_funds',     ''),
                              ('fund_transactions',    'source_event_id',       'financial_events', ''),
                              ('recurring_rule',       'category_id',           'categories',       ''),
                              ('target_funds',         'account_id',            'accounts',         ''),
                              ('target_funds',         'converted_to_event_id', 'financial_events', 'ON DELETE SET NULL (converted_to_event_id)'),
                              ('target_funds',         'converted_to_fund_id',  'target_funds',     'ON DELETE SET NULL (converted_to_fund_id)'))
            AS t (tbl, col, parent, on_delete)
    LOOP
        FOR con IN
            SELECT c.conname
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = ref.col
             WHERE c.conrelid = ref.tbl::regclass
               AND c.contype = 'f'
               AND c.conkey = ARRAY[a.attnum]
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', ref.tbl, con.conname);
        END LOOP;
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (user_id, %I) REFERENCES %I (user_id, id) %s',
                       ref.tbl, 'fk_' || ref.tbl || '_' || ref.col, ref.col, ref.parent, ref.on_delete);
    END LOOP;
END $$;

-- Владельца записи ставит приложение. Синтетический запасной (переход V31–V32) снят: строка, вставленная без
-- владельца ролью владельца таблиц — миграцией данных, — молча досталась бы хозяину нынешних данных. Теперь
-- без владельца сеанса — отказ NOT NULL.
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['accounts', 'balance_checkpoints', 'capital_items', 'capital_revaluations',
                               'categories', 'financial_events', 'fund_account_links', 'fund_transactions',
                               'recurring_rule', 'target_funds', 'user_settings']
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN user_id SET DEFAULT current_owner()', tbl);
    END LOOP;
END $$;

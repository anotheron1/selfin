-- ANO-145, ANO-146: владелец в данных. Каждая строка одиннадцати таблиц получает владельца, и снимаются
-- четыре замка, из-за которых второй человек физически невозможен.
-- Спека: docs/superpowers/specs/2026-10-12-owner-in-data-design.md
--
-- Синтетический владелец — тот, кому принадлежат все нынешние данные. external_id (sub из токена) у него
-- пустой: связать его с владельцем при первом входе — ANO-195. Id постоянный: он же значение по умолчанию.

CREATE TABLE users (
    id          UUID PRIMARY KEY,
    external_id VARCHAR(255) UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO users (id) VALUES ('00000000-0000-0000-0000-000000000001');

-- Колонка с постоянным значением по умолчанию: Postgres с 11-й версии не перезаписывает таблицу, старые
-- строки получают синтетического владельца сразу. Значение по умолчанию — переход до шага 8 плана спринта 5:
-- код записи владельца пока не знает. В шаге 5 оно станет «владелец транзакции, без него — синтетический»,
-- в шаге 8 снимается. budget_snapshots остаётся ничьей — решение владельца 28.09.
ALTER TABLE accounts             ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE balance_checkpoints  ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE capital_items        ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE capital_revaluations ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE categories           ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE financial_events     ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE fund_account_links   ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE fund_transactions    ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE recurring_rule       ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE target_funds         ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);
ALTER TABLE user_settings        ADD COLUMN user_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES users (id);

-- Уникальности на всю базу снимаются по смыслу, а не по имени. На базе владельца история Flyway начинается
-- с версии 2: схему завёл Hibernate, и имена у ограничений его (ukt8o6pivur7nn124jehx7cygw5 и т.п.), а не из V1.
-- Если после этого уникальность ровно по такой колонке осталась (например, индексом) — падаем: второй человек
-- упёрся бы в неё молча.
DO $$
DECLARE
    target RECORD;
    con    RECORD;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES ('categories', 'name'),
                              ('financial_events', 'idempotency_key'),
                              ('fund_transactions', 'idempotency_key'),
                              ('user_settings', 'settings_key')) AS t (tbl, col)
    LOOP
        FOR con IN
            SELECT c.conname
              FROM pg_constraint c
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = target.col
             WHERE c.conrelid = target.tbl::regclass
               AND c.contype = 'u'
               AND c.conkey = ARRAY[a.attnum]
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', target.tbl, con.conname);
        END LOOP;

        IF EXISTS (SELECT 1
                     FROM pg_index i
                     JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                    WHERE i.indrelid = target.tbl::regclass
                      AND i.indisunique
                      AND i.indnatts = 1
                      AND a.attname = target.col) THEN
            RAISE EXCEPTION 'V31: в %(%) осталась уникальность без владельца — второй человек упрётся в неё', target.tbl, target.col;
        END IF;
    END LOOP;
END $$;

-- Замки — в пределах владельца. Ключ идемпотентности тоже: глобальная уникальность связывала бы людей —
-- вставка с чужим ключом падала бы 409 и выдавала, что такой ключ у кого-то есть.
ALTER TABLE categories        ADD CONSTRAINT uq_categories_owner_name              UNIQUE (user_id, name);
ALTER TABLE user_settings     ADD CONSTRAINT uq_user_settings_owner_key            UNIQUE (user_id, settings_key);
ALTER TABLE financial_events  ADD CONSTRAINT uq_financial_events_owner_idempotency UNIQUE (user_id, idempotency_key);
ALTER TABLE fund_transactions ADD CONSTRAINT uq_fund_transactions_owner_idempotency UNIQUE (user_id, idempotency_key);

DROP INDEX uq_accounts_single_default;
CREATE UNIQUE INDEX uq_accounts_single_default ON accounts (user_id) WHERE is_default AND is_deleted = FALSE;

-- У каждой таблицы — индекс, начинающийся с владельца: под внешний ключ и под фильтр владельца, который
-- добавит ограничитель (шаг 5). У четырёх таблиц его дают уникальности выше. События выбираются периодом.
CREATE INDEX idx_accounts_owner             ON accounts (user_id);
CREATE INDEX idx_balance_checkpoints_owner  ON balance_checkpoints (user_id);
CREATE INDEX idx_capital_items_owner        ON capital_items (user_id);
CREATE INDEX idx_capital_revaluations_owner ON capital_revaluations (user_id);
CREATE INDEX idx_fund_account_links_owner   ON fund_account_links (user_id);
CREATE INDEX idx_recurring_rule_owner       ON recurring_rule (user_id);
CREATE INDEX idx_target_funds_owner         ON target_funds (user_id);
CREATE INDEX idx_financial_events_owner_date ON financial_events (user_id, date);

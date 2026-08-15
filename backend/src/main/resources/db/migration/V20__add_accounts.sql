-- V20: счета (ANO-9).
-- Спека: docs/superpowers/specs/2026-08-12-accounts-skeleton-design.md §3, §7.
--
-- Инвариант миграции: кармашек после неё обязан совпасть с прежним до копейки.
-- Достигается тем, что создаётся ровно один счёт — одновременно дефолтный и
-- отслеживаемый — и все существующие чекпоинты адресуются ему.

CREATE TABLE accounts (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255)   NOT NULL,
    kind                VARCHAR(16)    NOT NULL
                        CHECK (kind IN ('DEBIT', 'CREDIT', 'DEPOSIT', 'CASH')),
    track_balance       BOOLEAN        NOT NULL,
    purpose_category_id UUID           REFERENCES categories(id),
    credit_limit        NUMERIC(19, 2),
    available_floor     NUMERIC(19, 2),
    is_default          BOOLEAN        NOT NULL DEFAULT FALSE,
    sort_order          INT            NOT NULL DEFAULT 100,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    is_deleted          BOOLEAN        NOT NULL DEFAULT FALSE,

    CONSTRAINT ck_accounts_credit_fields CHECK (
        kind = 'CREDIT' OR (credit_limit IS NULL AND available_floor IS NULL)),
    CONSTRAINT ck_accounts_floor_le_limit CHECK (
        available_floor IS NULL OR credit_limit IS NULL OR available_floor <= credit_limit),
    CONSTRAINT ck_accounts_deposit_tracked CHECK (
        kind <> 'DEPOSIT' OR track_balance),
    CONSTRAINT ck_accounts_default_tracked CHECK (
        NOT is_default OR (track_balance AND kind IN ('DEBIT', 'CASH')))
);

CREATE UNIQUE INDEX uq_accounts_single_default
    ON accounts ((is_default)) WHERE is_default AND NOT is_deleted;

CREATE INDEX idx_accounts_active ON accounts (sort_order) WHERE NOT is_deleted;

INSERT INTO accounts (name, kind, track_balance, is_default, sort_order)
VALUES ('Основная карта', 'DEBIT', TRUE, TRUE, 0);

ALTER TABLE balance_checkpoints ADD COLUMN account_id UUID REFERENCES accounts(id);
UPDATE balance_checkpoints SET account_id = (SELECT id FROM accounts WHERE is_default);
ALTER TABLE balance_checkpoints ALTER COLUMN account_id SET NOT NULL;
CREATE INDEX idx_checkpoints_account_date ON balance_checkpoints (account_id, date DESC);

ALTER TABLE target_funds ADD COLUMN account_id UUID REFERENCES accounts(id);
CREATE UNIQUE INDEX uq_funds_one_per_account
    ON target_funds (account_id) WHERE account_id IS NOT NULL AND NOT is_deleted;

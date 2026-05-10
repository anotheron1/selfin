-- V17: модуль «Капитал» — единицы капитала (активы / обязательства) и журнал переоценок.
-- Одна таблица для активов и обязательств различимы дискриминатором kind.

CREATE TABLE capital_items (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE capital_revaluations (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id    UUID           NOT NULL REFERENCES capital_items(id),
    value      NUMERIC(19, 2) NOT NULL CHECK (value >= 0),
    valued_at  DATE           NOT NULL,
    note       TEXT,
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN        NOT NULL DEFAULT FALSE
);

-- Партиционные индексы — поиск по живым записям.
CREATE INDEX idx_capital_items_active
    ON capital_items (kind)
    WHERE is_deleted = FALSE;

CREATE INDEX idx_capital_revaluations_item_at
    ON capital_revaluations (item_id, valued_at DESC, created_at DESC)
    WHERE is_deleted = FALSE;

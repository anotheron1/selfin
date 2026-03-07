-- Flyway V1: Инициализация схемы базы данных
-- Создаёт все таблицы приложения Selfin

CREATE TABLE categories (
    id UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    is_mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    PRIMARY KEY (id),
    CONSTRAINT categories_name_unique UNIQUE (name)
);

CREATE TABLE financial_events (
    id UUID NOT NULL,
    idempotency_key UUID,
    date DATE NOT NULL,
    category_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE', 'FUND_TRANSFER')),
    planned_amount NUMERIC(19, 2),
    fact_amount NUMERIC(19, 2),
    status VARCHAR(50) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'EXECUTED', 'CANCELLED')),
    is_mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(255),
    raw_input TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT financial_events_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT fk_event_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE target_funds (
    id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    target_amount NUMERIC(19, 2),
    current_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'FUNDING' CHECK (status IN ('FUNDING', 'REACHED')),
    priority INTEGER NOT NULL DEFAULT 100,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE TABLE fund_transactions (
    id UUID NOT NULL,
    fund_id UUID NOT NULL,
    source_event_id UUID,
    idempotency_key UUID,
    amount NUMERIC(19, 2) NOT NULL,
    transaction_date DATE NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT fund_transactions_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT fk_transaction_fund FOREIGN KEY (fund_id) REFERENCES target_funds (id),
    CONSTRAINT fk_transaction_event FOREIGN KEY (source_event_id) REFERENCES financial_events (id)
);

CREATE TABLE budget_snapshots (
    id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    snapshot_date TIMESTAMP NOT NULL DEFAULT NOW(),
    snapshot_data JSONB,
    PRIMARY KEY (id)
);

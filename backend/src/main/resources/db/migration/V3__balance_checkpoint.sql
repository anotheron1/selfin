-- Flyway V3: Таблица чекпоинтов баланса счёта
-- Хранит привязанные к дате зафиксированные остатки для корректного расчёта
-- Текущего баланса и прогноза на конец месяца.

CREATE TABLE balance_checkpoints (
    id         UUID           NOT NULL DEFAULT gen_random_uuid(),
    date       DATE           NOT NULL,
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

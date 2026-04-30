-- V16: повторяющиеся финансовые события (recurring rule + FK на events)

CREATE TABLE recurring_rule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID        NOT NULL REFERENCES categories(id),
    event_type     VARCHAR(20) NOT NULL CHECK (event_type IN ('INCOME', 'EXPENSE')),
    planned_amount NUMERIC(19,2) NOT NULL CHECK (planned_amount >= 0),
    priority       VARCHAR(10) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    description    VARCHAR(255),
    frequency      VARCHAR(10) NOT NULL CHECK (frequency IN ('MONTHLY', 'YEARLY')),
    day_of_month   INTEGER     NOT NULL CHECK (day_of_month BETWEEN 1 AND 31),
    month_of_year  INTEGER     CHECK (month_of_year BETWEEN 1 AND 12),
    start_date     DATE        NOT NULL,
    end_date       DATE,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP,
    CONSTRAINT chk_monthly_no_month CHECK (frequency <> 'MONTHLY' OR month_of_year IS NULL),
    CONSTRAINT chk_yearly_has_month CHECK (frequency <> 'YEARLY'  OR month_of_year IS NOT NULL),
    CONSTRAINT chk_end_after_start  CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_recurring_rule_not_deleted
    ON recurring_rule (is_deleted) WHERE is_deleted = FALSE;

ALTER TABLE financial_events
    ADD COLUMN recurring_rule_id UUID REFERENCES recurring_rule(id);

CREATE INDEX idx_events_recurring_rule
    ON financial_events (recurring_rule_id)
    WHERE recurring_rule_id IS NOT NULL;

-- Защита от дублирования при lazy-extend (см. spec → Секция 2 Конкурентность)
CREATE UNIQUE INDEX uq_events_rule_date_active
    ON financial_events (recurring_rule_id, date)
    WHERE recurring_rule_id IS NOT NULL AND is_deleted = FALSE;

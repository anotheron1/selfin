-- recurring_rule: template that drives periodic event generation
CREATE TABLE recurring_rule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id    UUID        NOT NULL REFERENCES category(id),
    event_type     VARCHAR(20) NOT NULL,
    target_fund_id UUID        REFERENCES target_fund(id),
    planned_amount NUMERIC(19,2) NOT NULL,
    mandatory      BOOLEAN     NOT NULL DEFAULT FALSE,
    description    VARCHAR(255),
    frequency      VARCHAR(10) NOT NULL,
    day_of_month   INTEGER,
    day_of_week    VARCHAR(10),
    start_date     DATE        NOT NULL,
    end_date       DATE,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Link each generated event back to its rule
ALTER TABLE financial_events
    ADD COLUMN recurring_rule_id UUID REFERENCES recurring_rule(id);

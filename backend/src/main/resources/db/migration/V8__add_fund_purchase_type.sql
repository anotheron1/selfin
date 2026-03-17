-- Flyway V8: Add purchase type and credit fields to target_funds
-- Adds support for Savings Strategy Planner feature (SAVINGS vs CREDIT purchase types)

ALTER TABLE target_funds
    ADD COLUMN purchase_type VARCHAR(20) NOT NULL DEFAULT 'SAVINGS'
        CHECK (purchase_type IN ('SAVINGS', 'CREDIT')),
    ADD COLUMN credit_rate   NUMERIC(5, 2)
        CHECK (credit_rate > 0),
    ADD COLUMN credit_term_months INTEGER;

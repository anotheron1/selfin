-- Flyway V8: Add purchase type and credit fields to target_funds
-- Adds support for Savings Strategy Planner feature (SAVINGS vs CREDIT purchase types)

ALTER TABLE target_funds
    ADD COLUMN purchase_type VARCHAR(20) NOT NULL DEFAULT 'SAVINGS',
    ADD COLUMN credit_rate   NUMERIC(5, 2),
    ADD COLUMN credit_term_months INTEGER;

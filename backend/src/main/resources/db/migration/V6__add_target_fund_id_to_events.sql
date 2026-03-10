ALTER TABLE financial_events
    ADD COLUMN target_fund_id UUID REFERENCES target_fund(id);

-- Заменяем булев флаг mandatory на трёхуровневый enum priority
-- HIGH = обязательный (бывший mandatory=true)
-- MEDIUM = необязательный (бывший mandatory=false, дефолт)
-- LOW = хотелка

ALTER TABLE categories ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
UPDATE categories SET priority = 'HIGH' WHERE is_mandatory = true;
ALTER TABLE categories DROP COLUMN is_mandatory;

ALTER TABLE financial_events ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
UPDATE financial_events SET priority = 'HIGH' WHERE is_mandatory = true;
ALTER TABLE financial_events DROP COLUMN is_mandatory;

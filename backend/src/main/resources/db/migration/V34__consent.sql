-- ANO-196: согласие на обработку персональных данных — какую версию текста человек принял и когда поставил галочку.
-- Спека: docs/superpowers/specs/2026-10-18-consent-design.md
--
-- Пусто у заведённых до согласия: это учётные записи оператора — синтетический владелец и стенд.
ALTER TABLE users ADD COLUMN consent_version VARCHAR(32);
ALTER TABLE users ADD COLUMN consent_at TIMESTAMP WITH TIME ZONE;

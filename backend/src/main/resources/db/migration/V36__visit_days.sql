-- ANO-102: дни визита — «открывал» и «записывал» по дням (шаг 13 плана спринта 6).
-- Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
--
-- Строка за день — «открывал»: её ставит любой запрос человека. wrote — в этот день был удачный запрос к ручке,
-- которая пишет данные (разметка @WritesData). Только даты: время визита и что смотрел — не храним.
CREATE TABLE visit_days (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL DEFAULT current_owner() REFERENCES users (id),
    day     DATE NOT NULL,
    wrote   BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_visit_days_owner_day UNIQUE (user_id, day)
);

-- Как у остальных таблиц с владельцем (V32): без FORCE — владелец таблиц, роль миграций, видит всё.
ALTER TABLE visit_days ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_rows ON visit_days USING (user_id = current_owner()) WITH CHECK (user_id = current_owner());
GRANT SELECT, INSERT, UPDATE, DELETE ON visit_days TO selfin_app;

-- Журнал удалений людей (шаг 5 плана спринта 6, ANO-197): кого и когда удалили — sub Keycloak и наш id. После
-- восстановления из копии удаления повторяются по нему, иначе удалённый вернулся бы молча.
-- Схема ops — вне досягаемости приложения: V32 даёт selfin_app права только на public. Пишет tools/delete-person.mjs
-- ролью владельца таблиц, той же транзакцией, что удаляет строки человека.
-- Спека: docs/superpowers/specs/2026-10-18-backups-design.md
CREATE SCHEMA ops;

CREATE TABLE ops.deletions (
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    kc_sub     VARCHAR(255) NOT NULL,
    person_id  UUID
);

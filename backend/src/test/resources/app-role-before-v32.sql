-- Роль приложения для IT на схеме до V32 (ANO-229): её заводит только V32, а под суперпользователем контейнера
-- приложение не стартует. Права — как в V32: на таблицы, которые затем создаст Flyway ролью контейнера.
CREATE ROLE selfin_app LOGIN PASSWORD 'selfin-app-dev';
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO selfin_app;

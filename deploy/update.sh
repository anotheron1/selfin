#!/bin/sh
# Обновление сервера (шаг 5 плана спринта 6): сначала копия — миграции меняют данные. Копия не прошла — обновления нет.
# Тег образов — SELFIN_TAG в .env: sha-<коммит>, у которого опубликованы оба образа (план, шаг 6).
set -eu
cd "$(dirname "$0")"
docker compose exec -T backup backup
docker compose pull
docker compose up -d --build

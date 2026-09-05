#!/usr/bin/env bash
# ANO-50: пересоздать тестовый стенд.
#   tools/ano50-reset-stand.sh              — пустая база, флайвей поднимает с нуля (новый пользователь)
#   tools/ano50-reset-stand.sh <файл.sql>   — восстановить из дампа
#
# Боевая база не участвует: все команды бьют по контейнеру selfin-test-db.
set -euo pipefail

DUMP="${1:-}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.test.yml"
export COMPOSE_PROJECT_NAME=selfin-test

$COMPOSE stop backend >/dev/null
docker exec selfin-test-db psql -U admin -d postgres -c "DROP DATABASE selfin" >/dev/null
docker exec selfin-test-db psql -U admin -d postgres -c "CREATE DATABASE selfin OWNER admin" >/dev/null
if [ -n "$DUMP" ]; then
  docker exec -i selfin-test-db psql -U admin -d selfin -q < "$DUMP" >/dev/null
  echo "восстановлено из $DUMP"
else
  echo "база пустая, флайвей поднимет схему с нуля"
fi
$COMPOSE start backend >/dev/null

for i in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/v1/pocket)" = "200" ]; then
    echo "бэкенд поднялся за ${i} попыток"
    exit 0
  fi
  sleep 2
done
echo "бэкенд не поднялся" >&2
exit 1

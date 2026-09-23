#!/usr/bin/env bash
# Проверка миграций на данных (ANO-26).
#
# База встаёт на версию main, данные в неё пишет код main через API, поверх запускается
# бэкенд из PR — его миграции ложатся на заполненные таблицы, как на боевой базе. Затем:
#
#   1. каждая миграция, которую добавил PR, действительно применена. Flyway молча пропускает
#      файл с неверным именем или не в том каталоге — без этой проверки отчёт сказал бы
#      «новых миграций нет», а боевая база так и не получила бы изменение;
#   2. живые данные пережили миграцию: по каждой таблице совпадают число живых строк и суммы
#      денежных столбцов, а через API видно столько же записей, сколько до. Миграция, которая
#      помечает строки удалёнными или прячет их от чтения, отвечает 200 на всех ручках и не
#      меняет общее число строк — ловится только так. Если PR меняет данные намеренно, на нём
#      ставится метка «меняет-данные» (CI_DATA_CHANGE_EXPECTED=true): расхождение показывается,
#      но проверку не валит;
#   3. ручки чтения отвечают 200 на данных, записанных кодом main.
#
#   tools/ci-migrations.sh <образ бэка main> <образ бэка PR> <сеятель.mjs> [<исходники main> <исходники PR>]
#
# Без каталогов исходников первая проверка пропускается — так скрипт гоняется локально с одним
# образом дважды. Поднимает свои контейнеры selfin-ci-db и selfin-ci-app (порт 8099) и убирает
# их за собой; боевую базу и стенд не трогает. Вся логика здесь, а не в workflow, — чтобы
# гонялась и локально.
#
# Спека: docs/superpowers/specs/2026-09-23-ci-on-pr-design.md
set -euo pipefail

BASE_IMAGE=${1:?образ бэка main}
HEAD_IMAGE=${2:?образ бэка PR}
SEEDER=${3:?путь к сеятелю}
BASE_SRC=${4:-}
HEAD_SRC=${5:-}
DATA_CHANGE_EXPECTED=${CI_DATA_CHANGE_EXPECTED:-false}
PORT=${CI_APP_PORT:-8099}
API="http://localhost:${PORT}/api/v1"
NET=selfin-ci-net
DB=selfin-ci-db
APP=selfin-ci-app
MIGRATIONS=backend/src/main/resources/db/migration

# ── 1а. Файлы миграций PR, которые Flyway пропустит молча — до всяких контейнеров ──
expected=()
if [ -n "$BASE_SRC" ] && [ -n "$HEAD_SRC" ]; then
  skipped=()
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    if [[ "$f" =~ ^V([0-9]+(\.[0-9]+)*)__.+\.sql$ ]]; then
      expected+=("${BASH_REMATCH[1]}")
    else
      skipped+=("$MIGRATIONS/$f — Flyway не распознает имя, нужно V<номер>__<описание>.sql")
    fi
  done < <(LC_ALL=C comm -13 <(ls "$BASE_SRC/$MIGRATIONS" | LC_ALL=C sort) <(ls "$HEAD_SRC/$MIGRATIONS" | LC_ALL=C sort))
  stray() { (cd "$1" && find backend/src/main/resources -name '*.sql' ! -path "*/db/migration/*" | LC_ALL=C sort); }
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    skipped+=("$f — лежит вне $MIGRATIONS, Flyway его не увидит")
  done < <(LC_ALL=C comm -13 <(stray "$BASE_SRC") <(stray "$HEAD_SRC"))
  if [ ${#skipped[@]} -gt 0 ]; then
    echo "::error::SQL-файлы PR, которые Flyway пропустит молча — на боевую базу они не попадут"
    printf '    %s\n' "${skipped[@]}"
    exit 1
  fi
fi

remove_containers() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
remove_containers
WORK=$(mktemp -d)
trap 'remove_containers; rm -rf "$WORK"' EXIT

psql_q() { docker exec "$DB" psql -U admin -d selfin -At -F '|' -c "$1"; }

start_app() {
  local image=$1
  docker run -d --name "$APP" --network "$NET" -p "${PORT}:8080" \
    -e TZ=Europe/Moscow \
    -e SPRING_DATASOURCE_URL="jdbc:postgresql://${DB}:5432/selfin" \
    -e SPRING_DATASOURCE_USERNAME=admin \
    -e SPRING_DATASOURCE_PASSWORD=admin \
    "$image" >/dev/null
  for _ in $(seq 1 90); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$API/pocket" || true)" = "200" ]; then
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$APP" 2>/dev/null)" != "true" ]; then
      break
    fi
    sleep 2
  done
  echo "::error::бэкенд из образа $image не поднялся на базе с данными"
  docker logs --tail 150 "$APP" 2>&1 | sed 's/^/    /'
  return 1
}

# Строк в каждой таблице схемы public, считая удалённые, — одним запросом, без списка таблиц.
counts() {
  psql_q "SELECT table_name, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I', table_name), false, true, '')))[1]::text
          FROM information_schema.tables
          WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
          ORDER BY table_name" | LC_ALL=C sort
}

# Отпечаток живых данных: по каждой таблице — живые строки (без помеченных удалёнными) и
# сумма каждого числового столбца по ним. Строится по схеме, какая есть: новые столбцы и
# таблицы PR в сравнении не мешают, пропавшие — считаются изменением.
fingerprint() {
  psql_q "WITH t AS (
            SELECT table_name,
                   EXISTS (SELECT 1 FROM information_schema.columns c
                           WHERE c.table_schema = 'public' AND c.table_name = x.table_name
                             AND c.column_name = 'deleted') AS soft
            FROM information_schema.tables x
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'),
          n AS (
            SELECT c.table_name, c.column_name, t.soft
            FROM information_schema.columns c JOIN t USING (table_name)
            WHERE c.table_schema = 'public' AND c.data_type = 'numeric')
          SELECT table_name || ': живых строк|' || (xpath('/row/v/text()', query_to_xml(format(
                   'SELECT count(*) AS v FROM %I %s', table_name,
                   CASE WHEN soft THEN 'WHERE deleted = false' ELSE '' END), false, true, '')))[1]::text
          FROM t
          UNION ALL
          SELECT table_name || ': сумма ' || column_name || '|' || coalesce((xpath('/row/v/text()', query_to_xml(format(
                   'SELECT sum(%I) AS v FROM %I %s', column_name, table_name,
                   CASE WHEN soft THEN 'WHERE deleted = false' ELSE '' END), false, true, '')))[1]::text, '0')
          FROM n"
}

# Сколько записей видно через API — миграция может спрятать строки от чтения, не тронув их.
API_COUNTED=(
  "/events?startDate=2000-01-01&endDate=2100-12-31"
  /events/wishlist
  /accounts
  /categories
  /balance-checkpoints
  /capital/items
)
api_counts() {
  for ep in "${API_COUNTED[@]}"; do
    n=$(curl -s "$API$ep" | node -e 'let s = ""; process.stdin.on("data", (d) => { s += d; }).on("end", () => {
      try { const j = JSON.parse(s); console.log(Array.isArray(j) ? j.length : "не список"); }
      catch { console.log("не JSON"); } });')
    echo "через API ${ep%%\?*}: записей|$n"
  done
}

live_state() { { fingerprint; api_counts; } | LC_ALL=C sort; }

docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" \
  -e POSTGRES_DB=selfin -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin \
  postgres:15-alpine >/dev/null
# pg_isready — через TCP: через unix-сокет на свежем томе он отвечает раньше, чем сервер готов.
for _ in $(seq 1 60); do
  docker exec "$DB" pg_isready -h 127.0.0.1 -U admin -d selfin >/dev/null 2>&1 && break
  sleep 1
done

echo "── база на версии main, данные пишет код main: $BASE_IMAGE"
start_app "$BASE_IMAGE"
TZ=Europe/Moscow node "$SEEDER" --api "$API"
version_base=$(psql_q "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
rank_base=$(psql_q "SELECT max(installed_rank) FROM flyway_schema_history")
counts > "$WORK/before"
live_state > "$WORK/live_before"
docker rm -f "$APP" >/dev/null

echo "── поверх — бэкенд из PR: $HEAD_IMAGE"
start_app "$HEAD_IMAGE"
version_head=$(psql_q "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
applied=$(psql_q "SELECT 'V' || version || ' — ' || description FROM flyway_schema_history WHERE installed_rank > ${rank_base} ORDER BY installed_rank")
applied_versions=$(psql_q "SELECT version FROM flyway_schema_history WHERE success AND installed_rank > ${rank_base}")
counts > "$WORK/after"
live_state > "$WORK/live_after"

failures=()

# ── 1б. Каждая миграция, которую добавил PR, применена ──────────────────────────
for v in "${expected[@]}"; do
  if ! grep -qx "$v" <<< "$applied_versions"; then
    failures+=("миграция V$v есть в PR, но Flyway её не применил")
  fi
done

# ── 2. Живые данные пережили миграцию ────────────────────────────────────────
# Показатель, которого до PR не было (новый столбец, новая таблица), изменением не считается;
# пропавший — считается.
changed=$(LC_ALL=C join -t '|' -a 1 -a 2 -e 'нет' -o 0,1.2,2.2 "$WORK/live_before" "$WORK/live_after" \
  | awk -F '|' '$2 != $3 && $2 != "нет"')
indicators=$(wc -l < "$WORK/live_before" | tr -d ' ')
if [ -n "$changed" ] && [ "$DATA_CHANGE_EXPECTED" != "true" ]; then
  failures+=("живые данные изменились после миграций PR — если так задумано, поставь на PR метку «меняет-данные»")
fi

# ── 3. Ручки чтения отвечают на данных, записанных кодом main ──────────────────
FROM=$(date -d "$(date +%Y-%m-01) -5 months" +%F)
TO=$(date +%F)
ENDPOINTS=(
  /pocket
  /analytics/dashboard
  /analytics/report
  /analytics/forecast
  /analytics/forecast-readiness
  "/analytics/multi-month?startDate=${FROM}&endDate=${TO}"
  "/events?startDate=2000-01-01&endDate=2100-12-31"
  /events/wishlist
  /funds
  /funds/planner
  /accounts
  /categories
  /balance-checkpoints
  /capital/items
  /capital/summary
  /capital/trajectory
  /settings/pocket
  /settings/wishlist
  /snapshots
  /strategy/timeline
  /wishlist/simulation
)
silent=()
for ep in "${ENDPOINTS[@]}"; do
  code=$(curl -s -o "$WORK/body" -w '%{http_code}' "$API$ep" || true)
  if [ "$code" != "200" ]; then
    silent+=("$ep → $code: $(head -c 300 "$WORK/body")")
  fi
done
if [ ${#silent[@]} -gt 0 ]; then
  failures+=("ручки чтения не ответили 200 на данных, записанных кодом main")
fi

{
  echo "### Миграции на данных"
  echo
  echo "Схема: main — V${version_base}, после PR — V${version_head}."
  if [ -n "$applied" ]; then
    echo "Миграции PR, легшие на заполненную базу:"
    echo
    echo "$applied" | sed 's/^/* /'
  else
    echo "Новых миграций в PR нет — проверено, что код PR читает данные, записанные кодом main."
  fi
  if [ -z "$BASE_SRC" ]; then
    echo
    echo "Сверка файлов миграций с применёнными пропущена: каталоги исходников не переданы."
  fi
  echo
  if [ -z "$changed" ]; then
    echo "Живые данные не изменились: совпали все ${indicators} показателей — живые строки и суммы денежных столбцов по таблицам, записи через API."
  else
    if [ "$DATA_CHANGE_EXPECTED" = "true" ]; then
      echo "Живые данные изменились, и это помечено как ожидаемое (метка «меняет-данные»):"
    else
      echo "**Живые данные изменились** — метки «меняет-данные» на PR нет:"
    fi
    echo
    echo "| показатель | после main | после PR |"
    echo "|---|---:|---:|"
    echo "$changed" | awk -F '|' '{ printf "| %s | %s | %s |\n", $1, $2, $3 }'
  fi
  echo
  echo "Ручек чтения обойдено: ${#ENDPOINTS[@]}, не ответили 200: ${#silent[@]}."
  echo
  echo "| таблица | строк после main | строк после PR |"
  echo "|---|---:|---:|"
  LC_ALL=C join -t '|' -a 1 -a 2 -e '—' -o 0,1.2,2.2 "$WORK/before" "$WORK/after" \
    | awk -F '|' '{ mark = ($2 == $3) ? "" : " ← изменилось"; printf "| %s | %s | %s%s |\n", $1, $2, $3, mark }'
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ ${#failures[@]} -gt 0 ]; then
  echo
  for f in "${failures[@]}"; do echo "::error::$f"; done
  [ ${#silent[@]} -gt 0 ] && printf '    %s\n' "${silent[@]}"
  [ ${#silent[@]} -gt 0 ] && docker logs --tail 150 "$APP" 2>&1 | sed 's/^/    /'
  exit 1
fi

#!/bin/sh
# Выкатка кнопкой (ANO-246, шаг 1 плана спринта 7): сервер сам переходит на коммит ветки release, когда её сдвинула
# кнопка «Выкатить на сервер» (tools/release.mjs). Запускает таймер systemd раз в 2 минуты (deploy/systemd/); вручную —
# systemctl start selfin-release, журнал — journalctl -u selfin-release.
# Спека: docs/superpowers/specs/2026-10-27-release-button-design.md
#
# Таймер зовёт копию скрипта вне выкачанного дерева — /usr/local/lib/selfin/release.sh: возврат на старый коммит может
# убрать deploy/release.sh из дерева, и таймер звал бы несуществующий файл (ревью Codex на #202). Новую версию себя
# копия берёт из дерева только после вставшей выкатки.
#
# Что работает — спрашиваем у самого сервера, а не у файла: восстановление из копии или ручное обновление файл бы
# обманули. Не встала новая версия — она запоминается в .release-failed и больше не пробуется (каждый повтор снимал бы
# копию). Миграций между версиями нет — сервер возвращается на прежнюю; есть — не возвращается: старый код может не
# сойтись с новой схемой, и откат — только из копии (RESTORE.md), снятой перед обновлением.
set -eu

# Всё — внутри функции, вызов — одной строкой в конце: если запущенный файл перепишут на месте (установка через cp
# поверх, пока таймер работает), функцию оболочка уже прочла целиком и чужого не исполнит.
main() {
  # Где лежит сам скрипт — до cd: из каталога deploy относительный путь к себе уже не найти.
  self=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
  # Каталог deploy — SELFIN_DEPLOY (таймер ставит /opt/selfin/deploy); без неё — каталог скрипта, когда его зовут из дерева.
  cd "${SELFIN_DEPLOY:-$(dirname "$0")}"
  target=$(git -C .. ls-remote origin refs/heads/release | cut -f1)
  if [ -z "$target" ]; then
    echo "ветки release ещё нет — жду кнопку"
    return 0
  fi
  before=$(live || true)
  [ "$target" != "$before" ] || return 0
  [ "$target" != "$(cat .release-failed 2>/dev/null || true)" ] || return 0
  grep -q '^SELFIN_TAG=' .env || { echo "в .env нет строки SELFIN_TAG — тег новой версии записать некуда" >&2; return 1; }

  echo "release → $target, сейчас ${before:-сервер не отвечает}"
  git -C .. fetch -q origin release
  git -C .. checkout -q --detach "$target"
  tag "$target"
  if ./update.sh && serves "$target"; then
    rm -f .release-failed
    # Себя — из вставшей версии и переименованием, а не поверх: оборвись копирование — таймеру остаётся прежний целый
    # файл, а не половина нового.
    cp release.sh "$self.new" && chmod +x "$self.new" && mv -f "$self.new" "$self" \
      || echo "скрипт выкатки себя не обновил — остаётся прежним" >&2
    echo "сервер на $target"
    return 0
  fi

  echo "$target" > .release-failed
  echo "не встала $target — больше её не пробую, до следующего сдвига release"
  if [ -z "$before" ]; then
    echo "что работало до неё, неизвестно — назад не иду"
  elif git -C .. diff --quiet "$before" "$target" -- backend/src/main/resources/db/migration; then
    git -C .. checkout -q --detach "$before"
    tag "$before"
    ./update.sh && serves "$before" && echo "вернулся на $before"
  else
    echo "между $before и $target есть миграции — назад не иду: старый код может не сойтись с новой схемой;" \
      "восстановление — deploy/RESTORE.md, копия снята перед обновлением"
  fi
  return 1
}

# Тег образов коммита — sha-<7 знаков>, как публикует publish-images.yml; update.sh читает его из .env.
tag() { sed -i "s/^SELFIN_TAG=.*/SELFIN_TAG=sha-$(printf '%.7s' "$1")/" .env; }

# Версия бэка — через nginx экранов, как её видит человек: не встал любой из двух — версии нет. Ошибки curl молчат:
# пока бэк поднимается, каждая попытка дала бы строку в журнал (репетиция 27.10 — 120 строк за две выкатки).
live() {
  port=$(sed -n 's/^SELFIN_HTTP_PORT=//p' .env)
  curl -fs -m 10 "http://127.0.0.1:${port:-8280}/api/v1/version" | sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p'
}

# Новая версия отвечает за 5 минут — по часам, а не по числу попыток: пока бэка нет, nginx экранов ждёт его ещё 5 секунд
# на каждую попытку (proxy_connect_timeout), и 60 попыток растягивались до 10 минут (репетиция 27.10). Бэк поднимается
# около 40 секунд, Keycloak, если его образ сменился, — до двух минут.
serves() {
  deadline=$(( $(date +%s) + 300 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    [ "$(live || true)" != "$1" ] || return 0
    sleep 5
  done
  echo "за 5 минут сервер не ответил версией $1"
  return 1
}

main "$@"; exit

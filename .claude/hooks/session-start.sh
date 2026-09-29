#!/bin/bash
# Старт облачной сессии Claude Code — хук SessionStart из .claude/settings.json.
# Облачная машина приходит с выключенным демоном Docker и без node_modules во фронте:
# без первого не встают IT на Testcontainers и стенд, без второго — тесты фронта.
# Хук из settings.json запускается и локально, поэтому вне облака он сразу выходит:
# там Docker Desktop и свои node_modules. Проверено 29.09 — ретро 2026-09-29-sprint-retro.md.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
done_items=()

# Демон Docker. Все потоки демона — в файл и в новую сессию процессов:
# иначе он держит вывод хука, и старт сессии ждёт его до таймаута.
if command -v dockerd >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
  if ! pgrep -x dockerd >/dev/null; then
    rm -f /var/run/docker.pid
    setsid dockerd >/tmp/dockerd.log 2>&1 </dev/null &
  fi
  for _ in $(seq 1 30); do
    docker info >/dev/null 2>&1 && break
    sleep 1
  done
  if docker info >/dev/null 2>&1; then
    done_items+=("демон Docker поднят")
  else
    echo "Демон Docker не поднялся за 30 с — /tmp/dockerd.log" >&2
  fi
fi

# Зависимости фронта — npm ci по package-lock.json, как в CI. Метка — хэш lock-файла:
# повторный старт той же машины не ставит заново, новый lock-файл — ставит.
front="$root/frontend"
if [ -f "$front/package-lock.json" ]; then
  want=$(sha256sum "$front/package-lock.json" | cut -d' ' -f1)
  stamp="$front/node_modules/.lock-sha256"
  if [ "$(cat "$stamp" 2>/dev/null)" != "$want" ]; then
    if (cd "$front" && npm ci --no-audit --no-fund >/tmp/npm-ci.log 2>&1); then
      echo "$want" >"$stamp"
      done_items+=("зависимости фронта поставлены")
    else
      echo "npm ci во frontend не прошёл — /tmp/npm-ci.log" >&2
    fi
  fi
fi

if [ ${#done_items[@]} -gt 0 ]; then
  joined=$(printf '%s, ' "${done_items[@]}")
  echo "Облачная сессия: ${joined%, }."
fi
exit 0

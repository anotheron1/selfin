// Скрипт сервера (deploy/release.sh, ANO-246): сервер сам переходит на коммит ветки release. Заглушки: git пишет в
// журнал и держит «checkout» в файле, curl отвечает версией «работающих контейнеров», update.sh поднимает тот коммит,
// что выкачан, — или не поднимает, если тест сломал версию. Запуск — bash, как у копий: на сервере скрипт идёт под sh.
// Спека: docs/superpowers/specs/2026-10-27-release-button-design.md
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { delimiter, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const SCRIPT = fileURLToPath(new URL('../deploy/release.sh', import.meta.url));
const unix = (path) => path.replaceAll('\\', '/');
const A = 'a'.repeat(40);
const B = 'b'.repeat(40);

/**
 * Мир сервера: `running` — чем отвечает версия (null — сервер не отвечает), `release` — коммит ветки (null — ветки нет),
 * `failed` — что лежит в .release-failed, `broken` — коммиты, которые update.sh «поднимает», а они не отвечают,
 * `migrations` — между версиями есть миграции, `updateFails` — update.sh падает (копия не снялась).
 */
function server({ running = A, release = B, failed = null, broken = [], migrations = false, updateFails = false,
  env = `SELFIN_TAG=sha-${A.slice(0, 7)}\n`, hijack = false, slowCurl = false } = {}) {
  const root = unix(mkdtempSync(join(tmpdir(), 'selfin-release-')));
  const bin = `${root}/bin`;
  const deploy = `${root}/repo/deploy`;
  const log = `${root}/calls.log`;
  mkdirSync(bin, { recursive: true });
  mkdirSync(deploy, { recursive: true });
  const stub = (path, body) => {
    writeFileSync(path, `#!/bin/sh\n${body}\n`);
    chmodSync(path, 0o755);
  };
  writeFileSync(`${deploy}/.env`, env);
  if (running) writeFileSync(`${root}/running`, running);
  if (release) writeFileSync(`${root}/release`, release);
  if (failed) writeFileSync(`${deploy}/.release-failed`, `${failed}\n`);
  if (migrations) writeFileSync(`${root}/migrations`, '');
  if (updateFails) writeFileSync(`${root}/update-fails`, '');
  for (const sha of broken) writeFileSync(`${root}/broken-${sha}`, '');
  // Скрипт запускается копией в каталоге deploy: checkout в тесте может подменить её на ходу, как git на сервере.
  const script = `${deploy}/release.sh`;
  writeFileSync(script, readFileSync(SCRIPT, 'utf8'));
  stub(`${bin}/git`, `
[ "$1" = -C ] && shift 2
echo "git $*" >> "${log}"
case "$1" in
  ls-remote) [ -f "${root}/release" ] && printf '%s\\trefs/heads/release\\n' "$(cat "${root}/release")"; exit 0 ;;
  fetch) exit 0 ;;
  checkout) for a; do sha=$a; done; echo "$sha" > "${root}/head"
    ${hijack ? `i=0; while [ $i -lt 600 ]; do echo 'echo HIJACK'; i=$((i + 1)); done > "${script}"` : ''}
    exit 0 ;;
  diff) [ -f "${root}/migrations" ] && exit 1; exit 0 ;;
esac
exit 3`);
  // Часы — поддельные: их двигают sleep и «медленный» curl. Пока бэка нет, nginx экранов ждёт его 5 секунд на каждую
  // попытку (proxy_connect_timeout) — slowCurl так и отвечает.
  writeFileSync(`${root}/clock`, '0');
  const tick = (by) => `echo $(( $(cat "${root}/clock") + ${by} )) > "${root}/clock"`;
  stub(`${bin}/date`, `echo $(( 1000000 + $(cat "${root}/clock") ))`);
  // С -S настоящий curl печатает ошибку на каждую неудачную попытку — заглушка тоже.
  stub(`${bin}/curl`, `echo "curl $*" >> "${log}"
loud=; for a; do case "$a" in --*) ;; -*S*) loud=1 ;; esac; done
[ -f "${root}/running" ] || { ${slowCurl ? tick(5) : ':'}; [ -z "$loud" ] || echo "curl: (7) Failed to connect" >&2; exit 7; }
printf '{"commit":"%s"}' "$(cat "${root}/running")"`);
  stub(`${bin}/sleep`, `echo "sleep $*" >> "${log}"; ${tick('$1')}`);
  stub(`${deploy}/update.sh`, `
tag=$(sed -n 's/^SELFIN_TAG=//p' .env)
echo "update.sh $tag" >> "${log}"
[ -f "${root}/update-fails" ] && exit 1
head=$(cat "${root}/head")
[ "sha-$(printf '%.7s' "$head")" = "$tag" ] || { echo "update.sh: тег $tag, а выкачан $head" >> "${log}"; exit 1; }
if [ -f "${root}/broken-$head" ]; then rm -f "${root}/running"; else echo "$head" > "${root}/running"; fi`);
  const shellEnv = { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, SELFIN_DEPLOY: deploy };
  const run = spawnSync('bash', [script], { env: shellEnv, encoding: 'utf8' });
  const read = (path) => (existsSync(path) ? readFileSync(path, 'utf8') : null);
  const calls = read(log) ?? '';
  const result = {
    run, calls, root,
    env: read(`${deploy}/.env`),
    failed: read(`${deploy}/.release-failed`)?.trim() ?? null,
    running: read(`${root}/running`)?.trim() ?? null,
    updates: calls.split('\n').filter((l) => l.startsWith('update.sh ')),
  };
  rmSync(root, { recursive: true, force: true });
  return result;
}

test('ветки release ещё нет — ждёт кнопку: выход без ошибки, ни выкачки, ни обновления', () => {
  const s = server({ release: null });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.match(s.run.stdout, /жду кнопку/);
  assert.doesNotMatch(s.calls, /^git (fetch|checkout)/m);
  assert.deepEqual(s.updates, []);
});

test('сервер уже отвечает коммитом release — дела нет', () => {
  const s = server({ release: A });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.doesNotMatch(s.calls, /^git (fetch|checkout)/m);
  assert.deepEqual(s.updates, []);
});

test('коммит release уже не вставал — второй раз не пробуется: каждый повтор снимал бы копию', () => {
  const s = server({ failed: B });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.doesNotMatch(s.calls, /^git (fetch|checkout)/m);
  assert.deepEqual(s.updates, []);
});

test('сдвиг release — выкачка, тег в .env, update.sh, новая версия ответила: готово, прежний провал забыт', () => {
  const s = server({ failed: 'c'.repeat(40) });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.match(s.calls, new RegExp(`^git fetch -q origin release\\ngit checkout -q --detach ${B}\\nupdate\\.sh sha-bbbbbbb$`, 'm'));
  assert.match(s.env, /^SELFIN_TAG=sha-bbbbbbb$/m);
  assert.equal(s.running, B);
  assert.equal(s.failed, null);
  assert.match(s.run.stdout, /сервер на b{40}/);
});

test('новая не ответила, миграций между версиями нет — запомнена и сервер вернулся на прежнюю', () => {
  const s = server({ broken: [B] });
  assert.notEqual(s.run.status, 0);
  assert.equal(s.failed, B);
  assert.deepEqual(s.updates, ['update.sh sha-bbbbbbb', 'update.sh sha-aaaaaaa']);
  assert.match(s.calls, new RegExp(`^git checkout -q --detach ${A}$`, 'm'));
  assert.match(s.env, /^SELFIN_TAG=sha-aaaaaaa$/m);
  assert.equal(s.running, A);
  assert.match(s.run.stdout, /вернулся на a{40}/);
});

test('новая не ответила, между версиями миграции — назад не идёт: старый код может не сойтись с новой схемой', () => {
  const s = server({ broken: [B], migrations: true });
  assert.notEqual(s.run.status, 0);
  assert.equal(s.failed, B);
  assert.deepEqual(s.updates, ['update.sh sha-bbbbbbb']);
  assert.match(s.run.stdout, /миграции.*RESTORE\.md/s);
});

test('update.sh упал (копия не снялась) — коммит запомнен, тег в .env вернулся к работающей версии', () => {
  const s = server({ updateFails: true });
  assert.notEqual(s.run.status, 0);
  assert.equal(s.failed, B);
  assert.match(s.env, /^SELFIN_TAG=sha-aaaaaaa$/m);
  assert.equal(s.running, A, 'контейнеры не трогались');
});

test('сервер не отвечал до выкатки — новая не встала, а возвращаться не на что: назад не идёт', () => {
  const s = server({ running: null, broken: [B] });
  assert.notEqual(s.run.status, 0);
  assert.equal(s.failed, B);
  assert.deepEqual(s.updates, ['update.sh sha-bbbbbbb']);
  assert.match(s.run.stdout, /неизвестно/);
});

test('новой версии ждёт 5 минут — 60 раз по 5 секунд, потом сдаётся', () => {
  const s = server({ broken: [B], migrations: true });
  assert.equal(s.calls.split('\n').filter((l) => l === 'sleep 5').length, 60);
});

// Репетиция 27.10: 60 попыток со сломанным бэком шли 578 секунд — каждая ждала nginx ещё 5 секунд.
test('пока бэка нет, nginx тянет каждую попытку — ожидание всё равно 5 минут по часам, а не по числу попыток', () => {
  const s = server({ broken: [B], migrations: true, slowCurl: true });
  assert.equal(s.calls.split('\n').filter((l) => l === 'sleep 5').length, 30);
});

test('пока новая версия не отвечает, ошибки curl журнал не засоряют — в нём только итог', () => {
  const s = server({ broken: [B], migrations: true });
  assert.doesNotMatch(s.run.stdout + s.run.stderr, /curl: \(/);
  assert.match(s.run.stdout, /за 5 минут сервер не ответил версией b{40}/);
});

test('в .env нет SELFIN_TAG — ошибка до выкачки: правке негде записать тег', () => {
  const s = server({ env: 'SELFIN_PUBLIC_URL=https://example\n' });
  assert.notEqual(s.run.status, 0);
  assert.match(s.run.stdout + s.run.stderr, /SELFIN_TAG/);
  assert.doesNotMatch(s.calls, /^git (fetch|checkout)/m);
  assert.deepEqual(s.updates, []);
});

test('версия — через nginx экранов на 127.0.0.1, порт из .env; без него — 8280', () => {
  const own = server({ release: A, env: `SELFIN_TAG=sha-aaaaaaa\nSELFIN_HTTP_PORT=8299\n` });
  assert.match(own.calls, /^curl .*http:\/\/127\.0\.0\.1:8299\/api\/v1\/version$/m);
  const plain = server({ release: A });
  assert.match(plain.calls, /^curl .*http:\/\/127\.0\.0\.1:8280\/api\/v1\/version$/m);
});

// Подмена — в том же файле и длиннее самого скрипта: где бы оболочка ни читала дальше, она прочтёт подменённое.
test('checkout подменяет файл скрипта на ходу — оболочка доигрывает прочитанное, чужого не исполняет', () => {
  const s = server({ hijack: true });
  assert.equal(s.run.status, 0, s.run.stderr + s.run.stdout);
  assert.doesNotMatch(s.run.stdout, /HIJACK/);
  assert.equal(s.running, B);
});

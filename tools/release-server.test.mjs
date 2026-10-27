// Скрипт сервера (deploy/release.sh, ANO-246): сервер сам переходит на коммит ветки release. Заглушки: git пишет в
// журнал и держит «checkout» в файле, curl отвечает версией «работающих контейнеров», update.sh поднимает тот коммит,
// что выкачан, — или не поднимает, если тест сломал версию. Запуск — bash, как у копий: на сервере скрипт идёт под sh.
// Скрипт запускается копией вне выкачанного дерева, как на сервере (/usr/local/lib/selfin): возврат на старый коммит
// может убрать deploy/release.sh из дерева (ревью Codex на #202), а таймер должен работать и дальше.
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
const C = 'c'.repeat(40);

/**
 * Мир сервера: `running` — чем отвечает версия (null — сервер не отвечает), `release` — коммит ветки (null — ветки нет),
 * `failed` — что лежит в .release-failed, `broken` — коммиты, которые update.sh «поднимает», а они не отвечают,
 * `migrations` — между версиями есть миграции, `updateFails` — update.sh падает (копия не снялась), `old` — коммиты
 * старше скрипта: их checkout убирает deploy/release.sh из дерева. Остальные коммиты кладут в дерево свою версию
 * скрипта — с пометкой «# версия <7 знаков>».
 */
function world({ running = A, release = B, failed = null, broken = [], migrations = false, updateFails = false,
  env = `SELFIN_TAG=sha-${A.slice(0, 7)}\n`, hijack = false, slowCurl = false, old = [], cpBreaks = false } = {}) {
  const root = unix(mkdtempSync(join(tmpdir(), 'selfin-release-')));
  const bin = `${root}/bin`;
  const deploy = `${root}/repo/deploy`;
  const runnerDir = `${root}/runner`;
  const runner = `${runnerDir}/release.sh`;
  const tree = `${deploy}/release.sh`;
  const log = `${root}/calls.log`;
  for (const dir of [bin, deploy, runnerDir]) mkdirSync(dir, { recursive: true });
  const stub = (path, body) => {
    writeFileSync(path, `#!/bin/sh\n${body}\n`);
    chmodSync(path, 0o755);
  };
  const source = readFileSync(SCRIPT, 'utf8');
  writeFileSync(runner, source);
  chmodSync(runner, 0o755);
  writeFileSync(tree, source);
  writeFileSync(`${root}/source`, source);
  writeFileSync(`${deploy}/.env`, env);
  if (running) writeFileSync(`${root}/running`, running);
  if (release) writeFileSync(`${root}/release`, release);
  if (failed) writeFileSync(`${deploy}/.release-failed`, `${failed}\n`);
  if (migrations) writeFileSync(`${root}/migrations`, '');
  if (updateFails) writeFileSync(`${root}/update-fails`, '');
  for (const sha of broken) writeFileSync(`${root}/broken-${sha}`, '');
  for (const sha of old) writeFileSync(`${root}/old-${sha}`, '');
  // Подмена — прямо в запущенном файле и длиннее самого скрипта: где бы оболочка ни читала дальше, прочтёт подменённое.
  const overwrite = `i=0; while [ $i -lt 600 ]; do echo 'echo HIJACK'; i=$((i + 1)); done > "${runner}"`;
  stub(`${bin}/git`, `
[ "$1" = -C ] && shift 2
echo "git $*" >> "${log}"
case "$1" in
  ls-remote) [ -f "${root}/release" ] && printf '%s\\trefs/heads/release\\n' "$(cat "${root}/release")"; exit 0 ;;
  fetch) exit 0 ;;
  checkout) for a; do sha=$a; done; echo "$sha" > "${root}/head"
    if [ -f "${root}/old-$sha" ]; then rm -f "${tree}"; else { cat "${root}/source"; printf '\\n# версия %.7s\\n' "$sha"; } > "${tree}"; fi
    ${hijack ? overwrite : ''}
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
  // Копирование, оборванное на середине (кончился диск, пропало питание): полфайла — и ошибка.
  if (cpBreaks) stub(`${bin}/cp`, 'head -c 200 "$1" > "$2"; exit 1');
  stub(`${deploy}/update.sh`, `
tag=$(sed -n 's/^SELFIN_TAG=//p' .env)
echo "update.sh $tag" >> "${log}"
[ -f "${root}/update-fails" ] && exit 1
head=$(cat "${root}/head")
[ "sha-$(printf '%.7s' "$head")" = "$tag" ] || { echo "update.sh: тег $tag, а выкачан $head" >> "${log}"; exit 1; }
if [ -f "${root}/broken-$head" ]; then rm -f "${root}/running"; else echo "$head" > "${root}/running"; fi`);
  const shellEnv = { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, SELFIN_DEPLOY: deploy };
  const read = (path) => (existsSync(path) ? readFileSync(path, 'utf8') : null);
  return {
    // Таймер зовёт скрипт по его месту; здесь — относительным путём из его каталога: скрипт обязан найти себя сам.
    run() {
      writeFileSync(log, '');
      const run = spawnSync('bash', ['./release.sh'], { cwd: runnerDir, env: shellEnv, encoding: 'utf8' });
      const calls = read(log) ?? '';
      return {
        run, calls,
        env: read(`${deploy}/.env`),
        failed: read(`${deploy}/.release-failed`)?.trim() ?? null,
        running: read(`${root}/running`)?.trim() ?? null,
        updates: calls.split('\n').filter((l) => l.startsWith('update.sh ')),
        runner: read(runner),
        tree: read(tree),
      };
    },
    release: (sha) => writeFileSync(`${root}/release`, sha),
    done: () => rmSync(root, { recursive: true, force: true }),
  };
}

/** Один проход скрипта в свежем мире. */
function server(options) {
  const w = world(options);
  try {
    return w.run();
  } finally {
    w.done();
  }
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
  const s = server({ failed: C });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.match(s.calls, new RegExp(`^git fetch -q origin release\\ngit checkout -q --detach ${B}\\nupdate\\.sh sha-bbbbbbb$`, 'm'));
  assert.match(s.env, /^SELFIN_TAG=sha-bbbbbbb$/m);
  assert.equal(s.running, B);
  assert.equal(s.failed, null);
  assert.match(s.run.stdout, /сервер на b{40}/);
});

test('удачная выкатка — скрипт заменяет себя версией из выкачанного дерева', () => {
  const s = server();
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.match(s.runner, /# версия bbbbbbb\n$/);
});

test('копирование себя оборвалось — прежний скрипт цел: следующий проход таймера не наткнётся на полфайла', () => {
  const s = server({ cpBreaks: true });
  assert.equal(s.run.status, 0, s.run.stderr);
  assert.equal(s.running, B, 'выкатка встала');
  assert.equal(s.runner, readFileSync(SCRIPT, 'utf8'));
  assert.match(s.run.stderr, /себя не обновил/);
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

// Ревью Codex на #202 (P1): первая выкатка с 1cf6753 — коммита, где скрипта ещё нет. Возврат на него убрал бы скрипт
// из дерева, и таймер дальше звал бы несуществующий файл: исправленный коммит кнопкой было бы уже не выкатить.
test('возврат на коммит без скрипта не ломает таймер: скрипт вне дерева цел, и следующая выкатка идёт', () => {
  const w = world({ broken: [B], old: [A] });
  try {
    const first = w.run();
    assert.notEqual(first.run.status, 0);
    assert.match(first.run.stdout, /вернулся на a{40}/);
    assert.equal(first.tree, null, 'дерево — на коммите без скрипта');
    assert.doesNotMatch(first.runner ?? '', /# версия/, 'не вставшая версия скрипт не заменила');
    w.release(C);
    const second = w.run();
    assert.equal(second.run.status, 0, second.run.stderr + second.run.stdout);
    assert.equal(second.running, C);
    assert.match(second.runner, /# версия ccccccc\n$/);
  } finally {
    w.done();
  }
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

// Запущенный файл переписывают на месте — например, установка через cp поверх, пока таймер работает.
test('запущенный скрипт переписали на ходу — оболочка доигрывает прочитанное, чужого не исполняет', () => {
  const s = server({ hijack: true });
  assert.equal(s.run.status, 0, s.run.stderr + s.run.stdout);
  assert.doesNotMatch(s.run.stdout, /HIJACK/);
  assert.equal(s.running, B);
});

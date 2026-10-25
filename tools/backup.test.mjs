// Скрипт копии (deploy/backup/backup; шаг 5, ANO-243): режим local — только на сервере, открытым текстом; external —
// шифрование и хранилище, как в шаге 5; без режима — ошибка и ни одной команды. Внешние команды — заглушки, которые
// пишут в журнал, что их звали. Запуск — bash: в образе скрипт идёт под ash, а sh в CI (dash) не знает pipefail.
// Спека: docs/superpowers/specs/2026-10-25-backups-local-mode-design.md
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, utimesSync, writeFileSync } from 'node:fs';
import { delimiter, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const SCRIPT = fileURLToPath(new URL('../deploy/backup/backup', import.meta.url));
const unix = (path) => path.replaceAll('\\', '/');
const DAY = 24 * 60 * 60;

function backup(mode, { dumpFails = false, old = [], ping = '' } = {}) {
  const root = unix(mkdtempSync(join(tmpdir(), 'selfin-backup-')));
  const bin = `${root}/bin`;
  const backups = `${root}/backups`;
  const log = `${root}/calls.log`;
  mkdirSync(bin);
  mkdirSync(backups);
  const stub = (name, body) => {
    writeFileSync(`${bin}/${name}`, `#!/bin/sh\necho "${name} $*" >> "${log}"\n${body}\n`);
    chmodSync(`${bin}/${name}`, 0o755);
  };
  stub('pg_dump', dumpFails ? 'exit 1' : 'echo "-- дамп: $*"');
  stub('psql', 'echo "table users 1 abc"');
  stub('age', 'out=""; while [ $# -gt 0 ]; do [ "$1" = -o ] && out="$2"; shift; done; cat > "$out"');
  stub('rclone', '');
  stub('curl', 'echo \'{"commit":"abc"}\'');
  for (const [name, days] of old) {
    mkdirSync(`${backups}/${name}`);
    const at = Date.now() / 1000 - days * DAY;
    utimesSync(`${backups}/${name}`, at, at);
  }
  const env = {
    ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, SELFIN_BACKUPS: backups,
    POSTGRES_PASSWORD: 'p', KC_DB_PASSWORD: 'k', SELFIN_BACKUP_AGE_RECIPIENT: 'age1test', SELFIN_BACKUP_BUCKET: 'store',
  };
  delete env.SELFIN_BACKUP_MODE;
  delete env.SELFIN_BACKUP_PING_URL;
  if (mode !== undefined) env.SELFIN_BACKUP_MODE = mode;
  if (ping) env.SELFIN_BACKUP_PING_URL = ping;
  const run = spawnSync('bash', [SCRIPT], { env, encoding: 'utf8' });
  const calls = existsSync(log) ? readFileSync(log, 'utf8') : '';
  const copies = readdirSync(backups).filter((name) => !old.some(([o]) => o === name));
  const files = copies.length === 1 ? readdirSync(`${backups}/${copies[0]}`).sort() : [];
  const read = (file) => readFileSync(`${backups}/${copies[0]}/${file}`, 'utf8');
  return { run, calls, backups, copies, files, read };
}

test('без режима — ошибка, ни одной команды и ни одной копии: открытый текст сам собой не появится', () => {
  for (const mode of [undefined, '', 'encrypted']) {
    const { run, calls, copies } = backup(mode);
    assert.notEqual(run.status, 0, `режим «${mode}»`);
    assert.match(run.stderr, /SELFIN_BACKUP_MODE/);
    assert.equal(calls, '', `режим «${mode}»: команды звались — ${calls}`);
    assert.deepEqual(copies, []);
  }
});

test('local: обе базы и манифест — открытым текстом на сервере, без age и без выгрузки', () => {
  const { run, calls, files, read } = backup('local');
  assert.equal(run.status, 0, run.stderr);
  assert.deepEqual(files, ['keycloak.sql', 'manifest.txt', 'selfin.sql']);
  assert.match(read('selfin.sql'), /-- дамп: .*-h db -U admin -d selfin/);
  assert.match(read('keycloak.sql'), /-- дамп: .*-h keycloak-db -U keycloak -d keycloak/);
  assert.match(read('manifest.txt'), /^backend \{"commit":"abc"\}\ntable users 1 abc/);
  assert.doesNotMatch(calls, /^age /m);
  assert.doesNotMatch(calls, /^rclone /m);
});

test('local: копии старше шести суток уходят, младше — остаются; журнал удалений — старше 29 дней', () => {
  const { run, calls, backups } = backup('local', { old: [['week', 6.5], ['five-days', 5.5]] });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(existsSync(`${backups}/week`), false, 'копия шести с половиной суток должна уйти: тексты обещают 7 дней');
  assert.equal(existsSync(`${backups}/five-days`), true);
  assert.match(calls, /^psql .*DELETE FROM ops\.deletions WHERE deleted_at < now\(\) - interval '29 days'/m);
});

test('local: уборка — до копии: дамп упал, а старые копии и журнал всё равно убраны', () => {
  const { run, calls, backups } = backup('local', { dumpFails: true, old: [['week', 6.5]] });
  assert.notEqual(run.status, 0);
  assert.equal(existsSync(`${backups}/week`), false);
  assert.match(calls, /DELETE FROM ops\.deletions/);
});

test('external: как в шаге 5 — каждый файл шифруется, копия выгружается, хранилище убирается по 28 дням', () => {
  const { run, calls, files } = backup('external');
  assert.equal(run.status, 0, run.stderr);
  assert.deepEqual(files, ['keycloak.sql.age', 'manifest.txt.age', 'selfin.sql.age']);
  assert.equal(calls.match(/^age -r age1test /gm)?.length, 3);
  assert.match(calls, /^rclone copy .* store:store\//m);
  assert.match(calls, /^rclone delete --min-age 28d --rmdirs store:store/m);
});

// Номер первой строки журнала вызовов, где встретился образец: порядок команд — по журналу заглушек.
const order = (calls, pattern) => calls.split('\n').findIndex((line) => pattern.test(line));

test('external: порядок шага 5 — выгрузка, старые копии в хранилище, журнал удалений, отметка (ANO-234)', () => {
  const { run, calls } = backup('external', { ping: 'https://ping.example/selfin' });
  assert.equal(run.status, 0, run.stderr);
  const upload = order(calls, /^rclone copy /);
  const copies = order(calls, /^rclone delete --min-age 28d/);
  const journal = order(calls, /^psql .*DELETE FROM ops\.deletions/);
  const ping = order(calls, /^curl .*ping\.example/);
  assert.ok(upload >= 0 && upload < copies && copies < journal && journal < ping, calls);
});

test('local: журнал удалений — до дампа, отметка — после копии', () => {
  const { run, calls } = backup('local', { ping: 'https://ping.example/selfin' });
  assert.equal(run.status, 0, run.stderr);
  const journal = order(calls, /^psql .*DELETE FROM ops\.deletions/);
  const dump = order(calls, /^pg_dump /);
  const ping = order(calls, /^curl .*ping\.example/);
  assert.ok(journal >= 0 && journal < dump && dump < ping, calls);
});

// node --test tools/delete-person.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  deletionSql, journalEntries, journalLine, keepJournal, prunedJournal, tableSetProblems, JOURNAL_DAYS, PERSON_TABLES,
} from './delete-person.mjs';

const SOURCE = readFileSync(new URL('./delete-person.mjs', import.meta.url), 'utf8');

const ID = '7b0c1c8e-2f6a-4c3e-9a51-3f1d2e4b5a69';
const KC = '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';

test('удаление — одной транзакцией, по списку, строка users последней, запись в журнал — той же транзакцией (шаг 5)', () => {
  const lines = deletionSql({ userId: ID, kcId: KC }, ['fund_transactions', 'categories']).split('\n');
  assert.deepEqual(lines, [
    'BEGIN;',
    `DELETE FROM fund_transactions WHERE user_id = '${ID}';`,
    `DELETE FROM categories WHERE user_id = '${ID}';`,
    `DELETE FROM users WHERE id = '${ID}';`,
    `INSERT INTO ops.deletions (kc_sub, person_id) VALUES ('${KC}', '${ID}');`,
    'COMMIT;',
  ]);
});

test('наших строк нет — в журнал всё равно: удалена учётная запись Keycloak (шаг 5)', () => {
  assert.equal(deletionSql({ userId: undefined, kcId: KC }),
    `INSERT INTO ops.deletions (kc_sub, person_id) VALUES ('${KC}', NULL);`);
});

test('«только наши строки» — не удаление человека: журнала нет, иначе повтор после восстановления удалил бы владельца', () => {
  const sql = deletionSql({ userId: ID, kcId: KC, journal: false }, ['categories']);
  assert.doesNotMatch(sql, /ops\.deletions/);
  assert.match(sql, /^BEGIN;[\s\S]*COMMIT;$/);
});

test('по умолчанию — порядок из person-tables.json', () => {
  const tables = deletionSql({ userId: ID, kcId: KC }).split('\n').slice(1, -3).map((line) => line.split(' ')[2]);
  assert.deepEqual(tables, PERSON_TABLES);
});

test('в SQL идёт только id, не произвольная строка', () => {
  assert.throws(() => deletionSql({ userId: `${ID}'; DROP TABLE users; --`, kcId: KC }), /не id/);
  assert.throws(() => deletionSql({ userId: ID, kcId: `${KC}'); --` }), /не id/);
});

test('синтетического владельца не удаляет: это история владельца, на стенде — копия', () => {
  assert.throws(() => deletionSql({ userId: '00000000-0000-0000-0000-000000000001', kcId: KC }), /синтетическ/);
});

test('строка журнала владельца — когда, чей реалм, sub и наш id (шаг 5)', () => {
  const at = '2026-10-18T10:00:00.000Z';
  assert.deepEqual(JSON.parse(journalLine({ at, issuer: 'https://s.example/auth/realms/selfin', kcId: KC, userId: ID })),
    { at, issuer: 'https://s.example/auth/realms/selfin', sub: KC, userId: ID });
  assert.equal(JSON.parse(journalLine({ at, issuer: 'x', kcId: KC })).userId, null);
});

test('повтор — только удаления того же реалма, каждый человек один раз; пустые строки — ничего (шаг 5)', () => {
  const server = 'https://s.example/auth/realms/selfin';
  const lines = [
    JSON.stringify({ at: '1', issuer: server, sub: KC, userId: ID }),
    JSON.stringify({ at: '2', issuer: 'http://127.0.0.1:8180/realms/selfin', sub: ID, userId: null }),
    '',
    JSON.stringify({ at: '3', issuer: server, sub: KC, userId: ID }),
  ];
  assert.deepEqual(journalEntries(lines, server), { subs: [KC], malformed: [] });
});

test('битая строка журнала не пропускается молча: в ней могло быть удаление после копии (ревью Codex на #175)', () => {
  const server = 'https://s.example/auth/realms/selfin';
  const lines = [
    JSON.stringify({ at: '1', issuer: server, sub: KC, userId: ID }),
    '{"at":"2","issuer":"https://s.exa',
    JSON.stringify({ at: '3', issuer: server, sub: 'не id', userId: null }),
    JSON.stringify({ at: '4', sub: KC }),
    '   ',
  ];
  assert.deepEqual(journalEntries(lines, server), { subs: [KC], malformed: [2, 3, 4] });
});

test('журнал владельца забывает записи старше 29 дней: при чистке раз в сутки запись живёт меньше 30 (ANO-234)', () => {
  const now = new Date('2026-11-30T12:00:00Z');
  const entry = (at) => JSON.stringify({ at, issuer: 'x', sub: KC, userId: ID });
  // 1.11 11:00 — 29 дней и час, 1.11 13:00 — 28 дней и 23 часа.
  const lines = [entry('2026-11-01T11:00:00Z'), entry('2026-11-01T13:00:00Z'), '', '{"обрыв', entry('2026-11-29T00:00:00Z')];
  assert.deepEqual(prunedJournal(lines, now), [entry('2026-11-01T13:00:00Z'), '{"обрыв', entry('2026-11-29T00:00:00Z')]);
});

test('--prune чистит только файл владельца, без сервера и Keycloak: её раз в сутки запускает Планировщик (ANO-234)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'selfin-prune-'));
  const file = join(dir, 'selfin-deletions.jsonl');
  const line = (days, sub) => journalLine({ at: new Date(Date.now() - days * 86_400_000).toISOString(), issuer: 'x', kcId: sub, userId: null });
  const [old, fresh] = [line(30, KC), line(1, ID)];
  writeFileSync(file, `${old}\n${fresh}\n`);
  // Keycloak — по адресу, где никого нет: команда, полезшая к серверу, упала бы.
  const run = spawnSync(process.execPath, [fileURLToPath(new URL('./delete-person.mjs', import.meta.url)), '--prune'],
    { env: { ...process.env, SELFIN_DELETIONS: file, SELFIN_KC_ADMIN: 'http://127.0.0.1:9/auth' }, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(readFileSync(file, 'utf8'), `${fresh}\n`);
});

test('пропавший журнал чистка не заводит пустым: проверка восстановления ловит его отсутствие, а пустой приняла бы (ревью Codex на #189)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'selfin-missing-'));
  const file = join(dir, 'selfin-deletions.jsonl');
  keepJournal(file);
  assert.equal(existsSync(file), false, 'без новых записей файла не появилось');
  const run = spawnSync(process.execPath, [fileURLToPath(new URL('./delete-person.mjs', import.meta.url)), '--prune'],
    { env: { ...process.env, SELFIN_DELETIONS: file, SELFIN_KC_ADMIN: 'http://127.0.0.1:9/auth' }, encoding: 'utf8' });
  assert.equal(run.status, 1, 'пропавший журнал — ошибка задачи, а не молчаливый успех');
  assert.match(run.stderr, /журнала удалений нет/);
  assert.equal(existsSync(file), false, 'и --prune файла не завела');
});

test('журнал правится под замком: плановая чистка ждёт, пока пишет удаление, и не затирает его запись (ревью Codex на #189)', async () => {
  // Без замка чистка прочла бы журнал до записи удаления и заменила бы файл старым снимком — удаления нет нигде.
  const dir = mkdtempSync(join(tmpdir(), 'selfin-lock-'));
  const file = join(dir, 'selfin-deletions.jsonl');
  const line = (days, sub) => journalLine({ at: new Date(Date.now() - days * 86_400_000).toISOString(), issuer: 'x', kcId: sub, userId: null });
  const [old, fresh] = [line(30, KC), line(0, ID)];
  writeFileSync(file, `${old}\n`);
  writeFileSync(`${file}.lock`, ''); // замок держит удаление
  const prune = spawn(process.execPath, [fileURLToPath(new URL('./delete-person.mjs', import.meta.url)), '--prune'],
    { env: { ...process.env, SELFIN_DELETIONS: file, SELFIN_KC_ADMIN: 'http://127.0.0.1:9/auth' } });
  const exited = new Promise((resolve) => prune.on('exit', resolve));
  await new Promise((resolve) => setTimeout(resolve, 700));
  assert.equal(prune.exitCode, null, 'чистка ждёт замка');
  writeFileSync(file, `${old}\n${fresh}\n`); // удаление дописало запись
  rmSync(`${file}.lock`); // и отпустило замок
  assert.equal(await exited, 0);
  assert.equal(readFileSync(file, 'utf8'), `${fresh}\n`, 'запись удаления цела, старая убрана');
});

test('ночная копия: сначала уходят копии старше 28 дней, потом записи журнала старше 29 — в том же прогоне (ANO-234)', () => {
  // Сроки фиксированные, порядок держит set -e: не удалились старые копии — журнал не чистится. Так копия, снятая до
  // удаления, уходит не позже записи о нём, а запись живёт меньше 30 дней.
  const script = readFileSync(new URL('../deploy/backup/backup', import.meta.url), 'utf8');
  const at = (text) => {
    const i = script.indexOf(text);
    assert.ok(i >= 0, `в скрипте нет: ${text}`);
    return i;
  };
  const upload = at('rclone copy');
  const copies = at('rclone delete --min-age 28d');
  const journal = at(`DELETE FROM ops.deletions WHERE deleted_at < now() - interval '${JOURNAL_DAYS} days'`);
  assert.ok(upload < copies && copies < journal, 'выгрузка, удаление старых копий, чистка журнала — по порядку');
  assert.ok(journal < at('SELFIN_BACKUP_PING_URL'), 'отметка об успехе — после чистки журнала');
  assert.equal(script.split('DELETE FROM ops.deletions').length, 2, 'журнал на сервере чистится в одном месте');
  assert.ok(script.startsWith('#!/bin/sh') && script.includes('set -eu'), 'упала команда — дальше скрипт не идёт');
  assert.equal(JOURNAL_DAYS, 29, 'файл владельца — тот же срок, что журнал на сервере');
});

test('набор таблиц: совпал — проблем нет, расхождение — названо в обе стороны', () => {
  assert.deepEqual(tableSetProblems(['a', 'b'], ['b', 'a']), []);
  assert.deepEqual(tableSetProblems(['a', 'b', 'new'], ['a', 'b', 'gone']), [
    'в схеме есть new, в списке удаления — нет',
    'в списке удаления есть gone, в схеме — нет',
  ]);
});

test('список без повторов', () => {
  assert.equal(new Set(PERSON_TABLES).size, PERSON_TABLES.length);
});

test('журнал владельца пишется до удаления на сервере: оборвись запись после — удаление нигде не осталось бы (ревью Codex на #175)', () => {
  const body = SOURCE.slice(SOURCE.indexOf('export async function deletePerson'));
  const journal = body.indexOf('keepJournal(JOURNAL');
  assert.ok(journal > 0 && journal < body.indexOf('sql(statement)'), 'keepJournal(JOURNAL, …) — раньше sql(statement)');
});

test('журнал заменяется целиком, переименованием временного файла: обрыв не теряет прежних строк (ревью Codex на #175)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'selfin-journal-'));
  const file = join(dir, 'selfin-deletions.jsonl');
  const at = new Date().toISOString();
  const [kept, added] = [KC, ID].map((sub) => journalLine({ at, issuer: 'x', kcId: sub, userId: null }));
  writeFileSync(file, `${kept}\n`);
  const before = statSync(file, { bigint: true }).ino;
  keepJournal(file, [added]);
  assert.equal(readFileSync(file, 'utf8'), `${kept}\n${added}\n`);
  assert.notEqual(statSync(file, { bigint: true }).ino, before, 'журнал — новый файл, а не прежний, переписанный на месте');
  assert.deepEqual(readdirSync(dir), ['selfin-deletions.jsonl'], 'временного файла не осталось');
  const keep = SOURCE.slice(SOURCE.indexOf('export function keepJournal')).split(/\r?\n\}\r?\n/)[0];
  assert.match(keep, /flush: true/, 'временный файл — на диск до переименования');
});

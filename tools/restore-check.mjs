#!/usr/bin/env node
// Проверка восстановления из копии (шаг 5 плана спринта 6, спека 2026-10-18-backups-design.md).
//
//   node tools/restore-check.mjs <расшифрованный манифест> [<журнал владельца>]
//
// Запускается после восстановления обеих баз, когда Keycloak и бэк снова подняты (deploy/RESTORE.md):
//   1. таблицы нашей базы — те же количества и отпечатки, что в манифесте копии;
//   2. каждый external_id нашей базы — учётная запись реалма (ревью Codex на #166: копии двух баз — одна точка);
//   3. удаления повторяются — из журнала копии и журнала владельца (по умолчанию SELFIN_DELETIONS) — и никого из
//      них нет ни у нас, ни в Keycloak.
// Адреса — как у second-person.mjs. Выход 1 — что-то разошлось.

import { existsSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { adminToken, realmIssuer } from './keycloak-token.mjs';
import { API, KC_ADMIN, adminCall, sql } from './second-person.mjs';
import { deletePerson, journalEntries, JOURNAL, keepJournal } from './delete-person.mjs';

/** Запрос манифеста — тот же файл, что у копии: расхождение в самом запросе невозможно. */
export const MANIFEST_SQL = readFileSync(new URL('../deploy/backup/manifest.sql', import.meta.url), 'utf8');

/** Манифест: образ бэка при копии, таблицы — количество и отпечаток, sub из журнала удалений копии. */
export function parseManifest(text) {
  const manifest = { backend: null, tables: new Map(), deletions: [] };
  for (const line of text.split('\n')) {
    const [kind, ...rest] = line.trim().split(' ');
    if (kind === 'backend') manifest.backend = rest.join(' ');
    if (kind === 'table') manifest.tables.set(rest[0], { count: Number(rest[1]), md5: rest[2] });
    if (kind === 'deletion') manifest.deletions.push(rest[1]);
  }
  return manifest;
}

/**
 * Нет журнала владельца — восстановление не принимается (ревью Codex на #175): удаление, сделанное после копии, есть
 * только в нём, и с опечаткой в пути удалённый вернулся бы молча. Никого не удалял — пустой файл.
 */
export const journalProblems = (file, exists, malformed = []) => [
  ...(exists ? [] : [`журнала владельца нет (${file}): удаления после копии не повторить — если ты никого не удалял, создай пустой файл`]),
  ...(malformed.length ? [`журнал владельца: строки ${malformed.join(', ')} не читаются — удаление в них не повторено, поправь их и запусти проверку снова`] : []),
];

/** Чем восстановленные таблицы расходятся с копией. */
export function tableProblems(expected, actual) {
  const problems = [];
  for (const [table, want] of expected) {
    const got = actual.get(table);
    if (!got) problems.push(`${table}: в копии есть, после восстановления нет`);
    else if (got.count !== want.count) problems.push(`${table}: строк в копии ${want.count}, после восстановления ${got.count}`);
    else if (got.md5 !== want.md5) problems.push(`${table}: содержимое расходится с копией`);
  }
  for (const table of actual.keys()) {
    if (!expected.has(table)) problems.push(`${table}: после восстановления есть, в копии нет`);
  }
  return problems;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [manifestFile, journalFile = JOURNAL] = process.argv.slice(2);
  if (!manifestFile) {
    console.error('node tools/restore-check.mjs <расшифрованный манифест> [<журнал владельца>]');
    process.exit(2);
  }
  const manifest = parseManifest(readFileSync(manifestFile, 'utf8'));
  const problems = [];

  // 1. Таблицы — до повтора удалений: повтор их меняет.
  const tables = tableProblems(manifest.tables, parseManifest(sql(MANIFEST_SQL).join('\n')).tables);
  problems.push(...tables);
  console.log(`таблицы: ${manifest.tables.size} в копии, ${tables.length ? `расхождений — ${tables.length}` : 'совпали'}`);
  const version = await fetch(`${API}/version`).then((r) => r.text()).catch(() => 'не ответил');
  if (version !== manifest.backend) console.log(`образ бэка: при копии ${manifest.backend}, сейчас ${version} — миграции могли поменять таблицы`);

  // 2. Каждый человек нашей базы — учётная запись реалма.
  const admin = await adminToken(KC_ADMIN);
  const people = sql('SELECT external_id FROM users WHERE external_id IS NOT NULL');
  const orphans = [];
  for (const sub of people) {
    if ((await adminCall(admin, 'GET', `/users/${sub}`)).status !== 200) orphans.push(`${sub}: у нас есть, в реалме нет`);
  }
  problems.push(...orphans);
  console.log(`люди: ${people.length}, без учётной записи реалма — ${orphans.length}`);

  // 3. Повтор удалений — журнал копии и журнал владельца, только этого реалма.
  const issuer = await realmIssuer(KC_ADMIN);
  const hasJournal = existsSync(journalFile);
  const { subs: owners, malformed } = hasJournal
    ? journalEntries(readFileSync(journalFile, 'utf8').split('\n'), issuer) : { subs: [], malformed: [] };
  problems.push(...journalProblems(journalFile, hasJournal, malformed));
  if (hasJournal) keepJournal(journalFile);
  const subs = [...new Set([...manifest.deletions, ...owners])];
  for (const sub of subs) {
    const { left } = await deletePerson({ sub, journal: false });
    problems.push(...left.map((what) => `${sub} из журнала: ${what}`));
  }
  console.log(`удаления: в копии ${manifest.deletions.length}, у владельца ${owners.length}, повторено ${subs.length}`);

  for (const problem of problems) console.log(`  НЕТ  ${problem}`);
  console.log(problems.length ? `Итог: не сходится — ${problems.length}` : 'Итог: восстановление сходится');
  process.exitCode = problems.length ? 1 : 0;
}

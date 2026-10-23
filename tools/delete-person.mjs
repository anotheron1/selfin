#!/usr/bin/env node
// Удаление человека со всеми данными (спека 2026-10-17-stand-tools-design.md): временного второго человека после
// проверки и любого человека по его просьбе.
//
//   node tools/delete-person.mjs --username <имя> | --sub <id Keycloak> [--ours-only] [--remove-scripts-client]
//   node tools/delete-person.mjs --replay <журнал>   повтор удалений после восстановления из копии
//   node tools/delete-person.mjs --prune             только чистка журнала владельца — раз в сутки из Планировщика
//
// Сначала наши строки — одной транзакцией, в порядке tools/person-tables.json, строка users последней; потом учётная
// запись Keycloak. Общей транзакции у двух систем нет: повторный запуск доделывает начатое. В конце проверяются оба
// условия — строк человека ноль, учётной записи нет; выход 1 — что-то осталось.
// --ours-only — только наши строки, учётную запись Keycloak не трогать (ловушка шага 7 плана спринта 6).
// --remove-scripts-client — заодно убрать клиент скриптов: на сервере он живёт только на время проверки.
// Синтетического владельца — историю владельца, на стенде копию — не удаляет. Адреса — как у second-person.mjs.
//
// Журнал удалений (шаг 5, спека 2026-10-18-backups-design.md): та же транзакция пишет ops.deletions — он уходит в
// каждую ночную копию, — а у владельца дописывается файл SELFIN_DELETIONS (по умолчанию ~/selfin-deletions.jsonl):
// удаление после последней копии есть только в нём. Восстановили сервер из копии — --replay с этим файлом.

import { closeSync, existsSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { adminToken, realmIssuer } from './keycloak-token.mjs';
import { KC_ADMIN, adminCall, findUser, removeScriptsClient, sql } from './second-person.mjs';

/** Журнал удалений у владельца — вне баз сервера, переживает и гибель сервера, и откат к старой копии. */
export const JOURNAL = process.env.SELFIN_DELETIONS || join(homedir(), 'selfin-deletions.jsonl');

/** Таблицы с владельцем в порядке удаления — дочерние до родителей. Сторож — тест в TwoPeopleIsolationIT. */
export const PERSON_TABLES = JSON.parse(readFileSync(new URL('./person-tables.json', import.meta.url), 'utf8'));

const SYNTHETIC_OWNER = '00000000-0000-0000-0000-000000000001';
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** id для SQL: только UUID — в запрос не попадёт произвольная строка. */
const checked = (id) => {
  if (!ID.test(id)) throw new Error(`не id: ${id}`);
  return id;
};

/**
 * SQL удаления наших строк человека: одной транзакцией, по списку, строка users — последней; запись в журнал удалений —
 * в той же транзакции. Наших строк нет — только запись в журнал: удалена учётная запись Keycloak. {@code journal: false}
 * — «только наши строки»: это не удаление человека, в журнал не идёт. Нечего делать — пусто.
 */
export function deletionSql({ userId, kcId, journal = true }, tables = PERSON_TABLES) {
  const entry = journal
    ? `INSERT INTO ops.deletions (kc_sub, person_id) VALUES ('${checked(kcId)}', ${userId ? `'${checked(userId)}'` : 'NULL'});`
    : '';
  if (!userId) return entry;
  if (checked(userId) === SYNTHETIC_OWNER) {
    throw new Error('это синтетический владелец — история владельца, на стенде копия; удалять его не для этой команды');
  }
  return ['BEGIN;', ...tables.map((table) => `DELETE FROM ${table} WHERE user_id = '${userId}';`),
    `DELETE FROM users WHERE id = '${userId}';`, ...(entry ? [entry] : []), 'COMMIT;'].join('\n');
}

/** Строка журнала владельца: когда, чей реалм (издатель), sub Keycloak и наш id. */
export const journalLine = ({ at, issuer, kcId, userId }) => JSON.stringify({ at, issuer, sub: kcId, userId: userId ?? null });

/**
 * Сколько дней журнал помнит удалённого — тот же срок, что у журнала на сервере (`deploy/backup/backup`). Копии живут
 * меньше 29 дней, а восстанавливают только из копии не старше 28 (`deploy/RESTORE.md`): каждое удаление после неё
 * журнал ещё помнит. При чистке раз в сутки запись живёт меньше 30 дней, как обещают тексты (ANO-234).
 */
export const JOURNAL_DAYS = 29;

/** Запись журнала, если строка — запись: объект с реалмом, sub и временем. Иначе — null. */
function entryOf(line) {
  try {
    const entry = JSON.parse(line);
    return typeof entry?.issuer === 'string' && ID.test(entry.sub) && !Number.isNaN(Date.parse(entry.at)) ? entry : null;
  } catch {
    return null;
  }
}

/**
 * Кого повторить после восстановления: удаления того же реалма, каждый sub — один раз. Пустые строки — ничего;
 * непустая строка, которая не запись, — в {@code malformed} номером: в ней могло быть удаление после копии, и молча
 * её не пропускаем (ревью Codex на #175).
 */
export function journalEntries(lines, issuer) {
  const subs = new Set();
  const malformed = [];
  lines.forEach((line, index) => {
    if (!line.trim()) return;
    const entry = entryOf(line);
    if (!entry) malformed.push(index + 1);
    else if (entry.issuer === issuer) subs.add(entry.sub);
  });
  return { subs: [...subs], malformed };
}

/** Журнал без записей старше JOURNAL_DAYS и без пустых строк; битые строки остаются — их надо увидеть и поправить. */
export function prunedJournal(lines, now, days = JOURNAL_DAYS) {
  return lines.filter((line) => {
    if (!line.trim()) return false;
    const entry = entryOf(line);
    return !entry || now - Date.parse(entry.at) < days * 86_400_000;
  });
}

/** Сколько ждать замка журнала: другой процесс держит его миллисекунды. */
const LOCK_WAIT_MS = 10_000;

/**
 * Чтение и замена журнала — под замком, файлом рядом (ревью Codex на #189). Плановая чистка, удаление, повтор и
 * проверка — разные процессы: без замка один заменил бы файл снимком, прочитанным до записи другого, и запись удаления
 * пропала бы. Замок от упавшего процесса сам не снимается: не зная, что процесс умер, снимать его нельзя.
 */
function withJournalLock(file, work) {
  const lock = `${file}.lock`;
  const until = Date.now() + LOCK_WAIT_MS;
  for (;;) {
    try {
      closeSync(openSync(lock, 'wx'));
      break;
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      if (Date.now() > until) throw new Error(`журнал занят: ${lock}; если никто в него не пишет — удали этот файл`);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
  }
  try {
    return work();
  } finally {
    rmSync(lock, { force: true });
  }
}

/**
 * Переписывает журнал владельца: старые записи — вон, новые — в конец. Целиком во временный файл рядом, на диск — и
 * переименованием поверх: оборванная запись не теряет прежних строк (ревью Codex на #175).
 */
export function keepJournal(file, added = []) {
  withJournalLock(file, () => {
    // Пропавший журнал без новых записей пустым не заводится: проверка восстановления ловит отсутствие журнала, а
    // пустой приняла бы — и удалённые из пропавшего вернулись бы из копии (ревью Codex на #189).
    if (!existsSync(file) && added.length === 0) return;
    const lines = existsSync(file) ? readFileSync(file, 'utf8').split('\n') : [];
    const temp = `${file}.tmp`;
    writeFileSync(temp, [...prunedJournal(lines, Date.now()), ...added].map((line) => `${line}\n`).join(''), { flush: true });
    renameSync(temp, file);
  });
}

/** Чем набор таблиц схемы расходится со списком удаления. */
export function tableSetProblems(schemaTables, tables = PERSON_TABLES) {
  return [
    ...schemaTables.filter((table) => !tables.includes(table)).map((table) => `в схеме есть ${table}, в списке удаления — нет`),
    ...tables.filter((table) => !schemaTables.includes(table)).map((table) => `в списке удаления есть ${table}, в схеме — нет`),
  ];
}

/**
 * Удаляет человека; возвращает его id и то, что осталось, — пусто, если удалён целиком. Удаление пишется в журнал —
 * в базе и у владельца; «только наши строки» и повтор по журналу — нет.
 */
export async function deletePerson({ username, sub, oursOnly = false, journal = !oursOnly }) {
  const admin = await adminToken(KC_ADMIN);
  const found = sub ?? (await findUser(admin, username))?.id;
  if (!found) throw new Error(`учётной записи ${username} в Keycloak нет; если остались наши строки — удалить по --sub`);
  const kcId = checked(found);

  // Сервер может оказаться новее рабочей копии: с другим набором таблиц удалять нельзя — что-то останется.
  const problems = tableSetProblems(sql(`SELECT table_name FROM information_schema.columns
    WHERE table_schema = 'public' AND column_name = 'user_id'`));
  if (problems.length) throw new Error(`список удаления устарел: ${problems.join('; ')}`);

  const [userId] = sql(`SELECT id FROM users WHERE external_id = '${kcId}'`);
  const statement = deletionSql({ userId, kcId, journal });
  // Журнал владельца — раньше удаления на сервере (ревью Codex на #175): удаление после последней копии есть только в
  // нём, и запись после удаления могла не случиться. Удаление сорвалось — запись остаётся намерением: повтори команду.
  if (journal) {
    keepJournal(JOURNAL, [journalLine({ at: new Date().toISOString(), issuer: await realmIssuer(KC_ADMIN), kcId, userId })]);
  }
  if (statement) sql(statement);
  if (!oursOnly) {
    const response = await adminCall(admin, 'DELETE', `/users/${kcId}`);
    if (!response.ok && response.status !== 404) throw new Error(`удаление из Keycloak: ${response.status} ${await response.text()}`);
  }

  const left = [];
  if (userId) {
    for (const table of PERSON_TABLES) {
      const [count] = sql(`SELECT count(*) FROM ${table} WHERE user_id = '${userId}'`);
      if (count !== '0') left.push(`${table}: ${count}`);
    }
  }
  if (sql(`SELECT count(*) FROM users WHERE external_id = '${kcId}'`)[0] !== '0') left.push('users: строка осталась');
  if (!oursOnly && (await adminCall(admin, 'GET', `/users/${kcId}`)).status !== 404) left.push('Keycloak: учётная запись осталась');
  return { kcId, userId, left };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const oursOnly = args.includes('--ours-only');
  if (args.includes('--prune')) {
    // Без сервера и Keycloak (ANO-234): у машины владельца нет доступа к хранилищу, срок — фиксированный.
    if (!existsSync(JOURNAL)) {
      console.error(`журнала удалений нет: ${JOURNAL} — удалений ещё не было, заведи пустой; пропал — пустым не заменяй ` +
        '(deploy/RESTORE.md, «Журнал удалений у тебя»)');
      process.exitCode = 1;
    } else {
      keepJournal(JOURNAL);
      console.log(`журнал владельца ${JOURNAL}: записи старше ${JOURNAL_DAYS} дней убраны`);
    }
  } else if (option('--replay')) {
    try {
      const file = option('--replay');
      const { subs, malformed } = journalEntries(readFileSync(file, 'utf8').split('\n'), await realmIssuer(KC_ADMIN));
      if (malformed.length) console.log(`журнал: строки ${malformed.join(', ')} не читаются — удаление в них не повторено, поправь их`);
      let failed = malformed.length;
      for (const sub of subs) {
        const { userId, left } = await deletePerson({ sub, journal: false });
        console.log(`${sub}: ${left.length ? `осталось — ${left.join('; ')}` : `нет (наша строка ${userId ? 'удалена' : 'не нашлась'})`}`);
        if (left.length) failed++;
      }
      keepJournal(file);
      console.log(`повтор по журналу: ${subs.length} удалений этого реалма${failed ? `, не доделано — ${failed}` : ''}`);
      process.exitCode = failed ? 1 : 0;
    } catch (e) {
      console.error(e.message);
      process.exitCode = 1;
    }
  } else try {
    const { kcId, userId, left } = await deletePerson({ username: option('--username'), sub: option('--sub'), oursOnly });
    const done = oursOnly ? 'наши строки удалены, учётная запись Keycloak оставлена' : 'удалён целиком';
    console.log(`Keycloak ${kcId}, у нас ${userId ?? 'строки не было'}: ${left.length ? `осталось — ${left.join('; ')}` : done}`);
    // Живой сеанс удалённого человека заново его не заведёт (ANO-196): отметка согласия снята при входе, и без неё
    // сервер отвечает 403 и ведёт на экран согласия — вернуться можно только новой галочкой.
    if (args.includes('--remove-scripts-client')) console.log(`клиент скриптов убран: ${await removeScriptsClient()}`);
    process.exitCode = left.length ? 1 : 0;
  } catch (e) {
    console.error(e.message);
    process.exitCode = 1;
  }
}

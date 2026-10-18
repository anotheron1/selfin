#!/usr/bin/env node
// Удаление человека со всеми данными (спека 2026-10-17-stand-tools-design.md): временного второго человека после
// проверки и любого человека по его просьбе.
//
//   node tools/delete-person.mjs --username <имя> | --sub <id Keycloak> [--ours-only] [--remove-scripts-client]
//
// Сначала наши строки — одной транзакцией, в порядке tools/person-tables.json, строка users последней; потом учётная
// запись Keycloak. Общей транзакции у двух систем нет: повторный запуск доделывает начатое. В конце проверяются оба
// условия — строк человека ноль, учётной записи нет; выход 1 — что-то осталось.
// --ours-only — только наши строки, учётную запись Keycloak не трогать (ловушка шага 7 плана спринта 6).
// --remove-scripts-client — заодно убрать клиент скриптов: на сервере он живёт только на время проверки.
// Синтетического владельца — историю владельца, на стенде копию — не удаляет. Адреса — как у second-person.mjs.

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { adminToken } from './keycloak-token.mjs';
import { KC_ADMIN, adminCall, findUser, removeScriptsClient, sql } from './second-person.mjs';

/** Таблицы с владельцем в порядке удаления — дочерние до родителей. Сторож — тест в TwoPeopleIsolationIT. */
export const PERSON_TABLES = JSON.parse(readFileSync(new URL('./person-tables.json', import.meta.url), 'utf8'));

const SYNTHETIC_OWNER = '00000000-0000-0000-0000-000000000001';
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** id для SQL: только UUID — в запрос не попадёт произвольная строка. */
const checked = (id) => {
  if (!ID.test(id)) throw new Error(`не id: ${id}`);
  return id;
};

/** SQL удаления наших строк человека: одной транзакцией, по списку, строка users — последней. */
export function deletionSql(userId, tables = PERSON_TABLES) {
  if (checked(userId) === SYNTHETIC_OWNER) {
    throw new Error('это синтетический владелец — история владельца, на стенде копия; удалять его не для этой команды');
  }
  return ['BEGIN;', ...tables.map((table) => `DELETE FROM ${table} WHERE user_id = '${userId}';`),
    `DELETE FROM users WHERE id = '${userId}';`, 'COMMIT;'].join('\n');
}

/** Чем набор таблиц схемы расходится со списком удаления. */
export function tableSetProblems(schemaTables, tables = PERSON_TABLES) {
  return [
    ...schemaTables.filter((table) => !tables.includes(table)).map((table) => `в схеме есть ${table}, в списке удаления — нет`),
    ...tables.filter((table) => !schemaTables.includes(table)).map((table) => `в списке удаления есть ${table}, в схеме — нет`),
  ];
}

/** Удаляет человека; возвращает его id и то, что осталось, — пусто, если удалён целиком. */
export async function deletePerson({ username, sub, oursOnly = false }) {
  const admin = await adminToken(KC_ADMIN);
  const found = sub ?? (await findUser(admin, username))?.id;
  if (!found) throw new Error(`учётной записи ${username} в Keycloak нет; если остались наши строки — удалить по --sub`);
  const kcId = checked(found);

  // Сервер может оказаться новее рабочей копии: с другим набором таблиц удалять нельзя — что-то останется.
  const problems = tableSetProblems(sql(`SELECT table_name FROM information_schema.columns
    WHERE table_schema = 'public' AND column_name = 'user_id'`));
  if (problems.length) throw new Error(`список удаления устарел: ${problems.join('; ')}`);

  const [userId] = sql(`SELECT id FROM users WHERE external_id = '${kcId}'`);
  if (userId) sql(deletionSql(userId));
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
  try {
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

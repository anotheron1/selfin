#!/usr/bin/env node
// Живая проверка изоляции (спека 2026-10-17-stand-tools-design.md) — оракул TwoPeopleIsolationIT на работающем стенде
// или сервере: второй человек с настоящим токеном не видит и не меняет чужого.
//
//   node tools/isolation-check.mjs [--victim-sub <id Keycloak>] [--keep] [--remove-scripts-client]
//
// Хозяин данных по умолчанию — тестовый человек стенда: его токен даёт keycloak-token.mjs, и сверяются ещё и его
// чтения. На сервере — --victim-sub (владелец): его токена у скриптов нет, «ничего не изменилось» — по отпечатку строк
// в базе. На сервере — только после свежей копии: удар, который прошёл бы, изменил бы чужие данные.
// В конце второй человек удаляется (delete-person.mjs), --keep — оставить; --remove-scripts-client убирает клиент
// скриптов — на сервере он живёт только на время проверки. Выход 1 — изоляция нарушена или что-то не убралось.
// Адреса — как у second-person.mjs.

import { pathToFileURL } from 'node:url';
import { adminToken, scriptsToken, SCRIPTS } from './keycloak-token.mjs';
import { KC, KC_ADMIN, adminCall, api, secondPerson, sql } from './second-person.mjs';
import { deletePerson, PERSON_TABLES } from './delete-person.mjs';

/** Ответы, где встретились чужие id: [вызов, ['таблица id', …]]. */
export function leaks(answers, foreign) {
  return Object.entries(answers)
    .map(([call, { text }]) => [call, [...foreign].filter(([id]) => text.includes(id)).map(([id, table]) => `${table} ${id}`)])
    .filter(([, found]) => found.length);
}

/** Вызовы, чей ответ изменился — статус или текст. */
export function changed(before, after) {
  return Object.keys(before).filter((call) => before[call].status !== after[call]?.status || before[call].text !== after[call]?.text);
}

const SECOND = 'tester2';
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Дата по местному времени со сдвигом в днях. */
function day(shift = 0) {
  const d = new Date(Date.now() + shift * 864e5);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const READS = ['/accounts', '/analytics/dashboard', '/analytics/report',
  `/analytics/multi-month?startDate=${day(-31)}&endDate=${day(183)}`, '/analytics/forecast',
  '/analytics/forecast-readiness', '/balance-checkpoints', '/capital/items', '/capital/summary', '/capital/trajectory',
  '/categories', `/events?startDate=${day(-31)}&endDate=${day(183)}`, '/events?priority=LOW', '/funds',
  '/funds/planner', '/hello', '/me', '/pocket', '/settings/wishlist', '/settings/pocket', '/strategy/timeline',
  '/version', '/wishlist/simulation'];

/** Все чтения человека; два расчёта «что если» — POST, но ничего не пишут. */
async function reads(token) {
  const out = {};
  for (const path of READS) out[`GET ${path}`] = await api(token, 'GET', path);
  out['POST /pocket/sandbox'] = await api(token, 'POST', '/pocket/sandbox', '{}');
  out['POST /wishlist/simulation/recompute'] = await api(token, 'POST', '/wishlist/simulation/recompute',
    JSON.stringify({ kind: 'SAVINGS', amount: 40000, targetDate: day(183) }));
  return out;
}

/** Отпечаток чужих строк — всех, кроме строк второго: количество и md5 по каждой таблице. */
function fingerprint(second) {
  return Object.fromEntries([...PERSON_TABLES.map((table) => [table, `user_id <> '${second}'`]), ['users', `id <> '${second}'`]]
    .map(([table, where]) => [table, sql(`SELECT count(*) || ' ' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '-')
      FROM ${table} t WHERE ${where}`)[0]]));
}

async function main(args) {
  const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const victimSub = option('--victim-sub');
  const victimToken = victimSub ? null : await scriptsToken(KC);
  const victim = victimSub
    ? sql(`SELECT id FROM users WHERE external_id = '${ID.test(victimSub) ? victimSub : ''}'`)[0]
    : JSON.parse((await api(victimToken, 'GET', '/me')).text).id;
  if (!victim) throw new Error(`хозяина данных ${victimSub} в базе нет`);

  const second = await secondPerson(SECOND);
  const secondId = JSON.parse((await api(second, 'GET', '/me')).text).id;
  if (secondId === victim) throw new Error('второй человек совпал с хозяином данных');
  console.log(`хозяин данных ${victim}${victimToken ? ' (с токеном — и его чтения)' : ' (по отпечатку в базе)'} | второй ${secondId}`);
  console.log('роль пула приложения:', sql(`SELECT string_agg(DISTINCT usename, ',') FROM pg_stat_activity
    WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()`)[0]);

  const foreign = new Map();
  for (const table of PERSON_TABLES) for (const id of sql(`SELECT id FROM ${table} WHERE user_id <> '${secondId}'`)) foreign.set(id, table);
  for (const id of sql(`SELECT id FROM users WHERE id <> '${secondId}'`)) foreign.set(id, 'users');
  console.log(`чужих строк: ${foreign.size}`);

  const violations = [];
  const victimBefore = victimToken && await reads(victimToken); // заодно достраивает бессрочные повторы до отпечатка
  const before = fingerprint(secondId);

  // ── чтения второго ────────────────────────────────────────────────────────
  const secondReads = await reads(second);
  for (const [call, { status, text }] of Object.entries(secondReads)) {
    if (status !== 200) violations.push(`чтение второго не 200: ${call} ${status} ${text.slice(0, 200)}`);
  }
  for (const [call, found] of leaks(secondReads, foreign)) violations.push(`чужое у второго: ${call} — ${found.slice(0, 3).join(', ')}`);
  console.log(`чтения второго: ${Object.keys(secondReads).length}`);

  // ── удары по id хозяина данных ────────────────────────────────────────────
  const one = (query) => sql(query)[0];
  const ids = {
    account: one(`SELECT id FROM accounts WHERE user_id = '${victim}' AND is_default AND NOT is_deleted`),
    checkpoint: one(`SELECT id FROM balance_checkpoints WHERE user_id = '${victim}' LIMIT 1`),
    item: one(`SELECT id FROM capital_items WHERE user_id = '${victim}' LIMIT 1`),
    revaluation: one(`SELECT id FROM capital_revaluations WHERE user_id = '${victim}' LIMIT 1`),
    category: one(`SELECT id FROM categories WHERE user_id = '${victim}' AND NOT is_deleted AND type = 'EXPENSE' LIMIT 1`),
    plan: one(`SELECT id FROM financial_events WHERE user_id = '${victim}' AND event_kind = 'PLAN' AND NOT is_deleted
      AND wishlist_status IS NULL AND date >= CURRENT_DATE LIMIT 1`),
    fact: one(`SELECT id FROM financial_events WHERE user_id = '${victim}' AND event_kind = 'FACT' AND NOT is_deleted LIMIT 1`),
    wish: one(`SELECT id FROM financial_events WHERE user_id = '${victim}' AND wishlist_status IS NOT NULL AND NOT is_deleted
      ORDER BY (wishlist_status = 'OPEN') DESC LIMIT 1`),
    fund: one(`SELECT id FROM target_funds WHERE user_id = '${victim}' AND NOT is_deleted
      ORDER BY (wishlist_status IS NULL) DESC, (purchase_type <> 'CREDIT') DESC LIMIT 1`),
    credit: one(`SELECT id FROM target_funds WHERE user_id = '${victim}' AND NOT is_deleted AND wishlist_status IS NOT NULL LIMIT 1`),
  };
  const missing = Object.keys(ids).filter((role) => !ids[role]);
  if (missing.length) console.log('у хозяина данных нет строк для ударов:', missing.join(', '));
  const yesterday = day(-1);
  const HITS = [
    ['GET', '/accounts/%s', 'account'], ['PUT', '/accounts/%s', 'account', { name: 'Чужая', kind: 'DEBIT' }],
    ['PATCH', '/accounts/%s/default', 'account'], ['PUT', '/balance-checkpoints/%s', 'checkpoint', { date: yesterday, amount: 1 }],
    ['GET', '/capital/items/%s', 'item'], ['GET', '/capital/items/%s/revaluations', 'item', null, '200 []'],
    ['PUT', '/capital/items/%s', 'item', { name: 'Чужое' }],
    ['POST', '/capital/items/%s/revaluations', 'item', { value: 1, valuedAt: yesterday }],
    ['PUT', '/capital/revaluations/%s', 'revaluation', { value: 1 }],
    ['PUT', '/categories/%s', 'category', { name: 'Чужая', type: 'EXPENSE' }], ['PATCH', '/categories/%s/priority', 'category'],
    ['PUT', '/events/%s', 'plan', { date: day(5), categoryId: ids.category, type: 'EXPENSE', plannedAmount: 1 }],
    ['PATCH', '/events/%s/fact', 'fact', { factAmount: 1 }], ['PATCH', '/events/%s/priority', 'plan'],
    ['POST', '/events/%s/facts', 'plan', { date: day(), factAmount: 1 }],
    ['PATCH', '/events/%s/wishlist-params', 'wish', { plannedAmount: 1 }],
    ['PATCH', '/events/%s/wishlist-status', 'wish', { status: 'DISMISSED' }],
    ['POST', '/wishlist/items/%s/convert', 'wish', { sourceKind: 'WISHLIST', target: 'PLAN_EVENT' }],
    ['POST', '/wishlist/items/%s/fix', 'wish', { sourceKind: 'WISHLIST', amount: 1 }],
    ['PUT', '/funds/%s', 'fund', { name: 'Чужая', targetAmount: 1 }],
    ['POST', '/funds/%s/transfer', 'fund', { amount: 1, confirm: true }],
    ['PATCH', '/funds/%s/wishlist-params', 'credit', { targetAmount: 1 }],
    ['PATCH', '/funds/%s/wishlist-status', 'credit', { status: 'DISMISSED' }],
    // Удаления — последними, дочерние до родителей.
    ['DELETE', '/capital/revaluations/%s', 'revaluation'], ['DELETE', '/capital/items/%s', 'item'],
    ['DELETE', '/balance-checkpoints/%s', 'checkpoint'], ['DELETE', '/events/%s', 'plan'],
    ['DELETE', '/funds/%s?money=RETURN', 'fund'], ['DELETE', '/accounts/%s', 'account'], ['DELETE', '/categories/%s', 'category'],
  ];
  let hits = 0;
  for (const [method, path, role, body, expected = '404'] of HITS) {
    if (!ids[role]) continue;
    const { status, text } = await api(second, method, path.replace('%s', ids[role]), body && JSON.stringify(body));
    const answer = status === 404 ? '404' : `${status} ${text}`;
    hits++;
    if (answer !== expected) violations.push(`удар прошёл иначе: ${method} ${path} — ${answer.slice(0, 200)}`);
  }
  console.log(`ударов по чужим id: ${hits}`);

  // ── ссылки на чужие строки в теле записи (ANO-147) ────────────────────────
  const REFS = [
    ['POST /events — categoryId', '/events', { date: day(5), categoryId: ids.category, type: 'EXPENSE', plannedAmount: 1 }, 404],
    ['POST /events/facts — categoryId', '/events/facts', { date: day(), categoryId: ids.category, type: 'EXPENSE', factAmount: 1 }, 404],
    ['POST /accounts — purposeCategoryId', '/accounts', { name: 'Своя', kind: 'DEBIT', purposeCategoryId: ids.category }, 404],
    ['POST /balance-checkpoints — accountId', '/balance-checkpoints', { date: yesterday, amount: 1, accountId: ids.account }, 404],
    ['POST /funds — accountId', '/funds', { name: 'Своя', targetAmount: 1, accountId: ids.account }, 404],
    ['POST /events FUND_TRANSFER — targetFundId', '/events', { date: day(5), type: 'FUND_TRANSFER', plannedAmount: 1, targetFundId: ids.fund }, 409],
  ];
  for (const [name, path, body, expected] of REFS) {
    const { status, text } = await api(second, 'POST', path, JSON.stringify(body));
    if (status !== expected) violations.push(`ссылка на чужое: ${name} — ${status}, ждали ${expected}: ${text.slice(0, 160)}`);
  }
  if (ids.fund) {
    const [refs] = sql(`SELECT count(*) FROM financial_events WHERE user_id = '${secondId}' AND target_fund_id = '${ids.fund}'`);
    if (refs !== '0') violations.push(`у второго строк со ссылкой на чужую копилку: ${refs}`);
  }
  console.log(`записей со ссылкой на чужое: ${REFS.length}`);

  // ── у хозяина данных ничего не изменилось ─────────────────────────────────
  const after = fingerprint(secondId);
  for (const table of Object.keys(before).filter((table) => before[table] !== after[table])) {
    violations.push(`чужие строки изменились: ${table} — ${before[table]} → ${after[table]}`);
  }
  if (victimToken) {
    for (const call of changed(victimBefore, await reads(victimToken))) violations.push(`чтение хозяина данных изменилось: ${call}`);
  }
  console.log(`отпечаток чужих строк: ${Object.keys(before).length} таблиц${victimToken ? `, чтений хозяина данных: ${Object.keys(victimBefore).length}` : ''}`);

  // ── уборка ────────────────────────────────────────────────────────────────
  if (!args.includes('--keep')) {
    const { left } = await deletePerson({ username: SECOND });
    if (left.length) violations.push(`второй убран не целиком: ${left.join('; ')}`);
    else console.log(`второй удалён: в Keycloak нет, строк — ноль`);
  }
  if (args.includes('--remove-scripts-client')) {
    const admin = await adminToken(KC_ADMIN);
    const clients = await (await adminCall(admin, 'GET', `/clients?clientId=${SCRIPTS.clientId}`)).json();
    for (const client of clients) await adminCall(admin, 'DELETE', `/clients/${client.id}`);
    console.log(`клиент скриптов убран: ${clients.length}`);
  }

  console.log(violations.length ? `ИЗОЛЯЦИЯ НАРУШЕНА — ${violations.length}:\n  ${violations.join('\n  ')}` : 'изоляция держит: нарушений нет');
  return violations.length === 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = (await main(process.argv.slice(2))) ? 0 : 1;
}

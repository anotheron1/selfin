#!/usr/bin/env node
// Живая проверка изоляции (спека 2026-10-17-stand-tools-design.md) — оракул TwoPeopleIsolationIT на работающем стенде
// или сервере: второй человек с настоящим токеном не видит и не меняет чужого.
//
//   node tools/isolation-check.mjs [--victim-sub <id Keycloak>] [--keep] [--remove-scripts-client]
//
// Хозяин данных по умолчанию — тестовый человек стенда: его токен даёт keycloak-token.mjs, и сверяются ещё и его
// чтения. На сервере — --victim-sub (владелец): его токена у скриптов нет, «ничего не изменилось» — по отпечатку строк
// в базе. На сервере — только после свежей копии: удар, который прошёл бы, изменил бы чужие данные.
// Второй человек — всегда новый (second-person.mjs) и в конце удаляется по своему id, даже если проверка оборвалась;
// --keep — оставить. --remove-scripts-client убирает клиент скриптов — на сервере он живёт только на время проверки.
// Выход 1 — изоляция нарушена, проверка оборвалась или что-то не убралось. Адреса — как у second-person.mjs.

import { pathToFileURL } from 'node:url';
import { scriptsToken } from './keycloak-token.mjs';
import { KC, api, removeScriptsClient, secondPerson, sql } from './second-person.mjs';
import { deletePerson, PERSON_TABLES } from './delete-person.mjs';

/** Ответы, где встретились чужие id: [вызов, ['таблица id', …]]. */
export function leaks(answers, foreign) {
  return Object.entries(answers)
    .map(([call, { text }]) => [call, [...foreign].filter(([id]) => text.includes(id)).map(([id, table]) => `${table} ${id}`)])
    .filter(([, found]) => found.length);
}

/** JSON без учёта порядка в массивах и ключей — как NON_EXTENSIBLE в TwoPeopleIsolationIT; не JSON — как есть. */
function canonical(text = '') {
  const sortDeep = (value) => {
    if (Array.isArray(value)) return value.map(sortDeep).map((v) => JSON.stringify(v)).sort().map((v) => JSON.parse(v));
    if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((k) => [k, sortDeep(value[k])]));
    return value;
  };
  try {
    return JSON.stringify(sortDeep(JSON.parse(text)));
  } catch {
    return text;
  }
}

/**
 * Вызовы, чей ответ изменился — статус или содержимое. Порядок строк в массиве — не изменение: часть запросов идёт без
 * ORDER BY, и чужие записи меняют порядок строк, а не их состав (ANO-232).
 */
export function changed(before, after) {
  return Object.keys(before).filter((call) => before[call].status !== after[call]?.status
    || canonical(before[call].text) !== canonical(after[call]?.text));
}

/** Удары и ссылки, которые есть чем проверить: без чужой строки нужной роли вызов проверял бы не изоляцию. */
export const runnable = (entries, ids) => entries.filter((entry) => ids[entry.role]);

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

/** Сама проверка: нарушения — в violations. Второй человек уже заведён, уборка — у вызывающего. */
async function probe(person, victim, victimToken, violations) {
  const secondId = JSON.parse((await api(person.token, 'GET', '/me')).text).id;
  if (secondId === victim) throw new Error('второй человек совпал с хозяином данных');
  console.log(`хозяин данных ${victim}${victimToken ? ' (с токеном — и его чтения)' : ' (по отпечатку в базе)'} | второй ${secondId}`);
  console.log('роль пула приложения:', sql(`SELECT string_agg(DISTINCT usename, ',') FROM pg_stat_activity
    WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()`)[0]);

  // Прогрев — первым (ревью Codex на #167): чтения хозяина данных достраивают его бессрочные повторы, и их id должны
  // попасть и в список чужих, и в отпечаток — как в TwoPeopleIsolationIT, где чужие id снимаются после холостых чтений.
  const victimBefore = victimToken && await reads(victimToken);

  const foreign = new Map();
  for (const table of PERSON_TABLES) for (const id of sql(`SELECT id FROM ${table} WHERE user_id <> '${secondId}'`)) foreign.set(id, table);
  for (const id of sql(`SELECT id FROM users WHERE id <> '${secondId}'`)) foreign.set(id, 'users');
  console.log(`чужих строк: ${foreign.size}`);
  const before = fingerprint(secondId);

  // ── чтения второго ────────────────────────────────────────────────────────
  const secondReads = await reads(person.token);
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
    // fix и convert от копилки идут через другой репозиторий — свой удар, как в TwoPeopleIsolationIT (ревью Codex на #167).
    creditFund: one(`SELECT id FROM target_funds WHERE user_id = '${victim}' AND NOT is_deleted AND purchase_type = 'CREDIT'
      AND wishlist_status IS NOT NULL ORDER BY (wishlist_status = 'OPEN') DESC LIMIT 1`),
  };
  // Своя категория второго — в теле правки чужого события: чужим должен быть только id в пути, иначе 404 даст проверка
  // категории и спрячет дыру в поиске события (ревью Codex на #167; так же в TwoPeopleIsolationIT).
  const ownCategory = one(`SELECT id FROM categories WHERE user_id = '${secondId}' AND NOT is_deleted AND type = 'EXPENSE' LIMIT 1`);
  const missing = Object.keys(ids).filter((role) => !ids[role]);
  if (missing.length) console.log('у хозяина данных нет строк для ударов:', missing.join(', '));
  const yesterday = day(-1);
  const hit = (method, path, role, body, expected = '404') => ({ method, path, role, body, expected });
  const HITS = [
    hit('GET', '/accounts/%s', 'account'), hit('PUT', '/accounts/%s', 'account', { name: 'Чужая', kind: 'DEBIT' }),
    hit('PATCH', '/accounts/%s/default', 'account'), hit('PUT', '/balance-checkpoints/%s', 'checkpoint', { date: yesterday, amount: 1 }),
    hit('GET', '/capital/items/%s', 'item'), hit('GET', '/capital/items/%s/revaluations', 'item', null, '200 []'),
    hit('PUT', '/capital/items/%s', 'item', { name: 'Чужое' }),
    hit('POST', '/capital/items/%s/revaluations', 'item', { value: 1, valuedAt: yesterday }),
    hit('PUT', '/capital/revaluations/%s', 'revaluation', { value: 1 }),
    hit('PUT', '/categories/%s', 'category', { name: 'Чужая', type: 'EXPENSE' }), hit('PATCH', '/categories/%s/priority', 'category'),
    hit('PUT', '/events/%s', 'plan', { date: day(5), categoryId: ownCategory, type: 'EXPENSE', plannedAmount: 1 }),
    hit('PATCH', '/events/%s/fact', 'fact', { factAmount: 1 }), hit('PATCH', '/events/%s/priority', 'plan'),
    hit('POST', '/events/%s/facts', 'plan', { date: day(), factAmount: 1 }),
    hit('PATCH', '/events/%s/wishlist-params', 'wish', { plannedAmount: 1 }),
    hit('PATCH', '/events/%s/wishlist-status', 'wish', { status: 'DISMISSED' }),
    hit('POST', '/wishlist/items/%s/convert', 'wish', { sourceKind: 'WISHLIST', target: 'PLAN_EVENT' }),
    hit('POST', '/wishlist/items/%s/fix', 'wish', { sourceKind: 'WISHLIST', amount: 1 }),
    hit('POST', '/wishlist/items/%s/fix', 'creditFund',
      { sourceKind: 'CREDIT', amount: 1, date: day(183), stretchMonths: 0, creditRate: 12, creditTermMonths: 12 }),
    hit('POST', '/wishlist/items/%s/convert', 'creditFund', { sourceKind: 'CREDIT', target: 'PLAN_EVENT' }),
    hit('PUT', '/funds/%s', 'fund', { name: 'Чужая', targetAmount: 1 }),
    hit('POST', '/funds/%s/transfer', 'fund', { amount: 1, confirm: true }),
    hit('PATCH', '/funds/%s/wishlist-params', 'credit', { targetAmount: 1 }),
    hit('PATCH', '/funds/%s/wishlist-status', 'credit', { status: 'DISMISSED' }),
    // Удаления — последними, дочерние до родителей.
    hit('DELETE', '/capital/revaluations/%s', 'revaluation'), hit('DELETE', '/capital/items/%s', 'item'),
    hit('DELETE', '/balance-checkpoints/%s', 'checkpoint'), hit('DELETE', '/events/%s', 'plan'),
    hit('DELETE', '/funds/%s?money=RETURN', 'fund'), hit('DELETE', '/accounts/%s', 'account'), hit('DELETE', '/categories/%s', 'category'),
  ];
  const hits = runnable(HITS, ids);
  for (const { method, path, role, body, expected } of hits) {
    const { status, text } = await api(person.token, method, path.replace('%s', ids[role]), body && JSON.stringify(body));
    const answer = status === 404 ? '404' : `${status} ${text}`;
    if (answer !== expected) violations.push(`удар прошёл иначе: ${method} ${path} — ${answer.slice(0, 200)}`);
  }
  console.log(`ударов по чужим id: ${hits.length}`);

  // ── ссылки на чужие строки в теле записи (ANO-147) ────────────────────────
  const ref = (name, path, role, body, expected) => ({ name, path, role, body, expected });
  const refs = runnable([
    ref('POST /events — categoryId', '/events', 'category', { date: day(5), categoryId: ids.category, type: 'EXPENSE', plannedAmount: 1 }, 404),
    ref('POST /events/facts — categoryId', '/events/facts', 'category', { date: day(), categoryId: ids.category, type: 'EXPENSE', factAmount: 1 }, 404),
    ref('POST /accounts — purposeCategoryId', '/accounts', 'category', { name: 'Своя', kind: 'DEBIT', purposeCategoryId: ids.category }, 404),
    ref('POST /balance-checkpoints — accountId', '/balance-checkpoints', 'account', { date: yesterday, amount: 1, accountId: ids.account }, 404),
    ref('POST /funds — accountId', '/funds', 'account', { name: 'Своя', targetAmount: 1, accountId: ids.account }, 404),
    ref('POST /events FUND_TRANSFER — targetFundId', '/events', 'fund', { date: day(5), type: 'FUND_TRANSFER', plannedAmount: 1, targetFundId: ids.fund }, 409),
  ], ids);
  for (const { name, path, body, expected } of refs) {
    const { status, text } = await api(person.token, 'POST', path, JSON.stringify(body));
    if (status !== expected) violations.push(`ссылка на чужое: ${name} — ${status}, ждали ${expected}: ${text.slice(0, 160)}`);
  }
  if (ids.fund) {
    const [count] = sql(`SELECT count(*) FROM financial_events WHERE user_id = '${secondId}' AND target_fund_id = '${ids.fund}'`);
    if (count !== '0') violations.push(`у второго строк со ссылкой на чужую копилку: ${count}`);
  }
  console.log(`записей со ссылкой на чужое: ${refs.length}`);

  // ── у хозяина данных ничего не изменилось ─────────────────────────────────
  const after = fingerprint(secondId);
  for (const table of Object.keys(before).filter((table) => before[table] !== after[table])) {
    violations.push(`чужие строки изменились: ${table} — ${before[table]} → ${after[table]}`);
  }
  if (victimToken) {
    for (const call of changed(victimBefore, await reads(victimToken))) violations.push(`чтение хозяина данных изменилось: ${call}`);
  }
  console.log(`отпечаток чужих строк: ${Object.keys(before).length} таблиц${victimToken ? `, чтений хозяина данных: ${Object.keys(victimBefore).length}` : ''}`);
}

async function main(args) {
  const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const violations = [];
  let person;
  try {
    const victimSub = option('--victim-sub');
    const victimToken = victimSub ? null : await scriptsToken(KC);
    const victim = victimSub
      ? sql(`SELECT id FROM users WHERE external_id = '${ID.test(victimSub) ? victimSub : ''}'`)[0]
      : JSON.parse((await api(victimToken, 'GET', '/me')).text).id;
    if (!victim) throw new Error(`хозяина данных ${victimSub} в базе нет`);
    person = await secondPerson();
    await probe(person, victim, victimToken, violations);
  } catch (e) {
    violations.push(`проверка оборвалась: ${e.message}`);
  } finally {
    // Уборка — и после оборванной проверки (ревью Codex на #167): второй человек и клиент скриптов не должны остаться.
    if (person && !args.includes('--keep')) {
      try {
        const { left } = await deletePerson({ sub: person.kcId });
        if (left.length) violations.push(`второй убран не целиком: ${left.join('; ')}`);
        else console.log(`второй ${person.username} удалён: в Keycloak нет, строк — ноль`);
      } catch (e) {
        violations.push(`второй ${person.username} не убран: ${e.message}`);
      }
    }
    if (args.includes('--remove-scripts-client')) {
      try {
        console.log(`клиент скриптов убран: ${await removeScriptsClient()}`);
      } catch (e) {
        violations.push(`клиент скриптов не убран: ${e.message}`);
      }
    }
  }
  console.log(violations.length ? `ПРОВЕРКА НЕ ПРОШЛА — ${violations.length}:\n  ${violations.join('\n  ')}` : 'изоляция держит: нарушений нет');
  return violations.length === 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = (await main(process.argv.slice(2))) ? 0 : 1;
}

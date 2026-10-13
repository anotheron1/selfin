#!/usr/bin/env node
// Стенд: Keycloak поднял реалм selfin из репозитория, а кнопка Telegram ведёт в Telegram так, как настроено (ANO-143).
// Спека: docs/superpowers/specs/2026-10-12-keycloak-telegram-design.md
//
//   node tools/keycloak-check.mjs [<адрес Keycloak>]   по умолчанию http://127.0.0.1:8180; выход 0 — всё сходится
//
// Проходит путь человека до Telegram: страница входа реалма → кнопка Telegram → переход на oauth.telegram.org.
// Сам вход в Telegram — руками: подтверждение приходит в приложение Telegram.

import { createHash, randomBytes } from 'node:crypto';

const KC = (process.argv[2] ?? 'http://127.0.0.1:8180').replace(/\/$/, '');
const REALM = `${KC}/realms/selfin`;
const problems = [];
const check = (ok, what) => {
  console.log(`${ok ? 'да ' : 'НЕТ'}  ${what}`);
  if (!ok) problems.push(what);
};

const discovery = await fetch(`${REALM}/.well-known/openid-configuration`)
  .catch((e) => ({ ok: false, status: e.cause?.code ?? e.message }));
check(discovery.ok, `реалм selfin отвечает — ${discovery.status}`);
if (!discovery.ok) process.exit(1);
check((await discovery.json()).issuer === REALM, `издатель токенов — ${REALM}`);

// Страница входа консоли учётной записи — так её видит человек.
const verifier = randomBytes(32).toString('base64url');
const auth = new URL(`${REALM}/protocol/openid-connect/auth`);
auth.search = new URLSearchParams({
  client_id: 'account-console', redirect_uri: `${REALM}/account/`, response_type: 'code', scope: 'openid',
  state: randomBytes(8).toString('hex'), code_challenge_method: 'S256',
  code_challenge: createHash('sha256').update(verifier).digest('base64url'),
}).toString();
const page = await fetch(auth, { redirect: 'manual' });
const html = await page.text();
const cookies = page.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
check(page.status === 200, `страница входа — ${page.status}`);
check(/<html[^>]*lang="ru"/.test(html), 'страница входа по-русски');
const button = html.match(/href="([^"]*\/broker\/telegram\/login\?[^"]*)"/);
check(Boolean(button), 'на странице кнопка Telegram');
if (!button) process.exit(1);

// Кнопка — переход на Telegram: туда уходят ровно наши настройки.
const login = await fetch(new URL(button[1].replaceAll('&amp;', '&'), KC), { redirect: 'manual', headers: { cookie: cookies } });
const location = login.headers.get('location') ?? '';
check(location.startsWith('https://oauth.telegram.org/auth?'), `кнопка ведёт на oauth.telegram.org — ${login.status}`);
const to = new URL(location || 'https://invalid/').searchParams;
check(to.get('response_type') === 'code', `ответ кодом — ${to.get('response_type')}`);
check(to.get('code_challenge_method') === 'S256' && Boolean(to.get('code_challenge')), `PKCE S256 — ${to.get('code_challenge_method')}`);
check(to.get('scope') === 'openid profile', `просим openid и profile, без телефона — «${to.get('scope')}»`);
check(to.get('redirect_uri') === `${REALM}/broker/telegram/endpoint`, `возврат — ${to.get('redirect_uri')}`);
check(Boolean(to.get('client_id')), `Client ID бота задан — ${to.get('client_id') ? 'да' : 'пусто: нет TELEGRAM_CLIENT_ID в .env'}`);

console.log(problems.length ? `Итог: не сходится — ${problems.length}` : 'Итог: сходится');
process.exitCode = problems.length ? 1 : 0;

#!/usr/bin/env node
// Стенд: Keycloak поднял реалм selfin из репозитория, а кнопка Telegram ведёт в Telegram так, как настроено (ANO-143);
// вход в selfin идёт через наш сервер, и сервер узнаёт, чей запрос (ANO-195).
// Спеки: docs/superpowers/specs/2026-10-12-keycloak-telegram-design.md, 2026-10-13-server-knows-owner-design.md
//
//   node tools/keycloak-check.mjs [<адрес Keycloak> [<адрес экрана>]]   по умолчанию http://127.0.0.1:8180
//                                                                         и http://127.0.0.1:5174; выход 0 — всё сходится
//
// Проходит путь человека до Telegram: страница входа реалма → кнопка Telegram → переход на oauth.telegram.org.
// Сам вход в Telegram — руками: подтверждение приходит в приложение Telegram. Вход в selfin — тестовым человеком
// с паролем (tools/keycloak-token.mjs): экран → наш сервер → Keycloak → форма → наш сервер → экран с сеансом.

import { createHash, randomBytes } from 'node:crypto';
import { scriptsToken, TESTER } from './keycloak-token.mjs';

const KC = (process.argv[2] ?? 'http://127.0.0.1:8180').replace(/\/$/, '');
const APP = (process.argv[3] ?? 'http://127.0.0.1:5174').replace(/\/$/, '');
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

// ── Вход в selfin через наш сервер (ANO-195) ─────────────────────────────────────────────────
// Cookie — как у браузера: свои у экрана, свои у Keycloak.
const jar = { app: new Map(), kc: new Map() };
const keep = (cookies, response) => {
  for (const line of response.headers.getSetCookie()) {
    const [pair] = line.split(';');
    cookies.set(pair.slice(0, pair.indexOf('=')), pair.slice(pair.indexOf('=') + 1));
  }
  return response;
};
const sent = (cookies) => [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');
const go = (url, cookies, init = {}) => fetch(url, { redirect: 'manual', ...init, headers: { ...init.headers, cookie: sent(cookies) } })
  .then((response) => keep(cookies, response));

const scripts = await scriptsToken(KC);
// Тем же набором cookie, что и вход: запомни сервер этот запрос — после входа вернул бы на JSON, а не на «/».
check((await go(`${APP}/api/v1/pocket`, jar.app)).status === 401, 'API без входа — 401');

const start = await go(`${APP}/oauth2/authorization/keycloak`, jar.app);
const toKeycloak = new URL(start.headers.get('location') ?? 'https://invalid/');
check(toKeycloak.href.startsWith(`${REALM}/protocol/openid-connect/auth?`), `вход уводит в Keycloak — ${start.status}`);
check(toKeycloak.searchParams.get('client_id') === 'selfin', `клиент — ${toKeycloak.searchParams.get('client_id')}`);
check(toKeycloak.searchParams.get('code_challenge_method') === 'S256', `PKCE S256 — ${toKeycloak.searchParams.get('code_challenge_method')}`);
check(toKeycloak.searchParams.get('redirect_uri') === `${APP}/login/oauth2/code/keycloak`, `возврат — ${toKeycloak.searchParams.get('redirect_uri')}`);

const form = await go(toKeycloak, jar.kc);
const action = (await form.text()).match(/<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/)?.[1]?.replaceAll('&amp;', '&');
check(Boolean(action), `у Keycloak форма пароля — ${form.status}`);
const back = await go(action, jar.kc, {
  method: 'POST',
  headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ username: TESTER.username, password: TESTER.password, credentialId: '' }),
});
const callback = back.headers.get('location') ?? '';
check(callback.startsWith(`${APP}/login/oauth2/code/keycloak?`), `Keycloak вернул на экран — ${back.status}`);
const home = await go(callback, jar.app);
check(home.status === 302 && new URL(home.headers.get('location') ?? '/', APP).pathname === '/', `наш сервер открыл сеанс и вернул на «/» — ${home.status}`);

const me = await go(`${APP}/api/v1/me`, jar.app);
const person = me.ok ? (await me.json()).id : null;
check(Boolean(person), `сеанс: чей запрос — ${person ?? me.status}`);
check(jar.app.has('XSRF-TOKEN'), 'сеанс: cookie XSRF-TOKEN для фронта');
const noCsrf = await go(`${APP}/api/v1/categories`, jar.app, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
check(noCsrf.status === 403, `запись сеанса без CSRF — ${noCsrf.status}`);
// Пустое тело с CSRF — 400 проверки полей: CSRF пройден, а стенд не тронут.
const withCsrf = await go(`${APP}/api/v1/categories`, jar.app, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'x-xsrf-token': decodeURIComponent(jar.app.get('XSRF-TOKEN') ?? '') },
  body: '{}',
});
check(withCsrf.status === 400, `запись сеанса с CSRF доходит до проверки полей — ${withCsrf.status}`);

const bearer = await fetch(`${APP}/api/v1/me`, { headers: { authorization: `Bearer ${scripts}` } });
const robot = bearer.ok ? (await bearer.json()).id : null;
check(Boolean(robot) && robot !== person, `Bearer скриптов — свой владелец: ${robot ?? bearer.status}`);

console.log(problems.length ? `Итог: не сходится — ${problems.length}` : 'Итог: сходится');
process.exitCode = problems.length ? 1 : 0;

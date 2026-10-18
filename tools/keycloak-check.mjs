#!/usr/bin/env node
// Стенд: Keycloak поднял реалм selfin из репозитория, а кнопка Telegram ведёт в Telegram так, как настроено (ANO-143);
// вход в selfin идёт через наш сервер, и сервер узнаёт, чей запрос (ANO-195).
// Спеки: docs/superpowers/specs/2026-10-12-keycloak-telegram-design.md, 2026-10-13-server-knows-owner-design.md
//
//   node tools/keycloak-check.mjs [<адрес Keycloak> [<адрес экрана>]]   по умолчанию http://127.0.0.1:8180
//                                                                         и http://127.0.0.1:5174; выход 0 — всё сходится
//
// Сборка для сервера на своей машине (ANO-197): консоль администратора и реалм master снаружи закрыты — Admin API
// по своему адресу, SELFIN_KC_ADMIN (например, http://127.0.0.1:8190/auth); без неё — тот же адрес Keycloak.
// Настоящий сервер — только до входа: тестового человека с паролем из репозитория там не заводим (keycloak-token.mjs).
//
// Проходит путь человека до Telegram: страница входа реалма → кнопка Telegram → переход на oauth.telegram.org.
// Сам вход в Telegram — руками: подтверждение приходит в приложение Telegram. Вход в selfin — тестовым человеком
// с паролем (tools/keycloak-token.mjs): экран → наш сервер → Keycloak → форма → наш сервер → экран с сеансом.

import { createHash, randomBytes } from 'node:crypto';
import { CONSENT_VERSION, ownIssuer, scriptsToken, TESTER } from './keycloak-token.mjs';

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
const { issuer } = await discovery.json();
check(issuer === REALM, `издатель токенов — ${REALM}`);

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
// Тестовый человек — только свой Keycloak: на сервере он открыл бы вход в обход Telegram (ревью Codex на #171).
if (!ownIssuer(issuer)) {
  console.log(`вход тестовым человеком пропущен: ${issuer} — Keycloak не на этой машине, ничего не заведено`);
  console.log(problems.length ? `Итог: не сходится — ${problems.length}` : 'Итог: до входа сходится');
  process.exit(problems.length ? 1 : 0);
}

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
// Галочка экрана входа (ANO-196): POST с CSRF кладёт отметку в сеанс, и только с ней наш адрес входа ведёт в Keycloak.
// Cookie XSRF-TOKEN выписывает любой ответ сервера; после выхода она стёрта — тогда сначала коммит сборки.
const consent = async (cookies) => {
  if (!cookies.get('XSRF-TOKEN')) await go(`${APP}/api/v1/version`, cookies);
  return go(`${APP}/api/v1/consent`, cookies, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-xsrf-token': decodeURIComponent(cookies.get('XSRF-TOKEN') ?? '') },
    body: JSON.stringify({ version: CONSENT_VERSION }),
  });
};

const scripts = await scriptsToken(process.env.SELFIN_KC_ADMIN ?? KC);
// Тем же набором cookie, что и вход: запомни сервер этот запрос — после входа вернул бы на JSON, а не на «/».
check((await go(`${APP}/api/v1/pocket`, jar.app)).status === 401, 'API без входа — 401');

const unticked = await go(`${APP}/oauth2/authorization/keycloak`, new Map());
check(unticked.status === 302 && new URL(unticked.headers.get('location') ?? 'https://invalid/', APP).pathname === '/login',
  `без галочки наш адрес входа ведёт на экран входа, а не в Keycloak — ${unticked.status} ${unticked.headers.get('location') ?? ''} (ANO-196)`);
check((await consent(jar.app)).status === 204, 'галочка согласия принята — 204 (ANO-196)');

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
// Вход держится 30 дней (ANO-152): срок сеанса Keycloak виден по его cookie KEYCLOAK_SESSION, наш — по JSESSIONID.
const lifetime = (response, name) =>
  response.headers.getSetCookie().find((c) => c.startsWith(`${name}=`))?.match(/Max-Age=(\d+)/)?.[1] ?? 'до закрытия браузера';
const kcSession = lifetime(back, 'KEYCLOAK_SESSION');
check(kcSession === '2592000', `сеанс Keycloak — не дольше 30 дней: ${kcSession} (ANO-152)`);
const home = await go(callback, jar.app);
check(home.status === 302 && new URL(home.headers.get('location') ?? '/', APP).pathname === '/', `наш сервер открыл сеанс и вернул на «/» — ${home.status}`);
const ourSession = lifetime(home, 'JSESSIONID');
check(ourSession === '2592000', `cookie нашего сеанса — на 30 дней, переживает закрытие браузера: ${ourSession} (ANO-152)`);

const me = await go(`${APP}/api/v1/me`, jar.app);
const who = me.ok ? await me.json() : {};
const person = who.id ?? null;
check(Boolean(person), `сеанс: чей запрос — ${person ?? me.status}`);
check(Boolean(who.name), `сеанс: кто вошёл — «${who.name ?? ''}» (ANO-152)`);
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

// Скрипты ходят тестовым человеком (ANO-144): по Bearer — тот же владелец, что у входа формой.
const bearer = await fetch(`${APP}/api/v1/me`, { headers: { authorization: `Bearer ${scripts}` } });
const robot = bearer.ok ? (await bearer.json()).id : null;
check(robot === person, `Bearer скриптов — тот же человек, что вошёл формой: ${robot ?? bearer.status}`);

// ── Экран входа и выход (ANO-152) ────────────────────────────────────────────────────────────
// Кнопка экрана входа несёт провайдера: Keycloak уводит прямо к Telegram, своей страницы не показывает.
const fresh = { app: new Map(), kc: new Map() };
await consent(fresh.app);
let hop = new URL((await go(`${APP}/oauth2/authorization/keycloak?provider=telegram`, fresh.app)).headers.get('location') ?? 'https://invalid/');
check(hop.searchParams.get('kc_idp_hint') === 'telegram', `кнопка экрана входа — с подсказкой Telegram: ${hop.searchParams.get('kc_idp_hint')}`);
for (let i = 0; i < 3 && hop.origin === new URL(KC).origin; i++) {
  hop = new URL((await go(hop, fresh.kc)).headers.get('location') ?? 'https://invalid/', hop);
}
check(hop.host === 'oauth.telegram.org', `подсказка — сразу к Telegram, без страницы Keycloak: ${hop.host}`);

// Выход — и у нас, и в Keycloak: сервер отвечает адресом выхода Keycloak, браузер проходит его и
// возвращается на экран входа; новый вход снова спрашивает пароль — сеанса Keycloak больше нет.
const out = await go(`${APP}/logout`, jar.app, {
  method: 'POST',
  headers: { 'x-xsrf-token': decodeURIComponent(jar.app.get('XSRF-TOKEN') ?? '') },
});
const exit = out.ok ? (await out.json()).redirect ?? '' : '';
check(exit.startsWith(`${REALM}/protocol/openid-connect/logout?`), `выход: адрес выхода Keycloak — ${out.status}`);
const ended = await go(exit || 'https://invalid/', jar.kc);
check(ended.headers.get('location') === `${APP}/login`, `Keycloak погасил сеанс и вернул на экран входа — ${ended.status} ${ended.headers.get('location') ?? ''}`);
check((await go(`${APP}/api/v1/me`, jar.app)).status === 401, 'наш сеанс погашен — API 401');
await consent(jar.app);
const again = await go(`${APP}/oauth2/authorization/keycloak`, jar.app);
const againForm = await go(new URL(again.headers.get('location') ?? 'https://invalid/'), jar.kc);
check(/id="kc-form-login"/.test(await againForm.text()), 'новый вход снова спрашивает пароль — сеанс Keycloak погашен');

console.log(problems.length ? `Итог: не сходится — ${problems.length}` : 'Итог: сходится');
process.exitCode = problems.length ? 1 : 0;

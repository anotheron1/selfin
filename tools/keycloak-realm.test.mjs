// Реалм selfin как код (ANO-143): вход через Telegram настроен так, как решено, и ключей бота в файле нет.
// Спека: docs/superpowers/specs/2026-10-12-keycloak-telegram-design.md
//
//   node --test tools/keycloak-realm.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ownIssuer } from './keycloak-token.mjs';

const realm = JSON.parse(readFileSync(new URL('../keycloak/selfin-realm.json', import.meta.url), 'utf8'));
const telegram = realm.identityProviders?.find((p) => p.alias === 'telegram');

test('реалм selfin, вход через Telegram включён', () => {
  assert.equal(realm.realm, 'selfin');
  assert.equal(telegram?.enabled, true);
  assert.equal(telegram.providerId, 'oidc');
});

test('Telegram — код и PKCE S256, секрет по Basic, подпись по ключам Telegram, без userinfo', () => {
  const c = telegram.config;
  assert.equal(c.issuer, 'https://oauth.telegram.org');
  assert.equal(c.pkceEnabled, 'true');
  assert.equal(c.pkceMethod, 'S256');
  assert.equal(c.clientAuthMethod, 'client_secret_basic');
  assert.equal(c.validateSignature, 'true');
  assert.equal(c.useJwksUrl, 'true');
  // У Telegram нет отдельного userinfo — данные человека только в ID token.
  assert.equal(c.disableUserInfo, 'true');
});

test('номер телефона не просим: только openid и profile', () => {
  assert.deepEqual(telegram.config.defaultScope.split(' ').sort(), ['openid', 'profile']);
});

test('ключи бота — из окружения: в файле только подстановки', () => {
  assert.equal(telegram.config.clientId, '${TELEGRAM_CLIENT_ID}');
  assert.equal(telegram.config.clientSecret, '${TELEGRAM_CLIENT_SECRET}');
});

test('первый вход не просит заполнить профиль: почта, имя и фамилия необязательны', () => {
  const component = realm.components?.['org.keycloak.userprofile.UserProfileProvider']?.[0];
  const config = JSON.parse(component.config['kc.user.profile.config'][0]);
  for (const name of ['email', 'firstName', 'lastName']) {
    const attribute = config.attributes.find((a) => a.name === name);
    assert.ok(attribute, `атрибут ${name} есть`);
    assert.equal(attribute.required, undefined, `${name} не обязателен: у Telegram нет почты, фамилии часто нет`);
  }
});

// ANO-195: наш сервер входит в Keycloak конфиденциальным клиентом selfin — вариант Б, сервер держит токены.
// Спека: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md
const selfin = realm.clients?.find((c) => c.clientId === 'selfin');

test('клиент selfin — вход через наш сервер: конфиденциальный, только код с PKCE S256', () => {
  assert.equal(selfin?.enabled, true);
  assert.equal(selfin.publicClient, false);
  assert.equal(selfin.standardFlowEnabled, true);
  assert.equal(selfin.implicitFlowEnabled, false);
  assert.equal(selfin.directAccessGrantsEnabled, false, 'пароль в обход Telegram — нет');
  assert.equal(selfin.serviceAccountsEnabled, false, 'сервисной учётной записи — нет');
  assert.equal(selfin.attributes?.['pkce.code.challenge.method'], 'S256');
});

test('секрет клиента selfin — из окружения: в файле только подстановка', () => {
  assert.equal(selfin.secret, '${SELFIN_CLIENT_SECRET}');
});

// ANO-152: «Вы вошли как» в «Настройках» — имя и ник из области profile; почты и ролей серверу не нужно.
test('в токенах клиента selfin — sub, имя и ник: области basic и profile, без почты и ролей', () => {
  assert.deepEqual(selfin.defaultClientScopes, ['basic', 'profile']);
  assert.deepEqual(selfin.optionalClientScopes ?? [], []);
  assert.equal(selfin.fullScopeAllowed, false);
});

// ANO-197: адрес сервера — подстановкой из окружения при импорте реалма; без переменной — адрес стенда.
const SERVER = '${SELFIN_PUBLIC_URL:http://127.0.0.1:5174}';
const STAND = /^http:\/\/(127\.0\.0\.1|localhost):517[34]/;

test('адреса возврата — экран разработки и стенда и адрес сервера из окружения, буква в букву', () => {
  assert.ok(selfin.redirectUris.includes(`${SERVER}/login/oauth2/code/keycloak`), 'адрес сервера — из SELFIN_PUBLIC_URL');
  for (const uri of selfin.redirectUris.filter((u) => !u.startsWith(SERVER))) {
    assert.match(uri, new RegExp(`${STAND.source}/login/oauth2/code/keycloak$`));
  }
});

// ANO-152: выход завершается в Keycloak, и он возвращает на наш экран входа — только на эти адреса.
test('адреса после выхода — экран входа разработки и стенда и адрес сервера из окружения, буква в букву', () => {
  const uris = selfin.attributes?.['post.logout.redirect.uris']?.split('##') ?? [];
  assert.ok(uris.includes(`${SERVER}/login`), 'адрес сервера — из SELFIN_PUBLIC_URL');
  for (const uri of uris.filter((u) => !u.startsWith(SERVER))) {
    assert.match(uri, new RegExp(`${STAND.source}/login$`));
  }
});

// ANO-152, решение владельца 15.10: вход держится 30 дней. Сеанс Keycloak нужен, когда наш сеанс потерян —
// после перезапуска сервера: пока он жив, «Войти» возвращает без подтверждения в Telegram.
test('сеанс Keycloak — 30 дней без действий и не дольше 30 дней', () => {
  const thirtyDays = 30 * 24 * 60 * 60;
  assert.equal(realm.ssoSessionIdleTimeout, thirtyDays);
  assert.equal(realm.ssoSessionMaxLifespan, thirtyDays);
});

test('клиента скриптов в файле нет: файл идёт на сервер, а там он открыл бы API в обход Telegram', () => {
  assert.deepEqual(realm.clients.map((c) => c.clientId), ['selfin']);
});

// Тестовый человек с паролем из репозитория и клиент скриптов (tools/keycloak-token.mjs) открыли бы серверу вход
// в обход Telegram (ревью Codex на #171). Их заводят только в своём Keycloak — тот, чей издатель на этой машине:
// у сервера издатель — его адрес, даже через туннель к консоли (замер 18.10).
test('тестового человека заводят только в своём Keycloak: издатель на этой машине, а не сервер', () => {
  for (const own of ['http://127.0.0.1:8180/realms/selfin', 'http://localhost:8199/realms/selfin',
    'https://localhost:8443/auth/realms/selfin', 'http://[::1]:8180/realms/selfin']) assert.ok(ownIssuer(own), own);
  for (const server of ['https://selfin.duckdns.org/auth/realms/selfin', 'https://localhost.example.com/auth/realms/selfin',
    'https://127.0.0.1.nip.io/auth/realms/selfin']) assert.ok(!ownIssuer(server), server);
});

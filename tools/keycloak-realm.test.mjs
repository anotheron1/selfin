// Реалм selfin как код (ANO-143): вход через Telegram настроен так, как решено, и ключей бота в файле нет.
// Спека: docs/superpowers/specs/2026-10-12-keycloak-telegram-design.md
//
//   node --test tools/keycloak-realm.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

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

test('в токенах клиента selfin только sub: область basic, без ролей', () => {
  assert.deepEqual(selfin.defaultClientScopes, ['basic']);
  assert.deepEqual(selfin.optionalClientScopes ?? [], []);
  assert.equal(selfin.fullScopeAllowed, false);
});

test('адреса возврата — экран разработки и стенда, буква в букву', () => {
  assert.ok(selfin.redirectUris.length > 0);
  for (const uri of selfin.redirectUris) {
    assert.match(uri, /^http:\/\/(127\.0\.0\.1|localhost):517[34]\/login\/oauth2\/code\/keycloak$/);
  }
});

test('клиента скриптов в файле нет: файл идёт на сервер, а там он открыл бы API в обход Telegram', () => {
  assert.deepEqual(realm.clients.map((c) => c.clientId), ['selfin']);
});

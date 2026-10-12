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

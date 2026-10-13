#!/usr/bin/env node
// Токен для скриптов стенда и CI (ANO-195): сервер принимает Bearer с аудиторией selfin.
// Спека: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md
//
//   node tools/keycloak-token.mjs [<адрес Keycloak>]   по умолчанию http://127.0.0.1:8180; печатает access token
//   SELFIN_TOKEN=$(node tools/keycloak-token.mjs) node tools/ano50-invariants.mjs
//
// Заводит в реалме selfin то, чего нет в keycloak/selfin-realm.json и не должно быть на сервере: клиента
// скриптов selfin-scripts — сервисная учётная запись, аудитория selfin, токен на час — и тестового
// человека с паролем — проверять вход без Telegram (tools/keycloak-check.mjs). Тем же частичным импортом
// догоняет клиентов из файла реалма: стенд, заведённый до них, получает их без пересоздания и не теряет
// вошедших людей. Импорт не перезаписывает: чего нет — заводит, что есть — не трогает.
//
// Админ Keycloak — admin / KC_ADMIN_PASSWORD (без него — admin), секрет клиента selfin — SELFIN_CLIENT_SECRET
// (без него — selfin-dev-secret), как в docker-compose.test.yml.

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** Клиент скриптов и тестовый человек — только стенд и CI: на сервере они открыли бы API в обход Telegram. */
export const SCRIPTS = { clientId: 'selfin-scripts', secret: 'selfin-scripts-dev' };
export const TESTER = { username: 'tester', password: 'tester' };

const form = (fields) => ({ method: 'POST', body: new URLSearchParams(fields) });

async function json(response, what) {
  if (!response.ok) throw new Error(`${what}: ${response.status} ${await response.text()}`);
  return response.json();
}

/** Заводит недостающее в реалме и выдаёт токен скриптов. */
export async function scriptsToken(keycloak = 'http://127.0.0.1:8180') {
  const kc = keycloak.replace(/\/$/, '');
  const admin = await json(await fetch(`${kc}/realms/master/protocol/openid-connect/token`, form({
    grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: process.env.KC_ADMIN_PASSWORD || 'admin',
  })), 'вход администратора Keycloak');

  const secret = process.env.SELFIN_CLIENT_SECRET || 'selfin-dev-secret';
  const realmClients = JSON.parse(readFileSync(new URL('../keycloak/selfin-realm.json', import.meta.url), 'utf8'))
    .clients.map((client) => ({ ...client, secret: client.secret === '${SELFIN_CLIENT_SECRET}' ? secret : client.secret }));

  await json(await fetch(`${kc}/admin/realms/selfin/partialImport`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${admin.access_token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ifResourceExists: 'SKIP',
      clients: [...realmClients, {
        clientId: SCRIPTS.clientId, secret: SCRIPTS.secret, enabled: true, publicClient: false,
        serviceAccountsEnabled: true, standardFlowEnabled: false, directAccessGrantsEnabled: false,
        fullScopeAllowed: false, defaultClientScopes: ['basic'],
        attributes: { 'access.token.lifespan': '3600' },
        protocolMappers: [{
          name: 'аудитория selfin', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper',
          config: { 'included.custom.audience': 'selfin', 'access.token.claim': 'true', 'id.token.claim': 'false' },
        }],
      }],
      users: [{
        username: TESTER.username, enabled: true,
        credentials: [{ type: 'password', value: TESTER.password, temporary: false }],
      }, {
        // Сервисную учётную запись клиента частичный импорт сам не заводит — без неё client credentials
        // отвечает 401 «service account … does not exist».
        username: `service-account-${SCRIPTS.clientId}`, enabled: true, serviceAccountClientId: SCRIPTS.clientId,
      }],
    }),
  }), 'частичный импорт в реалм selfin');

  const token = await json(await fetch(`${kc}/realms/selfin/protocol/openid-connect/token`, form({
    grant_type: 'client_credentials', client_id: SCRIPTS.clientId, client_secret: SCRIPTS.secret,
  })), 'токен скриптов');
  return token.access_token;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(await scriptsToken(process.argv[2]));
}

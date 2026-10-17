#!/usr/bin/env node
// Токен для скриптов стенда и CI (ANO-195): сервер принимает Bearer с аудиторией selfin.
// Спеки: docs/superpowers/specs/2026-10-13-server-knows-owner-design.md, 2026-10-14-row-level-security-design.md
//
//   node tools/keycloak-token.mjs [<адрес Keycloak>]         по умолчанию http://127.0.0.1:8180; печатает access token
//   node tools/keycloak-token.mjs [<адрес Keycloak>] --sub   печатает sub тестового человека — для SELFIN_OWNER_SUB
//   SELFIN_TOKEN=$(node tools/keycloak-token.mjs) node tools/ano50-invariants.mjs
//
// Скрипты ходят от имени тестового человека с паролем: на стенде и в CI данные принадлежат ему
// (SELFIN_OWNER_SUB — его sub, решение владельца 14.10). С RLS (ANO-144) каждый человек видит только своё —
// скрипты и вход формой видят одни и те же данные, только если это один человек.
//
// Заводит в реалме selfin то, чего нет в keycloak/selfin-realm.json и не должно быть на сервере: тестового
// человека и клиента скриптов selfin-scripts — вход паролем, аудитория selfin, токен на час. Тем же частичным
// импортом догоняет клиентов из файла реалма: стенд, заведённый до них, получает их без пересоздания и не
// теряет вошедших людей. Чего нет — заводит, что есть — не трогает; клиента скриптов — перезаписывает, чтобы
// его настройка доходила и до старых стендов.
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

/** Токен администратора Keycloak — из реалма master. */
export async function adminToken(keycloak = 'http://127.0.0.1:8180') {
  const admin = await json(await fetch(`${keycloak.replace(/\/$/, '')}/realms/master/protocol/openid-connect/token`, form({
    grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: process.env.KC_ADMIN_PASSWORD || 'admin',
  })), 'вход администратора Keycloak');
  return admin.access_token;
}

const partialImport = async (kc, admin, ifResourceExists, resources) => json(await fetch(`${kc}/admin/realms/selfin/partialImport`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ ifResourceExists, ...resources }),
}), `частичный импорт в реалм selfin (${ifResourceExists})`);

/** Клиент скриптов — перезаписывается, чтобы его настройка доходила и до старых стендов. Тестового человека не заводит. */
export async function ensureScriptsClient(keycloak, admin) {
  await partialImport(keycloak.replace(/\/$/, ''), admin, 'OVERWRITE', {
    clients: [{
      clientId: SCRIPTS.clientId, secret: SCRIPTS.secret, enabled: true, publicClient: false,
      serviceAccountsEnabled: false, standardFlowEnabled: false, directAccessGrantsEnabled: true,
      fullScopeAllowed: false, defaultClientScopes: ['basic'],
      attributes: { 'access.token.lifespan': '3600' },
      protocolMappers: [{
        name: 'аудитория selfin', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper',
        config: { 'included.custom.audience': 'selfin', 'access.token.claim': 'true', 'id.token.claim': 'false' },
      }],
    }],
  });
}

/** Заводит недостающее в реалме и выдаёт токен скриптов — токен тестового человека. */
export async function scriptsToken(keycloak = 'http://127.0.0.1:8180') {
  const kc = keycloak.replace(/\/$/, '');
  const admin = await adminToken(kc);

  const secret = process.env.SELFIN_CLIENT_SECRET || 'selfin-dev-secret';
  const realmClients = JSON.parse(readFileSync(new URL('../keycloak/selfin-realm.json', import.meta.url), 'utf8'))
    .clients.map((client) => ({ ...client, secret: client.secret === '${SELFIN_CLIENT_SECRET}' ? secret : client.secret }));

  await partialImport(kc, admin, 'SKIP', {
    clients: realmClients,
    users: [{
      username: TESTER.username, enabled: true,
      credentials: [{ type: 'password', value: TESTER.password, temporary: false }],
    }],
  });
  await ensureScriptsClient(kc, admin);

  const token = await json(await fetch(`${kc}/realms/selfin/protocol/openid-connect/token`, form({
    grant_type: 'password', client_id: SCRIPTS.clientId, client_secret: SCRIPTS.secret,
    username: TESTER.username, password: TESTER.password,
  })), 'токен скриптов');
  return token.access_token;
}

/** sub из токена — без проверки подписи: токен только что выдал свой Keycloak. */
export const subOf = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url')).sub;

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const token = await scriptsToken(args.find((a) => !a.startsWith('--')));
  console.log(args.includes('--sub') ? subOf(token) : token);
}

#!/usr/bin/env node
// Временный второй человек для проверок на стенде и сервере (спека 2026-10-17-stand-tools-design.md).
//
//   node tools/second-person.mjs [<имя>]   заводит человека (по умолчанию tester2) с новым случайным паролем и печатает
//                                          его токен Bearer; первый запрос с токеном заводит его со стартовым набором
//
// Адреса — переменными, по умолчанию стенд: SELFIN_API, SELFIN_KC, SELFIN_KC_ADMIN, SELFIN_PSQL — таблица в спеке.
// Админ Keycloak — admin / KC_ADMIN_PASSWORD (без него — admin), как в keycloak-token.mjs.
// Убрать человека — node tools/delete-person.mjs --username <имя>.

import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { adminToken, ensureScriptsClient, SCRIPTS } from './keycloak-token.mjs';

const trim = (url) => url.replace(/\/$/, '');
export const API = trim(process.env.SELFIN_API || 'http://127.0.0.1:8081/api/v1');
export const KC = trim(process.env.SELFIN_KC || 'http://127.0.0.1:8180');
export const KC_ADMIN = trim(process.env.SELFIN_KC_ADMIN || KC);
const PSQL = (process.env.SELFIN_PSQL || 'docker exec -i selfin-test-db psql -U admin -d selfin').split(' ');

/** SQL ролью владельца таблиц — строки ответа. Запрос идёт через stdin: так он доходит и через ssh. */
export function sql(query) {
  const [command, ...args] = PSQL;
  return execFileSync(command, [...args, '-v', 'ON_ERROR_STOP=1', '-qAt'], { input: query, encoding: 'utf8' })
    .trim().split('\n').filter(Boolean);
}

/** Вызов Admin API реалма selfin. */
export const adminCall = (admin, method, path, body) => fetch(`${KC_ADMIN}/admin/realms/selfin${path}`, {
  method, headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
  body: body && JSON.stringify(body),
});

/** Запрос к API от имени владельца токена: статус и текст ответа. */
export async function api(token, method, path, body) {
  const response = await fetch(API + path, {
    method, body,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
  });
  return { status: response.status, text: await response.text() };
}

/** Учётная запись Keycloak по имени — или undefined. */
export async function findUser(admin, username) {
  const response = await adminCall(admin, 'GET', `/users?username=${encodeURIComponent(username)}&exact=true`);
  if (!response.ok) throw new Error(`поиск ${username} в Keycloak: ${response.status} ${await response.text()}`);
  return (await response.json())[0];
}

async function ok(response, what) {
  if (!response.ok) throw new Error(`${what}: ${response.status} ${await response.text()}`);
  return response;
}

/**
 * Временный человек: заводит его или, если он уже есть, ставит новый пароль — случайный, только на этот запуск.
 * Токен — через клиент скриптов; клиент заводится сам, тестового человека при этом нет.
 */
export async function secondPerson(username = 'tester2') {
  const admin = await adminToken(KC_ADMIN);
  await ensureScriptsClient(KC_ADMIN, admin);
  const credentials = [{ type: 'password', value: randomUUID(), temporary: false }];
  const existing = await findUser(admin, username);
  if (existing) {
    await ok(await adminCall(admin, 'PUT', `/users/${existing.id}/reset-password`, credentials[0]), `пароль ${username}`);
  } else {
    await ok(await adminCall(admin, 'POST', '/users', { username, enabled: true, credentials }), `учётная запись ${username}`);
  }
  const token = await ok(await fetch(`${KC}/realms/selfin/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password', client_id: SCRIPTS.clientId, client_secret: SCRIPTS.secret,
      username, password: credentials[0].value,
    }),
  }), `токен ${username}`);
  return (await token.json()).access_token;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(await secondPerson(process.argv[2]));
}

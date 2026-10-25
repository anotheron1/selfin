#!/usr/bin/env node
// Ссылка «только в Keycloak» для дня переезда (шаг 7 плана спринта 6, решение владельца 24.10): вход клиента selfin
// прямо в Keycloak, мимо нашего сервера. Владелец открывает её и подтверждает вход в Telegram — Keycloak заводит его
// учётную запись и возвращает на сайт. Сайт этот вход не начинал, не принимает его и строк не заводит; дальше номер
// владельца — в SELFIN_OWNER_SUB, и обычный вход связывает его с историей. Консоль учётной записи для этого не нужна:
// снаружи она закрыта, а туннель к ней не годится (шаг 3).
//
//   node tools/owner-link.mjs https://<адрес сервера>
//
// Секретов в ссылке нет: код возврата без секрета клиента и проверочного кода PKCE ни на что не обменять.
// Спека: docs/superpowers/specs/2026-10-25-owner-link-rehearsal-design.md

import { createHash, randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';

/** Вход клиента selfin в Keycloak под /auth публичного адреса — сразу к Telegram, с PKCE S256, как у нашего сервера. */
export function ownerLink(publicUrl) {
  const base = publicUrl.replace(/\/$/, '');
  const link = new URL(`${base}/auth/realms/selfin/protocol/openid-connect/auth`);
  link.search = new URLSearchParams({
    client_id: 'selfin',
    redirect_uri: `${base}/login/oauth2/code/keycloak`,
    response_type: 'code',
    scope: 'openid',
    state: randomBytes(16).toString('hex'),
    code_challenge: createHash('sha256').update(randomBytes(32).toString('base64url')).digest('base64url'),
    code_challenge_method: 'S256',
    kc_idp_hint: 'telegram',
  }).toString();
  return link.href;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const address = process.argv[2] ?? '';
  if (!address.startsWith('https://')) {
    console.error('node tools/owner-link.mjs https://<адрес сервера> — тот же, что SELFIN_PUBLIC_URL');
    process.exit(2);
  }
  console.log(ownerLink(address));
}

// Ссылка «только в Keycloak» для дня переезда (шаг 7, решение владельца 24.10): вход клиента selfin прямо в Keycloak.
// Спека: docs/superpowers/specs/2026-10-25-owner-link-rehearsal-design.md
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ownerLink } from './owner-link.mjs';

const TOOL = fileURLToPath(new URL('./owner-link.mjs', import.meta.url));

test('ссылка — на вход реалма selfin под /auth нашего адреса, клиент selfin, возврат — на наш сайт', () => {
  const link = new URL(ownerLink('https://selfin.example/'));
  assert.equal(link.origin + link.pathname, 'https://selfin.example/auth/realms/selfin/protocol/openid-connect/auth');
  const params = link.searchParams;
  assert.equal(params.get('client_id'), 'selfin');
  assert.equal(params.get('redirect_uri'), 'https://selfin.example/login/oauth2/code/keycloak');
  assert.equal(params.get('response_type'), 'code');
  assert.equal(params.get('scope'), 'openid');
});

test('сразу к Telegram и с PKCE S256: клиент selfin без проверочного кода вход не начнёт', () => {
  const params = new URL(ownerLink('https://selfin.example')).searchParams;
  assert.equal(params.get('kc_idp_hint'), 'telegram');
  assert.equal(params.get('code_challenge_method'), 'S256');
  assert.match(params.get('code_challenge'), /^[A-Za-z0-9_-]{43}$/);
});

test('state и проверочный код — свои на каждую ссылку', () => {
  const [a, b] = [ownerLink('https://selfin.example'), ownerLink('https://selfin.example')].map((l) => new URL(l).searchParams);
  assert.notEqual(a.get('state'), b.get('state'));
  assert.notEqual(a.get('code_challenge'), b.get('code_challenge'));
});

test('адрес — только https: без него Keycloak вернёт на адрес, которого нет среди разрешённых', () => {
  const run = spawnSync(process.execPath, [TOOL, 'http://selfin.example'], { encoding: 'utf8' });
  assert.equal(run.status, 2);
  assert.match(run.stderr, /https:\/\//);
  const ok = spawnSync(process.execPath, [TOOL, 'https://selfin.example'], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout.trim(), /^https:\/\/selfin\.example\/auth\/realms\/selfin\/protocol\/openid-connect\/auth\?/);
});

// Кнопка «Выкатить на сервер» (ANO-246): ветка release — только вперёд, к коммиту из main, когда опубликованы оба
// образа; потом кнопка ждёт, пока сервер ответит этим коммитом. git, реестр и сервер — подделки: тест не ходит в сеть.
// Спека: docs/superpowers/specs/2026-10-27-release-button-design.md
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { release } from './release.mjs';

const TOOL = fileURLToPath(new URL('./release.mjs', import.meta.url));
const OLD = 'a'.repeat(40);
const NEW = 'b'.repeat(40);
const OTHER = 'c'.repeat(40);

/**
 * Подделка мира кнопки. `ancestors` — пары «предок потомок»; `release` — где ветка сейчас (null — её нет);
 * `published` — какие образы есть; `versions` — чем отвечает сервер на каждый вопрос, последний ответ повторяется.
 */
function world({ head = NEW, ancestors = [[OLD, NEW], [NEW, NEW], [OLD, OLD]], inMain = [OLD, NEW], release: at = OLD,
  published = (ref) => ref.endsWith(':sha-bbbbbbb'), versions = [NEW] } = {}) {
  const calls = [];
  const sleeps = [];
  const lines = [];
  const git = (args) => {
    calls.push(args.join(' '));
    const [cmd, ...rest] = args;
    if (cmd === 'rev-parse') {
      const ref = rest.at(-1);
      if (ref === 'refs/remotes/origin/release') return at ? { ok: true, out: `${at}\n` } : { ok: false, out: '' };
      const name = ref.replace(/\^\{commit\}$/, '');
      if (name === 'origin/main') return { ok: true, out: `${head}\n` };
      return [OLD, NEW, OTHER].includes(name) ? { ok: true, out: `${name}\n` } : { ok: false, out: '' };
    }
    if (cmd === 'merge-base') {
      const [, a, b] = rest;
      if (b === 'origin/main') return { ok: inMain.includes(a), out: '' };
      return { ok: ancestors.some(([x, y]) => x === a && y === b), out: '' };
    }
    if (cmd === 'push') return { ok: true, out: '' };
    throw new Error(`нежданный git ${args.join(' ')}`);
  };
  let asked = 0;
  const serverVersion = async () => versions[Math.min(asked++, versions.length - 1)];
  const sleep = async (ms) => { sleeps.push(ms); };
  const log = (line) => lines.push(line);
  const pushes = () => calls.filter((c) => c.startsWith('push '));
  return { git, published, serverVersion, sleep, log, calls, sleeps, lines, pushes };
}

const run = (w, commit = '') => release({ commit, git: w.git, published: w.published, serverVersion: w.serverVersion,
  sleep: w.sleep, log: w.log, waitMs: 60_000, stepMs: 15_000 });

test('коммит не из main — отказ, release не двигается', async () => {
  const w = world({ inMain: [OLD] });
  await assert.rejects(run(w, NEW), /не из main/);
  assert.deepEqual(w.pushes(), []);
});

test('release уже дальше выбранного коммита — назад кнопка не ходит', async () => {
  const w = world({ release: NEW, ancestors: [[OLD, NEW]] });
  await assert.rejects(run(w, OLD), /назад/);
  assert.deepEqual(w.pushes(), []);
});

test('release на соседней ветке истории, не предок коммита — тоже отказ', async () => {
  const w = world({ release: OTHER, ancestors: [[OLD, NEW]], inMain: [OLD, NEW, OTHER] });
  await assert.rejects(run(w, NEW), /назад/);
  assert.deepEqual(w.pushes(), []);
});

test('нет хоть одного образа — «подожди публикации», release не двигается', async () => {
  const w = world({ published: (ref) => ref === 'ghcr.io/anotheron1/selfin-backend:sha-bbbbbbb' });
  await assert.rejects(run(w), /selfin-frontend:sha-bbbbbbb.*подожди публикации/s);
  assert.deepEqual(w.pushes(), []);
});

test('оба образа есть — release вперёд на коммит, кнопка ждёт, пока сервер ответит им', async () => {
  const w = world({ versions: [OLD, OLD, NEW] });
  assert.equal(await run(w), NEW);
  assert.deepEqual(w.pushes(), [`push origin ${NEW}:refs/heads/release`]);
  assert.deepEqual(w.sleeps, [15_000, 15_000]);
  assert.match(w.lines.at(-1), /сервер на bbbbbbb/);
});

test('образы проверяются по короткому коммиту — тег публикации sha-<7 знаков>', async () => {
  const asked = [];
  const w = world();
  w.published = (ref) => { asked.push(ref); return true; };
  await run(w);
  assert.deepEqual(asked, ['ghcr.io/anotheron1/selfin-backend:sha-bbbbbbb', 'ghcr.io/anotheron1/selfin-frontend:sha-bbbbbbb']);
});

test('ветки release ещё нет — первый прогон её заводит', async () => {
  const w = world({ release: null });
  assert.equal(await run(w), NEW);
  assert.deepEqual(w.pushes(), [`push origin ${NEW}:refs/heads/release`]);
});

test('release уже на коммите — без push, только ждёт сервер', async () => {
  const w = world({ release: NEW });
  assert.equal(await run(w), NEW);
  assert.deepEqual(w.pushes(), []);
  assert.ok(w.lines.some((l) => /уже на bbbbbbb/.test(l)), w.lines.join('\n'));
});

test('без коммита — голова main', async () => {
  const w = world();
  await run(w);
  assert.ok(w.calls.includes('rev-parse --verify origin/main^{commit}'), w.calls.join('\n'));
});

test('сервер не перешёл за отведённое время — красный итог и где искать причину', async () => {
  const w = world({ versions: [OLD] });
  await assert.rejects(run(w), /не перешёл на bbbbbbb.*journalctl -u selfin-release/s);
  assert.deepEqual(w.pushes(), [`push origin ${NEW}:refs/heads/release`]);
  assert.equal(w.sleeps.length, 4, 'минута по 15 секунд');
});

test('сервер в перезапуске не отвечает — это ещё не провал, кнопка ждёт дальше', async () => {
  const w = world({ versions: [null, null, NEW] });
  assert.equal(await run(w), NEW);
});

test('без адреса сервера — отказ до любых действий с git', () => {
  const env = { ...process.env };
  delete env.SELFIN_PUBLIC_URL;
  // Вне репозитория: сломайся проверка адреса, git дальше «коммита нет» не уйдёт — настоящую release тест не тронет.
  const out = spawnSync(process.execPath, [TOOL], { env, encoding: 'utf8', cwd: mkdtempSync(join(tmpdir(), 'selfin-release-cli-')) });
  assert.notEqual(out.status, 0);
  assert.match(out.stderr, /SELFIN_PUBLIC_URL/);
});

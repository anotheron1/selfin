#!/usr/bin/env node
// Кнопка «Выкатить на сервер» (ANO-246, шаг 1 плана спринта 7): ветка release — то, что работает на сервере. Кнопка
// двигает её только вперёд, к коммиту из main, когда опубликованы оба образа, и ждёт, пока сервер ответит этим коммитом.
// Сервер забирает версию сам — deploy/release.sh по таймеру: ключей к серверу у GitHub нет.
// Спека: docs/superpowers/specs/2026-10-27-release-button-design.md
//
//   node tools/release.mjs [<коммит>]   коммит из main, по умолчанию — голова origin/main
//   SELFIN_PUBLIC_URL — адрес сервера: секрет репозитория, в открытом журнале прогона он замазан
//
// Запускает .github/workflows/release.yml. Ветки — дев: ветки PR, тест: main на стенде, прод: release на сервере.

import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

/** Образы, которые публикует publish-images.yml по вливанию в main: тег — sha-<7 знаков коммита>. */
export const IMAGES = ['ghcr.io/anotheron1/selfin-backend', 'ghcr.io/anotheron1/selfin-frontend'];

const MINUTE = 60_000;

/**
 * Перевести release на коммит и дождаться сервера.
 *
 * @param {object} p
 * @param {string} [p.commit] коммит или пусто — голова origin/main
 * @param {(args: string[]) => {ok: boolean, out: string, err?: string}} p.git
 * @param {(ref: string) => boolean} p.published образ с этим тегом есть в реестре
 * @param {() => Promise<string|null>} p.serverVersion коммит, которым отвечает сервер; null — не отвечает
 * @param {(ms: number) => Promise<void>} p.sleep
 * @param {(line: string) => void} p.log
 * @returns {Promise<string>} коммит, на котором сервер
 * @throws {Error} отказ или сервер не перешёл — текстом для журнала прогона
 */
export async function release({ commit, git, published, serverVersion, sleep, log, waitMs = 15 * MINUTE, stepMs = 15_000 }) {
  const resolved = git(['rev-parse', '--verify', `${commit || 'origin/main'}^{commit}`]);
  if (!resolved.ok) throw new Error(`коммита «${commit}» нет`);
  const target = resolved.out.trim();
  const short = target.slice(0, 7);
  if (!git(['merge-base', '--is-ancestor', target, 'origin/main']).ok) {
    throw new Error(`${short} не из main: на сервер уходит только влитое`);
  }
  const at = git(['rev-parse', '--verify', '--quiet', 'refs/remotes/origin/release']);
  const current = at.ok ? at.out.trim() : null;
  if (current && current !== target && !git(['merge-base', '--is-ancestor', current, target]).ok) {
    throw new Error(`release уже на ${current.slice(0, 7)}, а ${short} не впереди неё: назад кнопка не ходит — откат делает `
      + 'сервер сам, если новая версия не встала, или копия (deploy/RESTORE.md)');
  }
  // Сервер мог уйти вперёд release — первая кнопка или ручное обновление: сверяем и с ним (сухой прогон 27.10 пустил бы
  // d49d31e на сервер с 1cf6753, пока ветки release не было). Не отвечает — сверять не с чем, выкатка его и поднимет.
  const running = await serverVersion();
  if (running && running !== target && git(['merge-base', '--is-ancestor', target, running]).ok) {
    throw new Error(`сервер уже на ${running.slice(0, 7)}, а ${short} старше: назад кнопка не ходит — откат делает `
      + 'сервер сам, если новая версия не встала, или копия (deploy/RESTORE.md)');
  }
  // Пара latest разъезжается, когда одна из двух публикаций упала (ревью Codex на #171): проверяем оба тега коммита.
  for (const image of IMAGES) {
    if (!published(`${image}:sha-${short}`)) {
      throw new Error(`образа ${image}:sha-${short} ещё нет — подожди публикации (Publish images) и нажми снова`);
    }
  }
  if (current === target) {
    log(`release уже на ${short} — жду, пока сервер ответит им`);
  } else {
    const pushed = git(['push', 'origin', `${target}:refs/heads/release`]);
    if (!pushed.ok) throw new Error(`release не сдвинулась: ${(pushed.err || pushed.out || '').trim()}`);
    log(`release → ${short}; сервер заберёт её сам за пару минут`);
  }
  const answer = (version) => (version ? version.slice(0, 7) : 'ничем');
  for (let waited = 0; ; waited += stepMs) {
    const version = await serverVersion();
    if (version === target) {
      log(`сервер на ${short}`);
      return target;
    }
    if (waited >= waitMs) {
      throw new Error(`за ${waitMs / MINUTE} мин сервер не перешёл на ${short} (отвечает ${answer(version)}): причина — в журнале `
        + 'сервера, journalctl -u selfin-release');
    }
    if (waited % MINUTE === 0) log(`жду: сервер отвечает ${answer(version)}`);
    await sleep(stepMs);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const url = (process.env.SELFIN_PUBLIC_URL ?? '').replace(/\/+$/, '');
  if (!url) {
    console.error('нет SELFIN_PUBLIC_URL — адреса сервера: проверить выкатку нечем. Это секрет репозитория (Settings → Secrets → Actions)');
    process.exit(2);
  }
  const git = (args) => {
    const r = spawnSync('git', args, { encoding: 'utf8' });
    return { ok: r.status === 0, out: r.stdout ?? '', err: r.stderr ?? '' };
  };
  const published = (ref) => spawnSync('docker', ['manifest', 'inspect', ref], { stdio: 'ignore' }).status === 0;
  const serverVersion = async () => {
    try {
      const res = await fetch(`${url}/api/v1/version`, { signal: AbortSignal.timeout(10_000) });
      return res.ok ? (await res.json()).commit ?? null : null;
    } catch {
      return null;
    }
  };
  const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
  release({ commit: process.argv[2] ?? '', git, published, serverVersion, sleep, log: (line) => console.log(line) })
    .then(() => process.exit(0), (e) => {
      console.error(e.message);
      process.exit(1);
    });
}

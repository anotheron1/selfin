#!/usr/bin/env node
// Готовность PR к «ждёт ок» и вливанию — ворота 1 (ANO-194).
// Спека: docs/superpowers/specs/2026-09-26-pr-ready-gate-design.md
//
//   node tools/pr-ready.mjs <N>                        пять пунктов, выход 0 только при чистом итоге
//   node tools/pr-ready.mjs <N> --only codex-replies   только замечания Codex без ответа (джоба CI)
//   node tools/pr-ready.mjs <N> --at <время>           пункты Codex на момент в прошлом

import { spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const CODEX = 'chatgpt-codex-connector[bot]';

const time = (iso) => Date.parse(iso);
const notAfter = (at) => (item) => at === undefined || time(item.createdAt) <= time(at);

// Первая строка замечания без разметки значка: «**<sub><sub>![P2 Badge](…)</sub></sub>  Заголовок**».
function remarkTitle(body) {
  const first = body.split('\n').find((l) => l.trim() !== '') ?? '';
  const priority = first.match(/!\[(P\d) Badge\]/)?.[1] ?? null;
  const title = first
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/<\/?sub>/g, '')
    .replace(/\*\*/g, '')
    .trim();
  return { priority, title };
}

/**
 * Ветки обсуждения в строках кода, где последнее слово за Codex.
 * comments — [{ id, inReplyTo, user, createdAt, path, line, body }]; ответ в ветке ссылается на её первое сообщение.
 */
export function unansweredThreads(comments, { at } = {}) {
  const threads = new Map();
  for (const c of comments.filter(notAfter(at))) {
    const root = c.inReplyTo ?? c.id;
    threads.set(root, [...(threads.get(root) ?? []), c]);
  }
  return [...threads.values()]
    .map((thread) => thread.sort((a, b) => time(a.createdAt) - time(b.createdAt)).at(-1))
    .filter((last) => last.user === CODEX)
    .map((last) => ({ path: last.path, line: last.line, ...remarkTitle(last.body) }));
}

// Заголовок прогона «Ответов Codex» задан run-name: «Ответы Codex — <событие> <действие>». По нему виден прогон
// по opened — запись о голове на открытии PR. У прогонов CI заголовок — название PR.
const OPENED_RUN = /pull_request opened$/;
const HEAD_WORKFLOW = '.github/workflows/codex-replies.yml';

/**
 * История голов PR: прогоны по событию pull_request на ветке PR после его открытия, по порядку. Каждый прогон несёт
 * коммит головы на момент события; прогон «Ответов Codex» по opened помечен как открытие. Ветка, а не номер PR:
 * список PR у прогона GitHub считает в момент запроса, и у влитого PR он пуст. Прогоны до открытия — прошлый PR
 * с той же веткой, они не в счёт.
 * runs — [{ event, createdAt, headSha, headBranch, displayTitle }].
 */
export function headTransitions({ branch, repo, openedAt, runs }) {
  return runs
    // Ветка — вместе с репозиторием головы: у PR из форков ветка может называться одинаково (четырнадцатое ревью Codex).
    .filter((r) => r.event === 'pull_request' && r.headRepo === repo && r.headBranch === branch && time(r.createdAt) >= time(openedAt))
    .sort((a, b) => time(a.createdAt) - time(b.createdAt))
    // Открытие — только прогон «Ответов Codex»: заголовок прогона CI — название PR, и оно может кончаться теми же
    // словами (двенадцатое ревью Codex на #86).
    .map((r) => ({ sha: r.headSha, createdAt: r.createdAt, opening: r.workflow === HEAD_WORKFLOW && OPENED_RUN.test(r.displayTitle ?? '') }));
}

/** Голова PR в момент времени — по последнему прогону до него; до первого прогона неизвестна. */
export function headAt(transitions, iso) {
  return transitions.filter((t) => time(t.createdAt) <= time(iso)).at(-1)?.sha;
}

const REVIEW_REQUEST = /@codex\s+review/i;
const USAGE_LIMIT = /reached your Codex usage limits/i;
const NO_ISSUES = /Didn't find any major issues/i;
// Настоящее ревью Codex начинается с «### 💡 Codex Review». Ответ Codex в ветке GitHub заворачивает в ревью
// с пустым телом (#86, 14:50: «To use Codex here, create an environment») — это не вердикт.
const REVIEW_HEADER = /Codex Review/i;
const REVIEWED_COMMIT = /Reviewed commit:\**\s*`([0-9a-f]{7,40})`/i;
const hhmm = (iso) => new Date(iso).toISOString().slice(0, 16).replace('T', ' ');

/**
 * Что Codex сказал о голове PR. Каждый вердикт привязан к коммиту, а не ко времени (ревью Codex на #86, круги 2–9):
 * ревью — к своему коммиту, «Didn't find any major issues» — к номеру из текста (так Codex ответил на повторный запрос
 * по #85), 👍 на PR — к голове из прогона по opened. Голова просмотрена, если есть вердикт про её коммит.
 * 👍 на запросе «@codex review» не вердикт: к голове его надёжно не привязать, и Codex так не отвечает — на запрос
 * он даёт ревью или «замечаний нет». Любой другой комментарий Codex в ленте тоже не вердикт: в #20–#46 так приходило
 * «упёрся в лимит», а ревью не было.
 * transitions — история голов (headTransitions). Пока последний прогон не про нынешнюю голову, смена головы
 * не записана и вердикты к ней не привязать — ворота закрыты (шестое ревью Codex на #86).
 */
export function codexState({ headSha: nowHead, transitions, reviews, prReactions, issueComments, at }) {
  const byCodex = (x) => x.user === CODEX;
  const until = (iso) => at === undefined || time(iso) <= time(at);
  const chrono = (a, b) => time(a.at) - time(b.at);
  const short = (sha) => (sha ? sha.slice(0, 7) : '?');

  const history = transitions.filter((t) => until(t.createdAt));
  const current = history.at(-1)?.sha;
  // При --at голова — та, что была тогда, а не нынешняя (восьмое ревью Codex на #86).
  const headSha = at === undefined ? nowHead : current;
  if (current === undefined || current !== headSha) {
    return {
      ok: false,
      state: 'смена головы не записана',
      text: current
        ? `последний прогон по pull_request — на ${short(current)}, а голова ${short(headSha)}: дождаться прогона`
        : 'прогонов по pull_request на ветке PR нет: дождаться прогона',
    };
  }
  // Голова на открытии — только из записанного прогона по opened. Не первая записанная голова и не по времени:
  // без прогона по opened первым стал бы уже следующий пуш, в том числе быстрый (восьмое и девятое ревью Codex на #86).
  const openingHead = history.find((t) => t.opening)?.sha;

  const requests = issueComments
    .filter((c) => !byCodex(c) && REVIEW_REQUEST.test(c.body) && until(c.createdAt))
    .sort((a, b) => time(a.createdAt) - time(b.createdAt));
  const lastRequest = requests.at(-1);

  // Когда Codex ставил и снимал 👀, по истории не видно — в прошлом «смотрит» не определяется.
  if (at === undefined) {
    const watching = [...prReactions, ...(lastRequest?.reactions ?? [])].some((r) => byCodex(r) && r.content === 'eyes');
    if (watching) return { ok: false, state: 'смотрит', text: 'смотрит — 👀, вердикта ещё нет' };
  }

  const thumbsUp = (r) => byCodex(r) && r.content === '+1';
  const said = [
    ...reviews.filter((r) => byCodex(r) && REVIEW_HEADER.test(r.body ?? ''))
      .map((r) => ({ at: r.submittedAt, sha: r.commitId, what: 'ревью' })),
    // 👍 на самом PR Codex ставит при первом ревью — это вердикт по голове на открытии, когда бы он ни пришёл.
    ...prReactions.filter(thumbsUp).map((r) => ({ at: r.createdAt, sha: openingHead, what: '👍' })),
    // Без разобранного номера коммита «замечаний нет» не засчитывается: не узнать, какую голову смотрели
    // (третье ревью Codex на #86). Такой комментарий уходит в «прочитать».
    ...issueComments.filter((c) => byCodex(c) && NO_ISSUES.test(c.body) && REVIEWED_COMMIT.test(c.body))
      .map((c) => ({ at: c.createdAt, sha: c.body.match(REVIEWED_COMMIT)[1], what: 'замечаний нет' })),
  ].filter((v) => until(v.at)).sort(chrono);
  // Вердикт, который не к чему привязать, — не вердикт: 👍 на PR без записанной головы на открытии.
  const verdicts = said.filter((v) => v.sha !== undefined);
  const unbound = said.length - verdicts.length;

  const aboutHead = (v) => headSha.startsWith(v.sha);
  const seen = verdicts.filter(aboutHead).at(-1);
  if (seen) return { ok: true, state: 'видел', text: `${seen.what} ${hhmm(seen.at)} — голова ${short(headSha)} просмотрена` };

  const lastVerdict = verdicts.at(-1);
  const note = issueComments
    .filter((c) => byCodex(c) && until(c.createdAt) && (!lastVerdict || time(c.createdAt) > time(lastVerdict.at)))
    .map((c) => ({ at: c.createdAt, body: c.body }))
    .sort(chrono)
    .at(-1);
  if (note && USAGE_LIMIT.test(note.body)) {
    return { ok: false, state: 'лимит', text: `${hhmm(note.at)} упёрся в лимит — ревью не было` };
  }
  if (note) {
    return { ok: false, state: 'написал', text: `${hhmm(note.at)} написал в ленте, прочитать: ${note.body.split('\n')[0]}` };
  }
  if (lastRequest && headAt(history, lastRequest.createdAt) === headSha) {
    return { ok: false, state: 'запрошен', text: `запрос ${hhmm(lastRequest.createdAt)}, ответа нет` };
  }
  if (!lastVerdict) {
    const why = unbound ? '; 👍 на PR не к чему привязать — прогона «Ответов Codex» по opened нет: нужен @codex review' : '';
    return { ok: false, state: 'молчит', text: `молчит — ни ревью, ни 👍${why}` };
  }
  return {
    ok: false,
    state: 'не видел голову',
    text: `последний вердикт — ${lastVerdict.what} ${hhmm(lastVerdict.at)} про ${short(lastVerdict.sha)}, голова ${short(headSha)}: нужен @codex review`,
  };
}

// Linear: номер в ветке или заголовке закрывает задачу при вливании, в описании — только после закрывающего слова.
// После связующих слов задача связывается, но не закрывается. Списки слов — из документации интеграции.
const ID = String.raw`ANO-\d+`;
const ID_LIST = String.raw`${ID}(?:\s*(?:,|and|&)\s*${ID})*`;
const CLOSING = 'close[sd]?|closing|fix(?:e[sd])?|fixing|resolve[sd]?|resolving|complete[sd]?|completing|implement(?:s|ed|ing)?';
const CONTRIBUTING = 'ref|references|part of|related to|contributes to|towards|updates';

const idsIn = (text) => [...text.matchAll(new RegExp(String.raw`\b${ID}\b`, 'gi'))].map((m) => m[0].toUpperCase());
const idsAfter = (text, words) =>
  [...text.matchAll(new RegExp(String.raw`\b(?:${words})\b[\s:]*(${ID_LIST})`, 'gi'))].flatMap((m) => idsIn(m[1]));
const byNumber = (a, b) => Number(a.split('-')[1]) - Number(b.split('-')[1]);
const uniqueIds = (ids) => [...new Set(ids)].sort(byNumber);

export function linearLinks({ branch, title, body }) {
  const closes = uniqueIds([...idsIn(branch), ...idsIn(title), ...idsAfter(body, CLOSING)]);
  const links = uniqueIds(idsAfter(body, CONTRIBUTING)).filter((id) => !closes.includes(id));
  return { closes, links };
}

// «Закроет: ANO-1, ANO-2» или «Закроет: —». Linear эту строку не читает — её сверяет скрипт.
const DECLARATION = /^\s*(?:[-*]\s+)?(?:\*\*)?Закроет:(?:\*\*)?[ \t]*(.*)$/m;
const NOTHING = /^(?:—|–|-|ничего|нет)$/i;
const DECLARATION_HINT = 'номера через запятую или «—»';

export function declaredClosing(body) {
  const visible = body.replace(/<!--[\s\S]*?-->/g, '');
  const match = visible.match(DECLARATION);
  if (!match) return { error: `в описании нет строки «Закроет:» — ${DECLARATION_HINT}` };
  const value = match[1].trim();
  if (NOTHING.test(value)) return { ids: [] };
  const ids = idsIn(value);
  const rest = value.replace(new RegExp(ID, 'gi'), '').replace(/\s|,|;|\band\b|(?<![а-яё])и(?![а-яё])/gi, '');
  if (ids.length === 0 || rest !== '') return { error: `строка «Закроет: ${value}» не заполнена — ${DECLARATION_HINT}` };
  return { ids: uniqueIds(ids) };
}

export function linearState({ branch, title, body }) {
  const { closes, links } = linearLinks({ branch, title, body });
  const list = (ids) => (ids.length ? ids.join(', ') : 'ничего');
  const facts = `закроет ${list(closes)}` + (links.length ? `; свяжет без закрытия ${links.join(', ')}` : '');
  const declared = declaredClosing(body);
  if (declared.error) return { ok: false, text: `${facts}; ${declared.error}` };

  const extra = closes.filter((id) => !declared.ids.includes(id));
  const missing = declared.ids.filter((id) => !closes.includes(id));
  if (extra.length === 0 && missing.length === 0) return { ok: true, text: facts };
  const why = [
    extra.length && `не объявлено ${extra.join(', ')} — номер в ветке или заголовке закрывает задачу; связать без закрытия — «Part of» в описании`,
    missing.length && `объявлено, но не закроется ${missing.join(', ')} — номер в заголовок или «Fixes» в описание`,
  ].filter(Boolean);
  return { ok: false, text: [`${facts}; объявлено ${list(declared.ids)}`, ...why].join('; ') };
}

/**
 * Проверки, которые workflow на pull_request обязаны поставить на голову PR: по одной на джобу — имя джобы
 * (`name:` джобы, без него — её ключ) под именем workflow. Не только имена workflow: PR без джобы бэка
 * выглядел бы пришедшим CI (двенадцатое ревью Codex на #86).
 * files — [{ path, text }] из .github/workflows. Разбор под наш вид файлов: `name:` и `on:` с начала строки,
 * ключи джоб — с двух пробелов после `jobs:`, `name:` джобы — с четырёх.
 */
export function pullRequestChecks(files) {
  const unquote = (value) => value.replace(/^['"]|['"]$/g, '');
  const onPullRequest = (text) => {
    const on = text.match(/^on:(.*)$([\s\S]*?)(?=^\S|(?![\s\S]))/m);
    return Boolean(on) && (/\bpull_request\b/.test(on[1]) || /^\s+pull_request:/m.test(on[2]));
  };
  return files.filter(({ text }) => onPullRequest(text)).flatMap(({ path, text }) => {
    const workflow = unquote(text.match(/^name:\s*(.+?)\s*$/m)?.[1] ?? path);
    const jobs = text.slice(text.search(/^jobs:/m)).split(/^ {2}(?=[\w-]+:\s*$)/m).slice(1);
    return jobs.map((job) => ({ workflow, check: unquote(job.match(/^ {4}name:\s*(.+?)\s*$/m)?.[1] ?? job.match(/^[\w-]+/)[0]) }));
  });
}

/**
 * Проверки, которые обязаны прийти на голову PR, — из базы PR и из самого PR вместе. Только из PR — и PR, убравший
 * pull_request из CI или джобу бэка, перестал бы ждать проверку, которая его проверяет (одиннадцатое и двенадцатое
 * ревью Codex на #86). base и pr — файлы .github/workflows, как для pullRequestChecks.
 */
export function requiredChecks({ base, pr }) {
  const key = (c) => `${c.workflow}/${c.check}`;
  const all = new Map([...pullRequestChecks(base), ...pullRequestChecks(pr)].map((c) => [key(c), c]));
  return [...all.values()].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
}

/**
 * Проверки головы PR. Одно имя может прогоняться на разные события (джоба ответов Codex) — берётся последний прогон.
 * checks — [{ name, workflow, bucket, startedAt }], bucket из `gh pr checks`: pass, fail, pending, skipping, cancel.
 * expected — проверки [{ workflow, check }], которые обязаны прийти (requiredChecks): без них не запустившийся CI
 * или CI без джобы выглядели бы чистыми (первое и двенадцатое ревью Codex на #86).
 */
export function ciState(checks, expected = []) {
  const latest = new Map();
  for (const c of checks) {
    const key = `${c.workflow}/${c.name}`;
    if (!latest.has(key) || time(c.startedAt) > time(latest.get(key).startedAt)) latest.set(key, c);
  }
  const runs = [...latest.values()];
  const named = (bucket) => runs.filter((c) => [bucket].flat().includes(c.bucket)).map((c) => c.name);
  if (runs.length === 0) return { ok: false, text: 'проверок нет' };
  const failed = named(['fail', 'cancel']);
  if (failed.length) return { ok: false, text: `упали: ${failed.join('; ')}` };
  // У джобы с матрицей GitHub дописывает к имени проверки значения: «Тесты (node 20)» (тринадцатое ревью Codex на #86).
  const isJob = (c, e) => c.name === e.check || c.name.startsWith(`${e.check} (`);
  const missing = expected.filter((e) => !runs.some((c) => c.workflow === e.workflow && isJob(c, e)));
  if (missing.length) return { ok: false, text: `не пришли: ${missing.map((e) => `${e.workflow} — ${e.check}`).join('; ')}` };
  const running = named('pending');
  if (running.length) return { ok: false, text: `идут: ${running.join('; ')}` };
  const skipped = named('skipping').length;
  return { ok: true, text: `прошли ${named('pass').length}` + (skipped ? `, пропущены ${skipped}` : '') };
}

/** База PR и PR, которые стоят на его ветке. children — номера открытых PR с базой на ветке этого. */
export function baseState({ base, children }) {
  if (base !== 'main') {
    return { ok: false, text: `в стопке на ${base}: сначала влить нижний, потом перенацелить этот на main` };
  }
  if (children.length) {
    return { ok: false, text: `на ветке стоят ${children.map((n) => `#${n}`).join(', ')}: перед вливанием перенацелить их на main` };
  }
  return { ok: true, text: 'main, сверху никого' };
}

/** Итог по пунктам [{ title, ok, text }]: готово, только если чисты все. */
export function summary(items) {
  const ok = items.every((i) => i.ok);
  const width = Math.max(...items.map((i) => i.title.length));
  const lines = items.map((i) => `  ${i.title.padEnd(width)}  ${i.ok ? 'да ' : 'НЕТ'}  ${i.text}`);
  return { ok, text: [...lines, ok ? 'Итог: готово' : 'Итог: НЕ готово'].join('\n') };
}

// ---- Данные PR через gh. {owner}/{repo} gh подставляет из текущего репозитория. ----

function gh(args, { allowFail = false } = {}) {
  const run = spawnSync('gh', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (run.error) throw run.error;
  if (run.status !== 0 && !allowFail) throw new Error(`gh ${args.join(' ')}: ${run.stderr.trim()}`);
  return run.stdout;
}
const ghJson = (args) => JSON.parse(gh(args));
// `--jq '.[] | {…}'` печатает по объекту в строке, в том числе при --paginate.
const ghList = (path, jq) =>
  gh(['api', '--paginate', `${path}${path.includes('?') ? '&' : '?'}per_page=100`, '--jq', `.[] | ${jq}`])
    .split('\n').filter(Boolean).map((line) => JSON.parse(line));

const REPO = 'repos/{owner}/{repo}';

function reviewComments(n) {
  return ghList(`${REPO}/pulls/${n}/comments`,
    '{id, inReplyTo: .in_reply_to_id, user: .user.login, createdAt: .created_at, path, line: (.line // .original_line), body}');
}

function headHistory(pr) {
  const path = `${REPO}/actions/runs?branch=${encodeURIComponent(pr.headRefName)}&event=pull_request&per_page=100`;
  const runs = gh(['api', '--paginate', path, '--jq',
    '.workflow_runs[] | {workflow: .path, event, createdAt: .created_at, headSha: .head_sha, headBranch: .head_branch, headRepo: .head_repository.full_name, displayTitle: .display_title}'])
    .split('\n').filter(Boolean).map((line) => JSON.parse(line));
  const repo = `${pr.headRepositoryOwner.login}/${pr.headRepository.name}`;
  return headTransitions({ branch: pr.headRefName, repo, openedAt: pr.createdAt, runs });
}

function codexData(n, headSha) {
  const reactions = (path) => ghList(path, '{user: .user.login, content, createdAt: .created_at}');
  const issueComments = ghList(`${REPO}/issues/${n}/comments`, '{id, user: .user.login, createdAt: .created_at, body}')
    .map((c) => ({ ...c, reactions: REVIEW_REQUEST.test(c.body) ? reactions(`${REPO}/issues/comments/${c.id}/reactions`) : [] }));
  const reviews = ghList(`${REPO}/pulls/${n}/reviews`, '{user: .user.login, submittedAt: .submitted_at, commitId: .commit_id, body}')
    .filter((r) => r.submittedAt);
  return { reviews, prReactions: reactions(`${REPO}/issues/${n}/reactions`), issueComments, headSha };
}

// На PR GitHub гоняет workflow из merge-коммита PR, а не из рабочей копии: у PR, открытого до нового
// workflow, его прогонов нет и быть не должно. Для влитого PR merge-ссылки может не быть — тогда голова.
function expectedChecks(pr) {
  const files = (ref) => ghList(`${REPO}/contents/.github/workflows?ref=${encodeURIComponent(ref)}`, '{path, name}')
    .filter((f) => /\.ya?ml$/.test(f.name))
    .map((f) => ({
      path: f.name,
      text: Buffer.from(gh(['api', `${REPO}/contents/${f.path}?ref=${encodeURIComponent(ref)}`, '--jq', '.content']), 'base64').toString('utf8'),
    }));
  let own;
  try {
    own = files(`refs/pull/${pr.number}/merge`);
  } catch {
    own = files(pr.headRefOid);
  }
  return requiredChecks({ base: files(pr.baseRefOid), pr: own });
}

function ciChecks(n) {
  // gh pr checks выходит не с нулём, пока проверки идут или упали, — результат читается всё равно.
  const out = gh(['pr', 'checks', String(n), '--json', 'name,workflow,bucket,startedAt'], { allowFail: true }).trim();
  return out ? JSON.parse(out) : [];
}

function repliesItem(comments, at) {
  const open = unansweredThreads(comments, { at });
  const text = open.length === 0
    ? 'без ответа нет'
    : [`без ответа ${open.length}:`, ...open.map((r) => `      ${r.path}:${r.line} ${r.priority ? `${r.priority} ` : ''}${r.title}`)].join('\n');
  return { title: 'Замечания Codex', ok: open.length === 0, text };
}

const USAGE = 'использование: pr-ready.sh <номер PR> [--only codex-replies] [--at <время ISO>]';

function parseArgs(argv) {
  const [number, ...rest] = argv;
  if (!/^\d+$/.test(number ?? '')) throw new Error(USAGE);
  const opts = { number: Number(number) };
  for (let i = 0; i < rest.length; i++) {
    if (rest[i] === '--only' && rest[i + 1] === 'codex-replies') opts.only = rest[++i];
    else if (rest[i] === '--at' && !Number.isNaN(Date.parse(rest[i + 1]))) opts.at = new Date(rest[++i]).toISOString();
    else throw new Error(USAGE);
  }
  return opts;
}

function report(text) {
  console.log(text);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\`\`\`\n${text}\n\`\`\`\n`);
}

function main(argv) {
  const { number, only, at } = parseArgs(argv);
  const pr = ghJson(['pr', 'view', String(number), '--json', 'number,title,body,headRefName,headRepository,headRepositoryOwner,baseRefName,headRefOid,baseRefOid,createdAt']);
  const header = `PR #${pr.number} — ${pr.title}${at ? ` (на ${at})` : ''}`;
  const replies = repliesItem(reviewComments(number), at);

  if (only === 'codex-replies') {
    const s = summary([replies]);
    report(`${header}\n${s.text}`);
    return s.ok;
  }

  const codex = codexState({ ...codexData(number, pr.headRefOid), transitions: headHistory(pr), at });
  const children = gh(['pr', 'list', '--state', 'open', '--base', pr.headRefName, '--json', 'number', '--jq', '.[].number'])
    .split('\n').filter(Boolean).map(Number);
  const s = summary([
    at ? { title: 'CI', ok: true, text: 'при --at не проверяется' } : { title: 'CI', ...ciState(ciChecks(number), expectedChecks(pr)) },
    { title: 'Codex', ok: codex.ok, text: codex.text },
    replies,
    { title: 'База', ...baseState({ base: pr.baseRefName, children }) },
    { title: 'Linear', ...linearState({ branch: pr.headRefName, title: pr.title, body: pr.body }) },
  ]);
  report(`${header}\n${s.text}`);
  return s.ok;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main(process.argv.slice(2)) ? 0 : 1;
  } catch (e) {
    console.error(e.message);
    process.exitCode = 2;
  }
}

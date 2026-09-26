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

const REVIEW_REQUEST = /@codex\s+review/i;
const USAGE_LIMIT = /reached your Codex usage limits/i;
const NO_ISSUES = /Didn't find any major issues/i;
const REVIEWED_COMMIT = /Reviewed commit:\**\s*`([0-9a-f]{7,40})`/i;
const hhmm = (iso) => new Date(iso).toISOString().slice(0, 16).replace('T', ' ');

/**
 * Что Codex сказал о голове PR.
 * Вердикт — ревью, 👍 на PR, 👍 на запросе «@codex review» и комментарий «Didn't find any major issues» с номером
 * просмотренного коммита: так Codex ответил на повторный запрос без замечаний (#85). Любой другой комментарий Codex
 * в ленте вердиктом не считается: в #20–#46 так приходило «упёрся в лимит», а ревью не было.
 */
export function codexState({ headSha, headPushedAt, reviews, prReactions, issueComments, at }) {
  const byCodex = (x) => x.user === CODEX;
  const until = (iso) => at === undefined || time(iso) <= time(at);
  const chrono = (a, b) => time(a.at) - time(b.at);

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
  const verdicts = [
    ...reviews.filter(byCodex).map((r) => ({ at: r.submittedAt, commitId: r.commitId, what: 'ревью' })),
    ...prReactions.filter(thumbsUp).map((r) => ({ at: r.createdAt, what: '👍' })),
    ...requests.flatMap((c) => c.reactions ?? []).filter(thumbsUp).map((r) => ({ at: r.createdAt, what: '👍 на запрос' })),
    // Без разобранного номера коммита «замечаний нет» не засчитывается: не узнать, какую голову смотрели
    // (третье ревью Codex на #86). Такой комментарий уходит в «прочитать».
    ...issueComments.filter((c) => byCodex(c) && NO_ISSUES.test(c.body) && REVIEWED_COMMIT.test(c.body))
      .map((c) => ({ at: c.createdAt, commitPrefix: c.body.match(REVIEWED_COMMIT)[1], what: 'замечаний нет' })),
  ].filter((v) => until(v.at)).sort(chrono);

  // Вердикт с номером коммита засчитывается только по номеру: ревью прошлой головы может прийти уже после пуша
  // новой (ревью Codex на #86). По времени — только реакции, у которых номера нет.
  const sawHead = (v) => {
    if (v.commitId !== undefined) return v.commitId === headSha;
    if (v.commitPrefix !== undefined) return headSha.startsWith(v.commitPrefix);
    return time(v.at) >= time(headPushedAt);
  };
  const seen = verdicts.filter(sawHead).at(-1);
  if (seen) return { ok: true, state: 'видел', text: `${seen.what} ${hhmm(seen.at)} — голова ${headSha.slice(0, 7)} просмотрена` };

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
  if (lastRequest && time(lastRequest.createdAt) >= time(headPushedAt)) {
    return { ok: false, state: 'запрошен', text: `запрос ${hhmm(lastRequest.createdAt)}, ответа нет` };
  }
  if (!lastVerdict) return { ok: false, state: 'молчит', text: 'молчит — ни ревью, ни 👍' };
  return {
    ok: false,
    state: 'не видел голову',
    text: `последний вердикт — ${lastVerdict.what} ${hhmm(lastVerdict.at)}, голова запушена ${hhmm(headPushedAt)}: нужен @codex review`,
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
 * Workflow, которые запускаются на pull_request: их проверки обязаны прийти на голову PR.
 * files — [{ path, text }] из .github/workflows. Разбор под наш вид файлов: `name:` и `on:` с начала строки.
 */
export function pullRequestWorkflows(files) {
  const onPullRequest = (text) => {
    const on = text.match(/^on:(.*)$([\s\S]*?)(?=^\S|(?![\s\S]))/m);
    return Boolean(on) && (/\bpull_request\b/.test(on[1]) || /^\s+pull_request:/m.test(on[2]));
  };
  return files
    .filter(({ text }) => onPullRequest(text))
    .map(({ path, text }) => (text.match(/^name:\s*(.+?)\s*$/m)?.[1] ?? path).replace(/^['"]|['"]$/g, ''))
    .sort();
}

/**
 * Проверки головы PR. Одно имя может прогоняться на разные события (джоба ответов Codex) — берётся последний прогон.
 * checks — [{ name, workflow, bucket, startedAt }], bucket из `gh pr checks`: pass, fail, pending, skipping, cancel.
 * expected — workflow, чьи проверки обязаны прийти: без него не запустившийся CI выглядел бы чистым (ревью Codex на #86).
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
  const missing = expected.filter((w) => !runs.some((c) => c.workflow === w));
  if (missing.length) return { ok: false, text: `не запускались: ${missing.join(', ')}` };
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

// Время пуша головы — создание первого набора проверок на её коммит: его создаёт сам пуш.
function headPushedAt(sha) {
  const suites = gh(['api', `${REPO}/commits/${sha}/check-suites`, '--jq', '[.check_suites[].created_at] | min']).trim();
  if (suites && suites !== 'null') return { at: suites, source: 'набор проверок' };
  return { at: gh(['api', `${REPO}/commits/${sha}`, '--jq', '.commit.committer.date']).trim(), source: 'дата коммита' };
}

function codexData(n, headSha) {
  const reactions = (path) => ghList(path, '{user: .user.login, content, createdAt: .created_at}');
  const issueComments = ghList(`${REPO}/issues/${n}/comments`, '{id, user: .user.login, createdAt: .created_at, body}')
    .map((c) => ({ ...c, reactions: REVIEW_REQUEST.test(c.body) ? reactions(`${REPO}/issues/comments/${c.id}/reactions`) : [] }));
  const reviews = ghList(`${REPO}/pulls/${n}/reviews`, '{user: .user.login, submittedAt: .submitted_at, commitId: .commit_id}')
    .filter((r) => r.submittedAt);
  return { reviews, prReactions: reactions(`${REPO}/issues/${n}/reactions`), issueComments, headSha };
}

// На PR GitHub гоняет workflow из merge-коммита PR, а не из рабочей копии: у PR, открытого до нового
// workflow, его прогонов нет и быть не должно. Для влитого PR merge-ссылки может не быть — тогда голова.
function expectedWorkflows(n, headSha) {
  const files = (ref) => ghList(`${REPO}/contents/.github/workflows?ref=${encodeURIComponent(ref)}`, '{path, name}')
    .filter((f) => /\.ya?ml$/.test(f.name))
    .map((f) => ({
      path: f.name,
      text: Buffer.from(gh(['api', `${REPO}/contents/${f.path}?ref=${encodeURIComponent(ref)}`, '--jq', '.content']), 'base64').toString('utf8'),
    }));
  let list;
  try {
    list = files(`refs/pull/${n}/merge`);
  } catch {
    list = files(headSha);
  }
  return pullRequestWorkflows(list);
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
  const pr = ghJson(['pr', 'view', String(number), '--json', 'number,title,body,headRefName,baseRefName,headRefOid']);
  const header = `PR #${pr.number} — ${pr.title}${at ? ` (на ${at})` : ''}`;
  const replies = repliesItem(reviewComments(number), at);

  if (only === 'codex-replies') {
    const s = summary([replies]);
    report(`${header}\n${s.text}`);
    return s.ok;
  }

  const pushed = headPushedAt(pr.headRefOid);
  const codex = codexState({ ...codexData(number, pr.headRefOid), headPushedAt: pushed.at, at });
  const children = gh(['pr', 'list', '--state', 'open', '--base', pr.headRefName, '--json', 'number', '--jq', '.[].number'])
    .split('\n').filter(Boolean).map(Number);
  const s = summary([
    at ? { title: 'CI', ok: true, text: 'при --at не проверяется' } : { title: 'CI', ...ciState(ciChecks(number), expectedWorkflows(number, pr.headRefOid)) },
    { title: 'Codex', ok: codex.ok, text: codex.text + (pushed.source === 'дата коммита' ? ' (время пуша — по дате коммита)' : '') },
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

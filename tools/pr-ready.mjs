#!/usr/bin/env node
// Готовность PR к «ждёт ок» и вливанию — ворота 1 (ANO-194).
// Спека: docs/superpowers/specs/2026-09-26-pr-ready-gate-design.md
//
//   node tools/pr-ready.mjs <N>                        пять пунктов, выход 0 только при чистом итоге
//   node tools/pr-ready.mjs <N> --only codex-replies   только замечания Codex без ответа (джоба CI)
//   node tools/pr-ready.mjs <N> --at <время>           пункты Codex на момент в прошлом

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
const hhmm = (iso) => new Date(iso).toISOString().slice(0, 16).replace('T', ' ');

/**
 * Что Codex сказал о голове PR.
 * Вердикт — ревью, 👍 на PR, 👍 на запросе «@codex review». Комментарий Codex в ленте вердиктом не считается:
 * в #20–#46 так приходило «упёрся в лимит», а ревью не было.
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
  ].filter((v) => until(v.at)).sort(chrono);

  const seen = verdicts.filter((v) => v.commitId === headSha || time(v.at) >= time(headPushedAt)).at(-1);
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

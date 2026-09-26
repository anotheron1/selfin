// Тесты скрипта готовности PR (ANO-194, ворота 1).
// Спека: docs/superpowers/specs/2026-09-26-pr-ready-gate-design.md, раздел 7.
// Номера, логины и время — из публичной истории PR этого репозитория; текст замечаний выдуман.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CODEX, unansweredThreads, codexState } from './pr-ready.mjs';

const ME = 'anotheron1';
const remark = (title) =>
  `**<sub><sub>![P2 Badge](https://img.shields.io/badge/P2-yellow?style=flat)</sub></sub>  ${title}**\n\nПодробности.`;

// #53: замечание пришло в 14:24, PR влит в 17:52, ответ — через три дня.
const pr53 = [
  { id: 4083518428, inReplyTo: null, user: CODEX, createdAt: '2026-09-23T14:24:08Z',
    path: 'frontend/src/productRules.test.ts', line: 95, body: remark('Сторож не читает контроллеры') },
  { id: 4111108131, inReplyTo: 4083518428, user: ME, createdAt: '2026-09-26T10:36:05Z',
    path: 'frontend/src/productRules.test.ts', line: 95, body: 'Подтверждено.' },
];
const merged53 = '2026-09-23T17:52:57Z';

// #84: четыре замечания в двух ревью, на каждое ответ в ветке.
const pr84 = [
  { id: 4111153372, inReplyTo: null, user: CODEX, createdAt: '2026-09-26T10:53:32Z', path: 'a.md', line: 1, body: remark('Первое') },
  { id: 4111153376, inReplyTo: null, user: CODEX, createdAt: '2026-09-26T10:53:32Z', path: 'a.md', line: 2, body: remark('Второе') },
  { id: 4111153378, inReplyTo: null, user: CODEX, createdAt: '2026-09-26T10:53:32Z', path: 'a.md', line: 3, body: remark('Третье') },
  { id: 4111294438, inReplyTo: 4111153372, user: ME, createdAt: '2026-09-26T11:55:00Z', path: 'a.md', line: 1, body: 'Да.' },
  { id: 4111294502, inReplyTo: 4111153376, user: ME, createdAt: '2026-09-26T11:55:02Z', path: 'a.md', line: 2, body: 'Да.' },
  { id: 4111294584, inReplyTo: 4111153378, user: ME, createdAt: '2026-09-26T11:55:04Z', path: 'a.md', line: 3, body: 'Да.' },
  { id: 4111300548, inReplyTo: null, user: CODEX, createdAt: '2026-09-26T11:58:01Z', path: 'b.md', line: 7, body: remark('Четвёртое') },
  { id: 4111302671, inReplyTo: 4111300548, user: ME, createdAt: '2026-09-26T11:59:07Z', path: 'b.md', line: 7, body: 'Да.' },
];

test('#53 на момент вливания: одно замечание без ответа — с файлом, строкой и заголовком', () => {
  assert.deepEqual(unansweredThreads(pr53, { at: merged53 }), [
    { path: 'frontend/src/productRules.test.ts', line: 95, priority: 'P2', title: 'Сторож не читает контроллеры' },
  ]);
});

test('#53 сейчас: ответ есть — без ответа ноль', () => {
  assert.deepEqual(unansweredThreads(pr53), []);
});

test('#84: все четыре замечания отвечены', () => {
  assert.deepEqual(unansweredThreads(pr84), []);
});

test('Codex написал в ветке после нашего ответа — ветка снова за ним', () => {
  const followUp = { id: 4111400000, inReplyTo: 4111300548, user: CODEX, createdAt: '2026-09-26T12:10:00Z',
    path: 'b.md', line: 7, body: 'Ответ не закрывает случай с пустым списком.' };
  assert.deepEqual(unansweredThreads([...pr84, followUp]), [
    { path: 'b.md', line: 7, priority: null, title: 'Ответ не закрывает случай с пустым списком.' },
  ]);
});

test('ветка человека без слова Codex не считается', () => {
  const own = [{ id: 1, inReplyTo: null, user: ME, createdAt: '2026-09-26T12:00:00Z', path: 'c.md', line: 1, body: 'Заметка.' }];
  assert.deepEqual(unansweredThreads(own), []);
});

// Голова PR и время её пуша — создание первого набора проверок на коммит.
const head = { headSha: 'a1b2c3d', headPushedAt: '2026-09-26T12:00:00Z' };
const quiet = { reviews: [], prReactions: [], issueComments: [] };
const thumb = (createdAt) => ({ user: CODEX, content: '+1', createdAt });
const eyes = (createdAt) => ({ user: CODEX, content: 'eyes', createdAt });
const request = (createdAt, reactions = []) => ({ user: ME, body: '@codex review', createdAt, reactions });

test('Codex молчит — не готово', () => {
  const s = codexState({ ...head, ...quiet });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'молчит');
});

test('👀 на PR — Codex смотрит, не готово', () => {
  const s = codexState({ ...head, ...quiet, prReactions: [eyes('2026-09-26T12:01:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'смотрит');
});

test('👀 на последнем запросе @codex review — смотрит, даже если раньше был 👍', () => {
  const s = codexState({ ...head, ...quiet,
    prReactions: [thumb('2026-09-26T11:00:00Z')],
    issueComments: [request('2026-09-26T12:05:00Z', [eyes('2026-09-26T12:05:30Z')])] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'смотрит');
});

test('👍 до пуша головы — Codex голову не видел', () => {
  const s = codexState({ ...head, ...quiet, prReactions: [thumb('2026-09-26T11:00:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

test('👍 после пуша головы — готово', () => {
  const s = codexState({ ...head, ...quiet, prReactions: [thumb('2026-09-26T12:10:00Z')] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('ревью на коммите головы — видел, по коммиту, а не по времени', () => {
  const s = codexState({ ...head, ...quiet,
    reviews: [{ user: CODEX, submittedAt: '2026-09-26T11:59:00Z', commitId: 'a1b2c3d' }] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('ревью на прошлом коммите до пуша — не видел', () => {
  const s = codexState({ ...head, ...quiet,
    reviews: [{ user: CODEX, submittedAt: '2026-09-26T11:59:00Z', commitId: 'ffff000' }] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

test('👍 на запросе @codex review после пуша — видел', () => {
  const s = codexState({ ...head, ...quiet,
    issueComments: [request('2026-09-26T12:05:00Z', [thumb('2026-09-26T12:15:00Z')])] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('запрос после пуша без ответа и без 👀 — запрошен, не готово', () => {
  const s = codexState({ ...head, ...quiet,
    prReactions: [thumb('2026-09-26T11:00:00Z')],
    issueComments: [request('2026-09-26T12:05:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'запрошен');
});

test('--at: вердикт позже момента не учитывается, 👀 не определяется', () => {
  const s = codexState({ ...head, ...quiet, at: '2026-09-26T12:08:00Z',
    prReactions: [eyes('2026-09-26T12:01:00Z'), thumb('2026-09-26T12:10:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'молчит');
});

// #20–#46: вместо ревью Codex писал в ленту, что упёрся в лимит. Ревью не было.
const limit = (createdAt) => ({ user: CODEX, createdAt, reactions: [],
  body: 'You have reached your Codex usage limits for code reviews. You can see your limits in the Codex usage dashboard.' });

test('Codex упёрся в лимит — это не вердикт, не готово', () => {
  const s = codexState({ ...head, ...quiet, issueComments: [limit('2026-09-26T12:02:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'лимит');
});

test('другой комментарий Codex в ленте — не вердикт, его надо прочитать', () => {
  const note = { user: CODEX, createdAt: '2026-09-26T12:02:00Z', reactions: [], body: 'Не могу открыть ветку.' };
  const s = codexState({ ...head, ...quiet, issueComments: [note] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'написал');
});

test('голову уже видел, лимит пришёл на лишний повторный запрос — готово', () => {
  const s = codexState({ ...head, ...quiet,
    prReactions: [thumb('2026-09-26T12:10:00Z')],
    issueComments: [request('2026-09-26T12:30:00Z'), limit('2026-09-26T12:31:00Z')] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

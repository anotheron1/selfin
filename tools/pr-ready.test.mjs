// Тесты скрипта готовности PR (ANO-194, ворота 1).
// Спека: docs/superpowers/specs/2026-09-26-pr-ready-gate-design.md, раздел 7.
// Номера, логины и время — из публичной истории PR этого репозитория; текст замечаний выдуман.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import {
  CODEX, unansweredThreads, codexState, headTransitions, headAt, linearLinks, declaredClosing, linearState, ciState, pullRequestChecks,
  requiredChecks,
  baseState, summary,
} from './pr-ready.mjs';

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

// История голов PR — прогоны по pull_request на ветке PR после его открытия: у каждого коммит головы на момент события.
// Вердикт Codex привязан к коммиту, а не ко времени (ревью Codex на #86, круги 2–6): у ревью и «замечаний нет» есть
// номер коммита, 👍 на PR — вердикт по голове на открытии, 👍 на запрос — по голове на момент запроса.
const OLD = '0ld0000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const HEAD = 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678';
const OTHER = 'ffff0000000000000000000000000000000000ff';
const at = (sha, createdAt, opening = false) => ({ sha, createdAt, opening });
// opening — прогон по событию opened: голова на открытии PR записана.
const head = { headSha: HEAD, transitions: [at(HEAD, '2026-09-26T12:00:00Z', true)] };
const pushed = { headSha: HEAD, transitions: [at(OLD, '2026-09-26T10:00:00Z', true), at(HEAD, '2026-09-26T12:00:00Z')] };
const back = { headSha: HEAD,
  transitions: [at(HEAD, '2026-09-26T10:00:00Z', true), at(OTHER, '2026-09-26T11:00:00Z'), at(HEAD, '2026-09-26T12:00:00Z')] };
const quiet = { reviews: [], prReactions: [], issueComments: [] };
const thumb = (createdAt) => ({ user: CODEX, content: '+1', createdAt });
const eyes = (createdAt) => ({ user: CODEX, content: 'eyes', createdAt });
const request = (createdAt, reactions = []) => ({ user: ME, body: '@codex review', createdAt, reactions });
const REVIEW_BODY = '### 💡 Codex Review\n\nHere are some automated review suggestions for this pull request.';
const review = (submittedAt, commitId, body = REVIEW_BODY) => ({ user: CODEX, submittedAt, commitId, body });

// Заголовок прогона «Ответов Codex» — «Ответы Codex — <событие> <действие>» (run-name в workflow).
const SYNC = 'Ответы Codex — pull_request synchronize';
const REPLIES = '.github/workflows/codex-replies.yml';
const run = (createdAt, headSha, { event = 'pull_request', headBranch = 'ci/x', title = SYNC, workflow = REPLIES } = {}) =>
  ({ workflow, event, createdAt, headSha, headBranch, displayTitle: title });

test('история голов — прогоны по pull_request на ветке PR после его открытия, по порядку', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T12:00:00Z', HEAD), run('2026-09-26T10:00:05Z', OLD),
  ] }), [at(OLD, '2026-09-26T10:00:05Z'), at(HEAD, '2026-09-26T12:00:00Z')]);
});

test('история голов — прогон «Ответов Codex» по opened помечен как открытие PR', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T10:00:05Z', OLD, { title: 'Ответы Codex — pull_request opened' }), run('2026-09-26T12:00:00Z', HEAD),
  ] }), [at(OLD, '2026-09-26T10:00:05Z', true), at(HEAD, '2026-09-26T12:00:00Z')]);
});

// Двенадцатое ревью Codex на #86 (P1): у прогонов CI заголовок — название PR, и оно может кончаться теми же словами.
test('история голов — прогон CI с названием PR на «pull_request opened» открытием не считается', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T10:30:00Z', HEAD, { title: 'Чинит pull_request opened', workflow: '.github/workflows/ci.yml' }),
  ] }), [at(HEAD, '2026-09-26T10:30:00Z')]);
});

test('история голов — прогон с заголовком PR (CI) открытием не считается', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T10:00:05Z', OLD, { title: 'Ворота готовности PR: скрипт, джоба ответов Codex, шаблон' }),
  ] }), [at(OLD, '2026-09-26T10:00:05Z')]);
});

test('история голов — прогон того же коммита в другой ветке не в счёт', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T12:00:00Z', HEAD), run('2026-09-26T13:00:00Z', OTHER, { headBranch: 'exp' }),
  ] }), [at(HEAD, '2026-09-26T12:00:00Z')]);
});

test('история голов — прогон по событию ревью не в счёт: он не про голову', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T10:00:00Z', runs: [
    run('2026-09-26T12:00:00Z', HEAD), run('2026-09-26T13:00:00Z', OTHER, { event: 'pull_request_review' }),
  ] }), [at(HEAD, '2026-09-26T12:00:00Z')]);
});

test('история голов — прогон до открытия PR не в счёт: прошлый PR с той же веткой', () => {
  assert.deepEqual(headTransitions({ branch: 'ci/x', openedAt: '2026-09-26T11:00:00Z', runs: [
    run('2026-09-26T10:00:00Z', OTHER), run('2026-09-26T12:00:00Z', HEAD),
  ] }), [at(HEAD, '2026-09-26T12:00:00Z')]);
});

test('голова в момент времени — по последнему прогону до него; до первого — неизвестна', () => {
  assert.equal(headAt(back.transitions, '2026-09-26T11:30:00Z'), OTHER);
  assert.equal(headAt(back.transitions, '2026-09-26T12:00:00Z'), HEAD);
  assert.equal(headAt(back.transitions, '2026-09-26T09:00:00Z'), undefined);
});

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
    prReactions: [thumb('2026-09-26T12:10:00Z')],
    issueComments: [request('2026-09-26T12:20:00Z', [eyes('2026-09-26T12:20:30Z')])] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'смотрит');
});

test('👍 на PR, голова с открытия не менялась — видел', () => {
  const s = codexState({ ...head, ...quiet, prReactions: [thumb('2026-09-26T12:10:00Z')] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('👍 на PR — вердикт по голове на открытии: после пуша новой головы не в счёт', () => {
  const s = codexState({ ...pushed, ...quiet, prReactions: [thumb('2026-09-26T10:05:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

test('👍 на PR пришёл уже после пуша новой головы — всё равно про голову на открытии, не видел', () => {
  const s = codexState({ ...pushed, ...quiet, prReactions: [thumb('2026-09-26T12:05:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

test('ревью на коммите головы — видел', () => {
  const s = codexState({ ...pushed, ...quiet, reviews: [review('2026-09-26T12:10:00Z', HEAD)] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

// #86, 14:50: ответ Codex в ветке («To use Codex here, create an environment») GitHub завернул в ревью с пустым
// телом на голове. Это не ревью — у настоящих ревью Codex тело начинается с «Codex Review».
test('ответ Codex в ветке — ревью с пустым телом — не вердикт', () => {
  const s = codexState({ ...head, ...quiet, reviews: [review('2026-09-26T12:10:00Z', HEAD, '')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'молчит');
});

test('ревью прошлого коммита — не видел, когда бы оно ни пришло', () => {
  const s = codexState({ ...pushed, ...quiet, reviews: [review('2026-09-26T12:10:00Z', OLD)] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

// Девятое ревью Codex на #86 (P1): 👍 на запрос не к чему надёжно привязать — запрос мог уйти раньше, чем записан
// прогон нового пуша. Codex на запрос так и не отвечает: на запрос — ревью или «замечаний нет», оба с номером коммита.
test('👍 на запрос — не вердикт: Codex отвечает на запрос ревью или «замечаний нет»', () => {
  const s = codexState({ ...pushed, ...quiet,
    issueComments: [request('2026-09-26T12:05:00Z', [thumb('2026-09-26T12:15:00Z')])] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'запрошен');
});

test('запрос при прошлой голове без ответа — не «запрошен»: для нынешней нужен новый запрос', () => {
  const s = codexState({ ...pushed, ...quiet,
    prReactions: [thumb('2026-09-26T10:05:00Z')],
    issueComments: [request('2026-09-26T11:00:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

test('запрос при нынешней голове без ответа и без 👀 — запрошен, не готово', () => {
  const s = codexState({ ...pushed, ...quiet,
    prReactions: [thumb('2026-09-26T10:05:00Z')],
    issueComments: [request('2026-09-26T12:05:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'запрошен');
});

// Шестое ревью Codex на #86 (P1): голова ушла с X на Y и вернулась на X, а прогон по возвращению ещё не пришёл.
test('последний записанный прогон — на другом коммите: смена головы не записана, не готово', () => {
  const s = codexState({ headSha: HEAD, transitions: [at(HEAD, '2026-09-26T10:00:00Z'), at(OTHER, '2026-09-26T11:00:00Z')],
    ...quiet, reviews: [review('2026-09-26T10:30:00Z', HEAD)] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'смена головы не записана');
});

test('прогонов по pull_request нет вовсе — смена головы не записана', () => {
  const s = codexState({ headSha: HEAD, transitions: [], ...quiet, reviews: [review('2026-09-26T10:30:00Z', HEAD)] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'смена головы не записана');
});

test('X → Y → X: ревью X из первого появления засчитывается — коммит тот же', () => {
  const s = codexState({ ...back, ...quiet, reviews: [review('2026-09-26T10:30:00Z', HEAD)] });
  assert.equal(s.ok, true);
});

test('--at: вердикт позже момента не учитывается, 👀 не определяется', () => {
  const s = codexState({ ...head, ...quiet, at: '2026-09-26T12:08:00Z',
    prReactions: [eyes('2026-09-26T12:01:00Z'), thumb('2026-09-26T12:10:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'молчит');
});

// Восьмое ревью Codex на #86 (P2): при --at раньше позднего пуша голова — та, что была тогда, а не нынешняя.
test('--at до позднего пуша: голова на тот момент, а не нынешняя', () => {
  const s = codexState({ ...pushed, ...quiet, at: '2026-09-26T11:00:00Z', reviews: [review('2026-09-26T10:30:00Z', OLD)] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('--at: прогон позже момента не в счёт — ревью нынешней головы тогда ещё не было', () => {
  const s = codexState({ ...pushed, ...quiet, at: '2026-09-26T11:00:00Z', reviews: [review('2026-09-26T12:10:00Z', HEAD)] });
  assert.equal(s.ok, false);
});

// Восьмое и девятое ревью Codex на #86 (P1): голова на открытии — только из записанного прогона по opened.
// По времени не годится: быстрый второй пуш в первые минуты выглядел бы открытием.
test('прогона по opened нет — 👍 на PR не вердикт', () => {
  const s = codexState({ headSha: HEAD, transitions: [at(HEAD, '2026-09-26T12:30:00Z')], ...quiet,
    prReactions: [thumb('2026-09-26T12:05:00Z')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'молчит');
});

test('прогона по opened нет, быстрый второй пуш — 👍 первого ревью к новой голове не привязывается', () => {
  const s = codexState({ headSha: HEAD, transitions: [at(HEAD, '2026-09-26T12:01:00Z')], ...quiet,
    prReactions: [thumb('2026-09-26T12:04:00Z')] });
  assert.equal(s.ok, false);
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

// #85: на повторный запрос без замечаний Codex ответил в ленте — с номером просмотренного коммита.
const SHA85 = '9378d0be107df86ea5caa9f94d441eaac05d6c69';
const head85 = { headSha: SHA85, transitions: [at(SHA85, '2026-09-26T12:33:57Z', true)] };
const clean = (createdAt, sha) => ({ user: CODEX, createdAt, reactions: [],
  body: `Codex Review: Didn't find any major issues. Hooray!\n\n**Reviewed commit:** \`${sha}\`\n\n<details>About Codex</details>` });

test('#85: лимит, лимит, потом «Didn\'t find any major issues» на голову — видел', () => {
  const s = codexState({ ...head85, ...quiet, issueComments: [
    limit('2026-09-26T12:33:59Z'),
    request('2026-09-26T13:52:21Z'), limit('2026-09-26T13:52:29Z'),
    request('2026-09-26T14:05:02Z'), clean('2026-09-26T14:06:36Z', '9378d0be10'),
  ] });
  assert.equal(s.ok, true);
  assert.equal(s.state, 'видел');
});

test('«замечаний нет» про прошлый коммит — не видел', () => {
  const s = codexState({ ...head85, ...quiet, issueComments: [clean('2026-09-26T14:06:36Z', 'ffff000000')] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'не видел голову');
});

// Третье ревью Codex на #86 (P1): без разобранного номера коммита «замечаний нет» — не вердикт, а текст для чтения.
test('«замечаний нет» без номера коммита — не вердикт, прочитать', () => {
  const bare = { user: CODEX, createdAt: '2026-09-26T12:10:00Z', reactions: [], body: "Codex Review: Didn't find any major issues. Hooray!" };
  const s = codexState({ ...head, ...quiet, issueComments: [bare] });
  assert.equal(s.ok, false);
  assert.equal(s.state, 'написал');
});

// Linear закрывает задачу при вливании, если номер стоит в ветке, в заголовке
// или в описании после закрывающего слова; после «part of» и подобных — только связывает.
const title65 = 'Режим нехватки у кармашка: сначала дата, потом что сдвинуть (ANO-100, ANO-76)';

test('#65: ветка и заголовок — Linear закроет ANO-76 и ANO-100', () => {
  assert.deepEqual(
    linearLinks({ branch: 'feat/ano-100-gap-mode', title: title65, body: 'Попутно видно в ANO-92 и ANO-179.' }),
    { closes: ['ANO-76', 'ANO-100'], links: [] });
});

test('закрывающее слово со списком — все номера списка', () => {
  assert.deepEqual(
    linearLinks({ branch: 'ci/x', title: 'Т', body: 'Fixes ANO-1, ANO-2 and ANO-3' }),
    { closes: ['ANO-1', 'ANO-2', 'ANO-3'], links: [] });
});

test('Part of — связь без закрытия', () => {
  assert.deepEqual(
    linearLinks({ branch: 'ci/pr-ready-gate', title: 'Ворота готовности PR', body: 'Part of ANO-194' }),
    { closes: [], links: ['ANO-194'] });
});

test('номер в тексте без слова — ни закрытия, ни связи', () => {
  assert.deepEqual(
    linearLinks({ branch: 'docs/x', title: 'Т', body: 'Как в ANO-88, только для хотелок.' }),
    { closes: [], links: [] });
});

test('регистр: ветка в нижнем, слово с заглавной', () => {
  assert.deepEqual(
    linearLinks({ branch: 'kir1904/ano-151-sidi', title: 'Т', body: 'Closes ano-5' }),
    { closes: ['ANO-5', 'ANO-151'], links: [] });
});

test('номер и в заголовке, и после part of — закроется', () => {
  assert.deepEqual(
    linearLinks({ branch: 'x', title: 'Т (ANO-7)', body: 'Part of ANO-7' }),
    { closes: ['ANO-7'], links: [] });
});

test('Закроет: номера через запятую', () => {
  assert.deepEqual(declaredClosing('Текст.\n\nЗакроет: ANO-100, ANO-101\n'), { ids: ['ANO-100', 'ANO-101'] });
});

test('Закроет: — ничего', () => {
  assert.deepEqual(declaredClosing('Закроет: —'), { ids: [] });
});

test('строки «Закроет:» нет — ошибка', () => {
  assert.ok(declaredClosing('Только текст.').error);
});

test('незаполненная строка из шаблона — ошибка', () => {
  assert.ok(declaredClosing('Закроет: ANO-…').error);
});

test('номер с пояснением — ошибка: объявление читается машиной', () => {
  assert.ok(declaredClosing('Закроет: ANO-100 частично').error);
  assert.deepEqual(declaredClosing('Закроет: ANO-100 и ANO-101'), { ids: ['ANO-100', 'ANO-101'] });
});

test('строка внутри HTML-комментария шаблона не считается', () => {
  assert.ok(declaredClosing('<!--\nПример:\nЗакроет: —\n-->\nТекст.').error);
});

test('#65 с объявленной ANO-100 — не готово: закроется ещё ANO-76', () => {
  const s = linearState({ branch: 'feat/ano-100-gap-mode', title: title65, body: 'Закроет: ANO-100' });
  assert.equal(s.ok, false);
  assert.match(s.text, /ANO-76/);
});

test('объявлено ровно то, что закроется, — готово', () => {
  const s = linearState({ branch: 'ci/pr-ready-gate', title: 'Ворота готовности PR', body: 'Part of ANO-194\n\nЗакроет: —' });
  assert.equal(s.ok, true);
});

test('без строки «Закроет:» — не готово', () => {
  assert.equal(linearState({ branch: 'x', title: 'Т', body: 'Текст.' }).ok, false);
});

// Проверки CI головы PR: по имени берётся последний прогон — джоба ответов Codex гоняется на разные события.
const check = (name, bucket, startedAt = '2026-09-26T12:34:00Z', workflow = 'CI') => ({ name, bucket, startedAt, workflow });

test('CI: упавшая проверка — не готово', () => {
  const s = ciState([check('Фронт — типы и тесты', 'pass'), check('Бэк — юниты и интеграционные', 'fail')]);
  assert.equal(s.ok, false);
  assert.match(s.text, /Бэк/);
});

test('CI: идущая проверка — ждёт', () => {
  const s = ciState([check('Фронт — типы и тесты', 'pass'), check('Бэк — юниты и интеграционные', 'pending')]);
  assert.equal(s.ok, false);
  assert.match(s.text, /идут/);
});

test('CI: прошедшие и пропущенные — готово', () => {
  assert.equal(ciState([check('Фронт — типы и тесты', 'pass'), check('Миграции на данных — main → PR', 'skipping')]).ok, true);
});

test('CI: из повторов имени берётся последний прогон', () => {
  const red = check('Codex — все замечания отвечены', 'fail', '2026-09-26T12:40:00Z', 'Ответы Codex');
  const green = check('Codex — все замечания отвечены', 'pass', '2026-09-26T12:45:00Z', 'Ответы Codex');
  assert.equal(ciState([green, red]).ok, true);
  assert.equal(ciState([{ ...red, startedAt: '2026-09-26T12:50:00Z' }, green]).ok, false);
});

test('CI: проверок нет — не готово', () => {
  assert.equal(ciState([]).ok, false);
});

// Ревью Codex на #86 (P1, первое и двенадцатое): ожидаются все проверки-джобы workflow на pull_request, а не только
// имена workflow. Иначе не запустившийся CI или CI без джобы бэка выглядели бы чистыми.
const need = (workflow, name) => ({ workflow, check: name });
const FRONT = need('CI', 'Фронт — типы и тесты');
const REPLIED = need('Ответы Codex', 'Codex — все замечания отвечены');

test('CI: ожидаемый workflow не запускался — не готово, хотя всё пришедшее зелёное', () => {
  const s = ciState([check('Codex — все замечания отвечены', 'pass', undefined, 'Ответы Codex')], [FRONT, REPLIED]);
  assert.equal(s.ok, false);
  assert.match(s.text, /не пришли: CI — Фронт — типы и тесты/);
});

test('CI: джоба из базы не пришла, а другая джоба того же workflow пришла — не готово', () => {
  const s = ciState([check('Фронт — типы и тесты', 'pass'), check('Codex — все замечания отвечены', 'pass', undefined, 'Ответы Codex')],
    [FRONT, need('CI', 'Бэк — юниты и интеграционные'), REPLIED]);
  assert.equal(s.ok, false);
  assert.match(s.text, /не пришли: CI — Бэк/);
});

test('CI: все ожидаемые проверки пришли и прошли — готово', () => {
  const s = ciState([check('Фронт — типы и тесты', 'pass'), check('Codex — все замечания отвечены', 'pass', undefined, 'Ответы Codex')],
    [FRONT, REPLIED]);
  assert.equal(s.ok, true);
});

test('ожидаемые проверки — по настоящим файлам репозитория: каждая джоба workflow на pull_request', () => {
  const dir = new URL('../.github/workflows/', import.meta.url);
  const files = readdirSync(dir).map((f) => ({ path: f, text: readFileSync(new URL(f, dir), 'utf8') }));
  assert.deepEqual(requiredChecks({ base: files, pr: files }), [
    need('CI', 'Бэк — юниты и интеграционные'),
    need('CI', 'Инструменты — тесты'),
    need('CI', 'Миграции на данных — main → PR'),
    need('CI', 'Образ фронта собирается'),
    need('CI', 'Фронт — типы и тесты'),
    need('Ответы Codex', 'Codex — все замечания отвечены'),
    need('Ответы Codex', 'Красные прогоны по пушу — перезапуск'),
  ]);
});

test('имя проверки — name джобы, без него — ключ джобы; кавычки снимаются', () => {
  const text = [
    "name: 'Lint'",
    'on: [push, pull_request]',
    'jobs:',
    '  a:',
    '    name: "Стиль"',
    '    steps:',
    '      - name: шаг',
    '  b:',
    '    steps: []',
    '',
  ].join('\n');
  assert.deepEqual(pullRequestChecks([{ path: 'l.yml', text }]), [need('Lint', 'Стиль'), need('Lint', 'b')]);
});

test('workflow только на пуш — его джобы не ожидаются', () => {
  const text = ['name: Publish', 'on:', '  push:', '    branches: [main]', 'jobs:', '  x:', '    name: Опубликовать', '    steps: []', ''].join('\n');
  assert.deepEqual(pullRequestChecks([{ path: 'a.yml', text }]), []);
});

// Одиннадцатое и двенадцатое ревью Codex на #86 (P1): PR не должен убирать проверку, которая проверяет его самого.
const ciFile = (on, jobs) => ({
  path: 'ci.yml',
  text: ['name: CI', 'on:', ...on, 'jobs:', ...jobs.flatMap((name, i) => [`  j${i}:`, `    name: ${name}`, '    steps: []']), ''].join('\n'),
});

test('PR убрал pull_request из CI — джобы CI всё равно ожидаются: из базы и из PR вместе', () => {
  const fresh = { path: 'new.yml', text: ['name: Новый', 'on:', '  pull_request:', 'jobs:', '  x:', '    name: Проверка', '    steps: []', ''].join('\n') };
  assert.deepEqual(requiredChecks({
    base: [ciFile(['  pull_request:'], ['Фронт'])],
    pr: [ciFile(['  push:', '    branches: [main]'], ['Фронт']), fresh],
  }), [need('CI', 'Фронт'), need('Новый', 'Проверка')]);
});

test('PR удалил из CI джобу бэка — её проверка всё равно ожидается', () => {
  assert.deepEqual(requiredChecks({
    base: [ciFile(['  pull_request:'], ['Бэк', 'Фронт'])],
    pr: [ciFile(['  pull_request:'], ['Фронт'])],
  }), [need('CI', 'Бэк'), need('CI', 'Фронт')]);
});

// Десятое ревью Codex на #86 (P1): код из ветки PR с правом actions: write может запускать чужие workflow.
// Сторож по исходнику: право на запись — только у джобы, которая кода PR не выгружает и не выполняет.
test('«Ответы Codex»: actions: write — только у джобы без кода PR, и не на уровне всего workflow', () => {
  const text = readFileSync(new URL('../.github/workflows/codex-replies.yml', import.meta.url), 'utf8')
    .split('\n').filter((line) => !/^\s*#/.test(line)).join('\n');
  const jobsAt = text.search(/^jobs:/m);
  assert.doesNotMatch(text.slice(0, jobsAt), /actions:\s*write/);
  const jobs = text.slice(jobsAt).split(/^ {2}(?=[\w-]+:\s*$)/m).slice(1);
  const writers = jobs.filter((job) => /actions:\s*write/.test(job));
  assert.equal(writers.length, 1);
  for (const job of writers) assert.doesNotMatch(job, /actions\/checkout|tools\//);
});

test('база main, сверху никого — готово', () => {
  assert.equal(baseState({ base: 'main', children: [] }).ok, true);
});

test('база не main — PR в стопке, не готово', () => {
  const s = baseState({ base: 'ci/ano-26-pr-checks', children: [] });
  assert.equal(s.ok, false);
  assert.match(s.text, /ci\/ano-26-pr-checks/);
});

test('на ветке PR стоит другой PR — сначала перенацелить его на main', () => {
  const s = baseState({ base: 'main', children: [52] });
  assert.equal(s.ok, false);
  assert.match(s.text, /#52/);
});

test('итог: пять чистых пунктов — готово, любой нечистый — нет', () => {
  const clean = ['CI', 'Codex', 'Замечания', 'База', 'Linear'].map((title) => ({ title, ok: true, text: 'ок' }));
  assert.equal(summary(clean).ok, true);
  assert.equal(summary([...clean.slice(0, 4), { title: 'Linear', ok: false, text: 'нет строки' }]).ok, false);
});

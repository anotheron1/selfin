// node --test tools/delete-person.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deletionSql, journalEntries, journalLine, prunedJournal, tableSetProblems, PERSON_TABLES } from './delete-person.mjs';

const ID = '7b0c1c8e-2f6a-4c3e-9a51-3f1d2e4b5a69';
const KC = '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';

test('удаление — одной транзакцией, по списку, строка users последней, запись в журнал — той же транзакцией (шаг 5)', () => {
  const lines = deletionSql({ userId: ID, kcId: KC }, ['fund_transactions', 'categories']).split('\n');
  assert.deepEqual(lines, [
    'BEGIN;',
    `DELETE FROM fund_transactions WHERE user_id = '${ID}';`,
    `DELETE FROM categories WHERE user_id = '${ID}';`,
    `DELETE FROM users WHERE id = '${ID}';`,
    `INSERT INTO ops.deletions (kc_sub, person_id) VALUES ('${KC}', '${ID}');`,
    'COMMIT;',
  ]);
});

test('наших строк нет — в журнал всё равно: удалена учётная запись Keycloak (шаг 5)', () => {
  assert.equal(deletionSql({ userId: undefined, kcId: KC }),
    `INSERT INTO ops.deletions (kc_sub, person_id) VALUES ('${KC}', NULL);`);
});

test('«только наши строки» — не удаление человека: журнала нет, иначе повтор после восстановления удалил бы владельца', () => {
  const sql = deletionSql({ userId: ID, kcId: KC, journal: false }, ['categories']);
  assert.doesNotMatch(sql, /ops\.deletions/);
  assert.match(sql, /^BEGIN;[\s\S]*COMMIT;$/);
});

test('по умолчанию — порядок из person-tables.json', () => {
  const tables = deletionSql({ userId: ID, kcId: KC }).split('\n').slice(1, -3).map((line) => line.split(' ')[2]);
  assert.deepEqual(tables, PERSON_TABLES);
});

test('в SQL идёт только id, не произвольная строка', () => {
  assert.throws(() => deletionSql({ userId: `${ID}'; DROP TABLE users; --`, kcId: KC }), /не id/);
  assert.throws(() => deletionSql({ userId: ID, kcId: `${KC}'); --` }), /не id/);
});

test('синтетического владельца не удаляет: это история владельца, на стенде — копия', () => {
  assert.throws(() => deletionSql({ userId: '00000000-0000-0000-0000-000000000001', kcId: KC }), /синтетическ/);
});

test('строка журнала владельца — когда, чей реалм, sub и наш id (шаг 5)', () => {
  const at = '2026-10-18T10:00:00.000Z';
  assert.deepEqual(JSON.parse(journalLine({ at, issuer: 'https://s.example/auth/realms/selfin', kcId: KC, userId: ID })),
    { at, issuer: 'https://s.example/auth/realms/selfin', sub: KC, userId: ID });
  assert.equal(JSON.parse(journalLine({ at, issuer: 'x', kcId: KC })).userId, null);
});

test('повтор — только удаления того же реалма, каждый человек один раз; пустые строки — ничего (шаг 5)', () => {
  const server = 'https://s.example/auth/realms/selfin';
  const lines = [
    JSON.stringify({ at: '1', issuer: server, sub: KC, userId: ID }),
    JSON.stringify({ at: '2', issuer: 'http://127.0.0.1:8180/realms/selfin', sub: ID, userId: null }),
    '',
    JSON.stringify({ at: '3', issuer: server, sub: KC, userId: ID }),
  ];
  assert.deepEqual(journalEntries(lines, server), { subs: [KC], malformed: [] });
});

test('битая строка журнала не пропускается молча: в ней могло быть удаление после копии (ревью Codex на #175)', () => {
  const server = 'https://s.example/auth/realms/selfin';
  const lines = [
    JSON.stringify({ at: '1', issuer: server, sub: KC, userId: ID }),
    '{"at":"2","issuer":"https://s.exa',
    JSON.stringify({ at: '3', issuer: server, sub: 'не id', userId: null }),
    JSON.stringify({ at: '4', sub: KC }),
    '   ',
  ];
  assert.deepEqual(journalEntries(lines, server), { subs: [KC], malformed: [2, 3, 4] });
});

test('журнал владельца забывает записи старше 30 дней: копий с этим человеком уже нет (ревью Codex на #175)', () => {
  const now = new Date('2026-11-30T12:00:00Z');
  const entry = (at) => JSON.stringify({ at, issuer: 'x', sub: KC, userId: ID });
  const lines = [entry('2026-10-30T11:00:00Z'), entry('2026-10-31T13:00:00Z'), '', '{"обрыв', entry('2026-11-29T00:00:00Z')];
  assert.deepEqual(prunedJournal(lines, now), [entry('2026-10-31T13:00:00Z'), '{"обрыв', entry('2026-11-29T00:00:00Z')]);
});

test('набор таблиц: совпал — проблем нет, расхождение — названо в обе стороны', () => {
  assert.deepEqual(tableSetProblems(['a', 'b'], ['b', 'a']), []);
  assert.deepEqual(tableSetProblems(['a', 'b', 'new'], ['a', 'b', 'gone']), [
    'в схеме есть new, в списке удаления — нет',
    'в списке удаления есть gone, в схеме — нет',
  ]);
});

test('список без повторов', () => {
  assert.equal(new Set(PERSON_TABLES).size, PERSON_TABLES.length);
});

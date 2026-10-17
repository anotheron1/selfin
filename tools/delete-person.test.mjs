// node --test tools/delete-person.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deletionSql, tableSetProblems, PERSON_TABLES } from './delete-person.mjs';

const ID = '7b0c1c8e-2f6a-4c3e-9a51-3f1d2e4b5a69';

test('удаление — одной транзакцией, по списку, строка users последней', () => {
  const lines = deletionSql(ID, ['fund_transactions', 'categories']).split('\n');
  assert.deepEqual(lines, [
    'BEGIN;',
    `DELETE FROM fund_transactions WHERE user_id = '${ID}';`,
    `DELETE FROM categories WHERE user_id = '${ID}';`,
    `DELETE FROM users WHERE id = '${ID}';`,
    'COMMIT;',
  ]);
});

test('по умолчанию — порядок из person-tables.json', () => {
  const tables = deletionSql(ID).split('\n').slice(1, -2).map((line) => line.split(' ')[2]);
  assert.deepEqual(tables, PERSON_TABLES);
});

test('в SQL идёт только id, не произвольная строка', () => {
  assert.throws(() => deletionSql(`${ID}'; DROP TABLE users; --`), /не id/);
});

test('синтетического владельца не удаляет: это история владельца, на стенде — копия', () => {
  assert.throws(() => deletionSql('00000000-0000-0000-0000-000000000001'), /синтетическ/);
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

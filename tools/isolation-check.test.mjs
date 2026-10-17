// node --test tools/isolation-check.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { leaks, changed } from './isolation-check.mjs';

const FOREIGN = new Map([['11111111-aaaa-4aaa-8aaa-111111111111', 'accounts'], ['22222222-bbbb-4bbb-8bbb-222222222222', 'users']]);

test('чужой id в ответе — утечка: вызов, таблица и id', () => {
  const answers = {
    'GET /accounts': { status: 200, text: '[{"id":"11111111-aaaa-4aaa-8aaa-111111111111"}]' },
    'GET /me': { status: 200, text: '{"id":"33333333-cccc-4ccc-8ccc-333333333333"}' },
  };
  assert.deepEqual(leaks(answers, FOREIGN), [['GET /accounts', ['accounts 11111111-aaaa-4aaa-8aaa-111111111111']]]);
});

test('свои id — не утечка', () => {
  assert.deepEqual(leaks({ 'GET /me': { status: 200, text: '{"id":"33333333-cccc-4ccc-8ccc-333333333333"}' } }, FOREIGN), []);
});

test('изменившиеся чтения — по тексту и по статусу; одинаковые — нет', () => {
  const before = { a: { status: 200, text: '1' }, b: { status: 200, text: '2' }, c: { status: 200, text: '3' } };
  const after = { a: { status: 200, text: '1' }, b: { status: 200, text: '20' }, c: { status: 500, text: '3' } };
  assert.deepEqual(changed(before, after), ['b', 'c']);
  assert.deepEqual(changed(before, before), []);
});

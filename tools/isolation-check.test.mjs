// node --test tools/isolation-check.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { leaks, changed, runnable } from './isolation-check.mjs';

test('удар без чужой строки нужной роли пропускается: он проверял бы не изоляцию (ревью Codex на #167)', () => {
  const entries = [{ role: 'fund', path: 'a' }, { role: 'account', path: 'b' }];
  assert.deepEqual(runnable(entries, { account: 'id-1', fund: undefined }), [{ role: 'account', path: 'b' }]);
});

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

test('порядок в массиве — не изменение, как NON_EXTENSIBLE в TwoPeopleIsolationIT (ANO-232)', () => {
  const before = { p: { status: 200, text: '{"upcoming":[{"id":"a","n":1},{"id":"b","n":2}],"x":1}' } };
  const after = { p: { status: 200, text: '{"x":1,"upcoming":[{"n":2,"id":"b"},{"n":1,"id":"a"}]}' } };
  assert.deepEqual(changed(before, after), []);
});

test('новая строка или другое значение в массиве — изменение', () => {
  const before = { p: { status: 200, text: '{"upcoming":[{"id":"a","n":1}]}' } };
  assert.deepEqual(changed(before, { p: { status: 200, text: '{"upcoming":[{"id":"a","n":1},{"id":"c","n":3}]}' } }), ['p']);
  assert.deepEqual(changed(before, { p: { status: 200, text: '{"upcoming":[{"id":"a","n":2}]}' } }), ['p']);
});

test('изменившиеся чтения — по тексту и по статусу; одинаковые — нет', () => {
  const before = { a: { status: 200, text: '1' }, b: { status: 200, text: '2' }, c: { status: 200, text: '3' } };
  const after = { a: { status: 200, text: '1' }, b: { status: 200, text: '20' }, c: { status: 500, text: '3' } };
  assert.deepEqual(changed(before, after), ['b', 'c']);
  assert.deepEqual(changed(before, before), []);
});

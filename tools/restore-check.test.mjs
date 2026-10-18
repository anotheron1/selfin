// node --test tools/restore-check.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseManifest, tableProblems } from './restore-check.mjs';

const KC = '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f';

const MANIFEST = [
  'backend {"commit":"abc1234"}',
  'table public.categories 30 9e107d9d372bb6826bd81d3542a419d6',
  'table public.users 2 e4d909c290d0fb1ca068ffaddf22cbd0',
  'table ops.deletions 1 d41d8cd98f00b204e9800998ecf8427e',
  `deletion 2026-10-18T10:00:00+00 ${KC} -`,
].join('\n');

test('манифест: образ бэка, таблицы с количеством и отпечатком, удаления из журнала', () => {
  const manifest = parseManifest(MANIFEST);
  assert.equal(manifest.backend, '{"commit":"abc1234"}');
  assert.deepEqual([...manifest.tables.keys()], ['public.categories', 'public.users', 'ops.deletions']);
  assert.deepEqual(manifest.tables.get('public.categories'), { count: 30, md5: '9e107d9d372bb6826bd81d3542a419d6' });
  assert.deepEqual(manifest.deletions, [KC]);
});

test('восстановленное совпало с копией — расхождений нет', () => {
  const manifest = parseManifest(MANIFEST);
  assert.deepEqual(tableProblems(manifest.tables, parseManifest(MANIFEST).tables), []);
});

test('расхождения названы: строк не столько, содержимое другое, таблицы нет или лишняя', () => {
  const expected = parseManifest(MANIFEST).tables;
  const actual = parseManifest([
    'table public.categories 29 9e107d9d372bb6826bd81d3542a419d6',
    'table public.users 2 00000000000000000000000000000000',
    'table public.extra 0 d41d8cd98f00b204e9800998ecf8427e',
  ].join('\n')).tables;
  assert.deepEqual(tableProblems(expected, actual), [
    'public.categories: строк в копии 30, после восстановления 29',
    'public.users: содержимое расходится с копией',
    'ops.deletions: в копии есть, после восстановления нет',
    'public.extra: после восстановления есть, в копии нет',
  ]);
});

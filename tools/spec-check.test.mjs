// Тесты проверки спек (ANO-194, ворота 3).
// Спека: docs/superpowers/specs/2026-09-26-spec-sections-check-design.md, раздел 4.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { specProblems } from './spec-check.mjs';

const doc = (...lines) => ['# Спека', '', ...lines, ''].join('\n');
const RULES = '## Правила продукта — сверка';
const HOW = '## Как мерил';

test('оба раздела, есть замер — проблем нет', () => {
  assert.deepEqual(specProblems(doc('## Что сломано — замер', RULES, HOW)), []);
});

test('нет раздела сверки — проблема', () => {
  assert.deepEqual(specProblems(doc('## Решение')), ['нет раздела «Правила продукта — сверка»']);
});

test('есть замер, нет «Как мерил» — проблема', () => {
  assert.deepEqual(specProblems(doc('## Что сломано — замер 26.09', RULES)),
    ['есть замер или перемер, нет раздела «Как мерил»']);
});

test('нет замера и нет «Как мерил» — проблем нет', () => {
  assert.deepEqual(specProblems(doc('## Решение', RULES)), []);
});

test('заголовок с номером засчитывается', () => {
  assert.deepEqual(specProblems(doc('## 6. Правила продукта — сверка')), []);
});

test('заголовок уровня ### засчитывается', () => {
  assert.deepEqual(specProblems(doc('### Правила продукта — сверка')), []);
});

test('слова раздела в тексте, а не в заголовке, не засчитываются', () => {
  assert.deepEqual(specProblems(doc('* спека — путь; в ней есть раздел «Правила продукта — сверка»;')),
    ['нет раздела «Правила продукта — сверка»']);
});

test('«Перемер» с заглавной — замер найден', () => {
  assert.deepEqual(specProblems(doc('## Перемер от экрана', RULES)), ['есть замер или перемер, нет раздела «Как мерил»']);
});

test('настоящие спеки ворот — проблем нет', () => {
  for (const name of ['2026-09-26-spec-sections-check-design.md', '2026-09-26-pr-ready-gate-design.md']) {
    const text = readFileSync(new URL(`../docs/superpowers/specs/${name}`, import.meta.url), 'utf8');
    assert.deepEqual(specProblems(text), [], name);
  }
});

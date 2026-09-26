#!/usr/bin/env node
// Проверка спек — ворота 3 (ANO-194).
// Спека: docs/superpowers/specs/2026-09-26-spec-sections-check-design.md
//
//   node tools/spec-check.mjs [<база>]   спеки *-design.md, добавленные, изменённые или переименованные
//                                          относительно базы (по умолчанию origin/main); выход 0 — проблем нет

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Заголовок раздела: уровень от ## до ####, номер перед названием допускается — «## 6. Правила продукта — сверка».
// Только пробелы и табы, не \s: заголовок — одна строка, «##» и название на следующей — не заголовок (ревью Codex на #88).
const heading = (title) => new RegExp(String.raw`^#{2,4}[ \t]+(?:\d+\.[ \t]*)?${title}[ \t]*\r?$`, 'm');
const RULES = heading('Правила продукта — сверка');
const HOW_MEASURED = heading('Как мерил');
const MEASURED = /замер|перемер/i;

/** Чего не хватает в спеке: раздела сверки с правилами и, если в ней есть замер, раздела «Как мерил». */
export function specProblems(text) {
  const problems = [];
  if (!RULES.test(text)) problems.push('нет раздела «Правила продукта — сверка»');
  if (MEASURED.test(text) && !HOW_MEASURED.test(text)) problems.push('есть замер или перемер, нет раздела «Как мерил»');
  return problems;
}

function git(args) {
  const run = spawnSync('git', args, { encoding: 'utf8' });
  if (run.error) throw run.error;
  if (run.status !== 0) throw new Error(`git ${args.join(' ')}: ${run.stderr.trim()}`);
  return run.stdout.trim();
}

function main(argv) {
  const base = argv[0] ?? 'origin/main';
  const top = git(['rev-parse', '--show-toplevel']);
  const files = git(['-C', top, 'diff', '--name-only', '--diff-filter=AMR', `${base}...HEAD`, '--', 'docs/superpowers/specs/*-design.md'])
    .split('\n').filter(Boolean);
  if (files.length === 0) {
    console.log(`Изменённых спек *-design.md относительно ${base} нет.`);
    return true;
  }
  let ok = true;
  for (const file of files) {
    const problems = specProblems(readFileSync(join(top, file), 'utf8'));
    console.log(`${problems.length ? 'НЕТ' : 'да '}  ${file}${problems.length ? ` — ${problems.join('; ')}` : ''}`);
    if (problems.length) ok = false;
  }
  return ok;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main(process.argv.slice(2)) ? 0 : 1;
  } catch (e) {
    console.error(e.message);
    process.exitCode = 2;
  }
}

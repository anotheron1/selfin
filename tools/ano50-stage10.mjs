#!/usr/bin/env node
/**
 * ANO-50, этап 10 — сквозная согласованность.
 *
 * Проверяет не функции, а то, что все экраны рассказывают одну и ту же историю.
 * Зона критического ущерба: расходящиеся числа убивают доверие насовсем, и чинить
 * их дороже, чем неверный расчёт — неверный виден, расходящийся нет.
 *
 *   node tools/ano50-stage10.mjs                 # блоки 10.1–10.3, только чтение
 *   node tools/ano50-stage10.mjs --mutate        # плюс 10.4–10.6, МЕНЯЕТ ДАННЫЕ
 *
 * Без --mutate не пишет ничего. С --mutate записывает факт, ставит якорь и делает
 * перевод в копилку, каждый раз обходя все экраны, — и в конце откатывает за собой.
 */

const args = process.argv.slice(2);
const API = (args.includes('--api') ? args[args.indexOf('--api') + 1] : 'http://localhost:8081/api/v1').replace(/\/$/, '');
const MUTATE = args.includes('--mutate');
const EPS = 0.005;

const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && Math.abs(a - b) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }));

const results = [];
const add = (level, block, message, data) => results.push({ level, block, message, data: data ?? null });
const fail = (...a) => add('РАСХОЖДЕНИЕ', ...a);
const warn = (...a) => add('СМОТРЕТЬ', ...a);
const ok = (...a) => add('СОШЛОСЬ', ...a);

async function api(path, init) {
  const res = await fetch(`${API}${path}`, init);
  const text = await res.text();
  if (!res.ok) throw new Error(`${path} → ${res.status}: ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}

const today = new Date().toISOString().slice(0, 10);
const thisMonth = today.slice(0, 7);

/** Шесть источников остатка (блок 10.1). */
async function collectBalances() {
  const [pocket, accounts, capital, strategy, wishlist] = await Promise.all([
    api('/pocket'), api('/accounts'), api('/capital/summary'),
    api('/strategy/timeline'), api('/wishlist/simulation'),
  ]);

  const starting = pocket.breakdown.find((b) => b.type === 'STARTING_BALANCE');
  // Экран счетов показывает остатки карточками; «остаток» = сумма денежных
  // отслеживаемых счетов, то есть то, чем реально можно платить.
  const cashAccounts = accounts.filter((a) => a.trackBalance && (a.kind === 'DEBIT' || a.kind === 'CASH'));
  const accountsSum = cashAccounts.reduce((s, a) => s + (num(a.balance) ?? 0), 0);
  const stratNow = strategy.points.find((p) => p.yearMonth === thisMonth);

  return {
    pocket: { label: 'Дашборд — строка «Остаток на счёте»', value: num(starting?.amount) },
    pocketField: { label: 'Дашборд — поле currentBalance', value: num(pocket.currentBalance) },
    accounts: {
      label: `Счета — сумма денежных (${cashAccounts.map((a) => a.name).join(' + ')})`,
      value: accountsSum,
    },
    capital: { label: 'Капитал — ликвид', value: num(capital.liquid) },
    strategy: { label: `Стратегия — баланс текущего месяца (${thisMonth})`, value: num(stratNow?.balance) },
    wishlist: { label: 'Хотелки — ограничение currentCapital', value: num(wishlist.constraints?.currentCapital) },
    _raw: { pocket, accounts, capital, strategy, wishlist },
  };
}

function check101(b) {
  const rows = ['pocket', 'pocketField', 'accounts', 'capital', 'strategy', 'wishlist'].map((k) => b[k]);
  const base = b.pocket.value;
  const diffs = rows.filter((r) => !eq(r.value, base));

  console.log('\n10.1 · Остаток на шести экранах');
  console.log('─'.repeat(78));
  for (const r of rows) {
    const mark = eq(r.value, base) ? '  ' : ' ≠';
    console.log(`${mark} ${String(money(r.value)).padStart(14)}  ${r.label}`);
  }

  if (diffs.length === 0) {
    ok('10.1', 'шесть чисел совпали');
    return;
  }
  for (const d of diffs) {
    const delta = d.value - base;
    // Вклад в ликвиде — законное расхождение по ANO-46: полу-ликвид виден только
    // на экране Капитала. Всё прочее расхождение объяснения не имеет.
    const deposits = b._raw.accounts.filter((a) => a.kind === 'DEPOSIT' && a.trackBalance)
      .reduce((s, a) => s + (num(a.balance) ?? 0), 0);
    if (d.label.includes('Капитал') && eq(delta, deposits) && deposits > 0) {
      warn('10.1', `ликвид Капитала больше остатка ровно на сумму вкладов — так и задумано (ANO-46)`,
        { разница: delta, вклады: deposits });
    } else {
      fail('10.1', `${d.label}: ${money(d.value)} против ${money(base)}`, { разница: delta });
    }
  }
}

function check102(b) {
  const pocket = b._raw.pocket;
  const planned = pocket.breakdown.find((x) => x.type === 'PLANNED_EXPENSES');
  console.log('\n10.2 · Плановые расходы месяца на трёх экранах');
  console.log('─'.repeat(78));
  console.log(`   ${String(money(planned ? -planned.amount : 0)).padStart(14)}  Кармашек — «${planned?.label ?? 'строки нет'}»`);
  console.log('   (Бюджет и Аналитика считают ПОЛНЫЙ месяц, кармашек — только до дня минимума,');
  console.log(`    то есть до ${pocket.minPoint?.date}. Совпадения не ждём, ждём объяснимого расхождения.)`);
  warn('10.2', 'сравнение требует границ периода — снимается вместе с Бюджетом и Аналитикой на экране',
    { кармашекДо: pocket.minPoint?.date, сумма: planned ? -planned.amount : 0 });
}

function check103(b) {
  const credits = b._raw.accounts.filter((a) => a.kind === 'CREDIT');
  const byCard = credits.map((a) => ({ карта: a.name, долг: num(a.debt) ?? 0 }));
  const sumCards = byCard.reduce((s, x) => s + x.долг, 0);

  const items = (b._raw.capital.items ?? []).filter((i) => i.kind === 'LIABILITY' && !i.isArchived);
  const sumItems = items.reduce((s, i) => s + (num(i.currentValue) ?? 0), 0);
  const total = num(b._raw.capital.liabilitiesTotal);
  const creditPart = total - sumItems;

  console.log('\n10.3 · Обязательства на двух экранах');
  console.log('─'.repeat(78));
  byCard.forEach((x) => console.log(`   ${String(money(x.долг)).padStart(14)}  ${x.карта}`));
  console.log(`   ${String(money(sumCards)).padStart(14)}  сумма по карточкам счетов`);
  console.log(`   ${String(money(creditPart)).padStart(14)}  кредитная часть обязательств Капитала`);

  if (eq(sumCards, creditPart)) ok('10.3', 'долг по картам и кредитная часть капитала совпали', { сумма: sumCards });
  else fail('10.3', 'долг по картам не равен кредитной части обязательств',
    { поКарточкам: sumCards, вКапитале: creditPart, разница: sumCards - creditPart });
}

/** Снимок всех наблюдаемых чисел — для метаморфических блоков 10.4–10.6. */
async function snapshot() {
  const b = await collectBalances();
  const p = b._raw.pocket, c = b._raw.capital;
  return {
    'кармашек': num(p.pocket),
    'минимум': num(p.minPoint?.balance),
    'датаМинимума': p.minPoint?.date,
    'остаток': num(p.currentBalance),
    'ликвид': num(c.liquid),
    'капиталИтого': num(c.total),
    'обязательства': num(c.liabilitiesTotal),
    'стратегияТекМесяц': b.strategy.value,
    'хотелкиОграничение': b.wishlist.value,
    'второеЧисло': num(p.pocketAfterCreditRestore),
    'третьеЧисло': num(p.pocketWithDeposits),
  };
}

function compare(block, before, after, expected) {
  console.log(`\n${block}`);
  console.log('─'.repeat(78));
  console.log(`   ${'показатель'.padEnd(22)}${'до'.padStart(14)}${'после'.padStart(14)}${'сдвиг'.padStart(14)}`);
  const moved = {};
  for (const k of Object.keys(before)) {
    const a = before[k], z = after[k];
    if (typeof a !== 'number') {
      if (a !== z) console.log(`   ${k.padEnd(22)}${String(a).padStart(14)}${String(z).padStart(14)}${'изменилась'.padStart(14)}`);
      continue;
    }
    const d = z - a;
    moved[k] = d;
    console.log(`   ${k.padEnd(22)}${String(money(a)).padStart(14)}${String(money(z)).padStart(14)}${String(d === 0 ? '—' : money(d)).padStart(14)}`);
  }
  for (const [k, want] of Object.entries(expected)) {
    const got = moved[k];
    if (got === undefined) continue;
    if (eq(got, want)) ok(block, `${k} сдвинулся на ${money(want)}, как и ожидалось`);
    else fail(block, `${k}: ожидался сдвиг ${money(want)}, получен ${money(got)}`, { разница: got - want });
  }
  return moved;
}

async function main() {
  console.log(`\nANO-50 · этап 10 «сквозная согласованность» · ${API} · ${today}`);
  console.log(MUTATE ? 'режим: С МУТАЦИЯМИ (данные будут изменены и откатаны)' : 'режим: только чтение');

  const b = await collectBalances();
  check101(b);
  check102(b);
  check103(b);

  if (MUTATE) {
    const before = await snapshot();

    // 10.4 — запись факта на 5 000. Дата ЗАВТРА: факт в день якоря не учитывается
    // (ANO-82), и блок мерил бы не распространение изменения, а этот дефект.
    const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
    // Факт создаётся отдельным эндпоинтом: POST /events принимает только план,
    // ни factAmount, ни eventKind в его DTO нет.
    const cat = (await api('/categories')).find((c) => c.type === 'EXPENSE' && !c.deleted);
    const created = await api('/events/facts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        date: tomorrow, type: 'EXPENSE', factAmount: 5000,
        categoryId: cat.id, description: 'ANO-50 этап 10, будет удалено',
      }),
    });
    const afterFact = await snapshot();
    compare('10.4 · Пересчёт после записи факта 5 000 (завтра)', before, afterFact, {
      'кармашек': -5000, 'остаток': -5000, 'ликвид': -5000, 'капиталИтого': -5000,
    });

    await api(`/events/${created.id}`, { method: 'DELETE' });
    const restored = await snapshot();
    if (eq(restored['кармашек'], before['кармашек'])) ok('10.4', 'откат вернул кармашек в исходное');
    else fail('10.4', 'после удаления факта кармашек не вернулся',
      { было: before['кармашек'], стало: restored['кармашек'] });
  }

  console.log(`\n${'═'.repeat(78)}`);
  const bad = results.filter((r) => r.level === 'РАСХОЖДЕНИЕ');
  const look = results.filter((r) => r.level === 'СМОТРЕТЬ');
  for (const r of [...bad, ...look]) {
    console.log(`${r.level} [${r.block}] ${r.message}`);
    if (r.data) console.log(`    ${JSON.stringify(r.data)}`);
  }
  console.log(bad.length === 0
    ? `\nРасхождений нет. Требует глаз: ${look.length}. Сошлось: ${results.filter((r) => r.level === 'СОШЛОСЬ').length}.`
    : `\nРАСХОЖДЕНИЙ: ${bad.length}. Требует глаз: ${look.length}.`);
  console.log('');
  process.exit(bad.length ? 1 : 0);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

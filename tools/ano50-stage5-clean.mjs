#!/usr/bin/env node
/**
 * ANO-50, этап 5 — четыре блока, которым нужен чистый стенд: 5.15, 5.18, 5.14, 5.21.
 *
 * Стенд между фазами пересоздаётся снаружи (DROP/CREATE базы, флайвей поднимает пустую),
 * потому что «пустые данные» и «фиксированный набор» несовместимы в одном прогоне.
 *
 *   node tools/ano50-stage5-clean.mjs --phase empty      # 5.15, на только что созданной базе
 *   node tools/ano50-stage5-clean.mjs --phase fixed      # 5.18 и 5.14, на той же пустой базе
 *   node tools/ano50-stage5-clean.mjs --phase midmonth   # 5.21, снова на пустой базе
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && a !== undefined && b !== undefined
  && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const iso = (d) => d.toISOString().slice(0, 10);
const today = iso(new Date());
const shift = (n) => iso(new Date(Date.now() + n * 86400000));

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  →  ', 'НЕЛЬЗЯ': '  x  ' }[verdict];
  console.log(`${mark}${block}  ${message}`);
  if (data && verdict !== 'СОШЛОСЬ') console.log(`        ${JSON.stringify(data)}`);
};

async function api(path, init) {
  const res = await fetch(`${API}${path}`, init);
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { status: res.status, ok: res.ok, body };
}
async function must(p, i) {
  const r = await api(p, i);
  if (!r.ok) throw new Error(`${p} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 250)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));

/** Все экраны продукта одним списком: на пустых данных ни один не имеет права упасть. */
const SCREENS = [
  ['дашборд-кармашек', '/pocket'],
  ['дашборд-аналитика', '/analytics/dashboard'],
  ['аналитика-отчёт', '/analytics/report'],
  ['счета', '/accounts'],
  ['категории', '/categories'],
  ['журнал', `/events?startDate=${today}&endDate=${shift(60)}`],
  ['якоря', '/balance-checkpoints'],
  ['копилки', '/funds'],
  ['копилки-планировщик', '/funds/planner'],
  ['капитал-итого', '/capital/summary'],
  ['капитал-строки', '/capital/items'],
  ['капитал-траектория', '/capital/trajectory'],
  ['стратегия', '/strategy/timeline'],
  ['хотелки-примерка', '/wishlist/simulation'],
  ['настройки-кармашка', '/settings/pocket'],
  ['настройки-хотелок', '/settings/wishlist'],
];

async function phaseEmpty() {
  console.log('\n5.15 · кармашек и все экраны на пустых данных');
  console.log('─'.repeat(78));
  const упали = [];
  for (const [имя, путь] of SCREENS) {
    const r = await api(путь);
    const пусто = r.ok && (Array.isArray(r.body) ? r.body.length === 0 : true);
    console.log(`   ${String(r.status).padStart(3)}  ${имя.padEnd(24)} ${r.ok ? (Array.isArray(r.body) ? `${r.body.length} записей` : 'ответ есть') : JSON.stringify(r.body).slice(0, 90)}`);
    if (!r.ok) упали.push({ экран: имя, путь, статус: r.status, тело: String(JSON.stringify(r.body)).slice(0, 140) });
    void пусто;
  }
  упали.length
    ? record('5.15', 'РАСХОЖДЕНИЕ', `${упали.length} экранов из ${SCREENS.length} не отвечают на пустых данных`, { упали })
    : record('5.15', 'СОШЛОСЬ', `все ${SCREENS.length} экранов отвечают на пустых данных, пятисоток нет`);

  const p = await must('/pocket');
  const t = byType(p);
  eq(num(p.pocket), 0)
    ? record('5.15', 'СОШЛОСЬ', 'кармашек на пустоте — ноль, а не ошибка')
    : record('5.15', 'СМОТРЕТЬ', 'кармашек на пустоте не ноль', { кармашек: num(p.pocket) });
  p.checkpointDate === null
    ? record('5.15', 'СОШЛОСЬ', 'якоря нет и поле честно пустое — есть за что зацепить подсказку «поставьте остаток»')
    : record('5.15', 'СМОТРЕТЬ', 'на пустой базе уже есть якорь', { якорь: p.checkpointDate });
  p.horizon?.fallback === true
    ? record('5.15', 'СОШЛОСЬ', `горизонт честно помечен фолбэком: «${p.horizon.label}», конец ${p.horizon.endDate}`)
    : record('5.15', 'РАСХОЖДЕНИЕ', 'плановых доходов нет, а горизонт не помечен фолбэком', { горизонт: p.horizon });
  record('5.15', 'СМОТРЕТЬ', 'что именно отдаёт API новому пользователю — из этого строится пустое состояние',
    { кармашек: num(p.pocket), строкиРазбивки: Object.keys(t), точекТраектории: (p.trajectory ?? []).length, подпись: p.horizon?.label });
  const cats = await must('/categories');
  record('5.15', 'СМОТРЕТЬ', `новому пользователю засеяно категорий: ${cats.length}`,
    { сПрогнозом: cats.filter((c) => c.forecastEnabled).map((c) => c.name), доходные: cats.filter((c) => c.type === 'INCOME').map((c) => c.name) });
  record('5.15', 'РУКАМИ', 'есть ли на экране внятное пустое состояние и подсказка, что делать первым');
}

async function phaseFixed() {
  console.log('\n5.18 · сценарий провала на фиксированных данных');
  console.log('─'.repeat(78));
  const cats = await must('/categories');
  const isPrimary = (c) => Boolean(c.primaryIncome ?? c.isPrimaryIncome);
  const salary = cats.find((c) => c.type === 'INCOME' && isPrimary(c)) ?? cats.find((c) => c.type === 'INCOME');
  const exp = cats.find((c) => c.type === 'EXPENSE');

  let accounts = await must('/accounts');
  if (!accounts.length) {
    await must('/accounts', send('POST', { name: 'Карта', kind: 'DEBIT' }));
    accounts = await must('/accounts');
  }
  const acc = accounts.find((a) => a.isDefault) ?? accounts[0];

  // Бумажный эталон: шесть строк, всё считается в уме.
  // 80 000 → −60 000 (5.09) → −8 000 (10.09) → −15 000 (12.09) → +100 000 (20.09) → −20 000 (25.09)
  //   =  20 000 → 12 000 → −3 000 → 97 000 → 77 000.  Минимум −3 000 двенадцатого.
  await must('/balance-checkpoints', send('POST', { date: '2026-09-01', amount: 80000, accountId: acc.id }));
  const набор = [
    ['2026-09-05', 'EXPENSE', 60000, 'Аренда'],
    ['2026-09-10', 'EXPENSE', 8000, 'Коммуналка'],
    ['2026-09-12', 'EXPENSE', 15000, 'Кредит'],
    ['2026-09-20', 'INCOME', 100000, 'Зарплата'],
    ['2026-09-25', 'EXPENSE', 20000, 'Страховка'],
  ];
  for (const [date, type, amount, description] of набор) {
    await must('/events', send('POST', {
      date, type, plannedAmount: amount, description,
      categoryId: type === 'INCOME' ? salary.id : exp.id,
    }));
  }

  const p = await must('/pocket');
  const t = byType(p);
  console.log(`   горизонт ${p.horizon.endDate} «${p.horizon.label}», остаток ${money(p.currentBalance)}`);
  p.breakdown.forEach((l) => console.log(`   ${String(money(l.amount)).padStart(12)}  ${l.type}  ${l.label}`));

  eq(num(p.pocket), -3000)
    ? record('5.18', 'СОШЛОСЬ', 'кармашек −3 000 — показан минимум траектории, а не остаток на конец периода')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'кармашек не равен −3 000',
      { кармашек: num(p.pocket), ожидалось: -3000, остатокНаКонец: 77000, строки: t });
  p.minPoint?.date === '2026-09-12'
    ? record('5.18', 'СОШЛОСЬ', 'дата минимума — двенадцатое сентября')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'дата минимума не двенадцатое', { минимум: p.minPoint });
  eq(num(p.minPoint?.balance), -3000)
    ? record('5.18', 'СОШЛОСЬ', 'минимум траектории −3 000')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'минимум траектории не −3 000', { минимум: num(p.minPoint?.balance) });
  p.minPoint?.drivenBy === 'Кредит'
    ? record('5.18', 'СОШЛОСЬ', 'виновник назван верно: «Кредит»')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'виновник провала назван неверно или не назван',
      { drivenBy: p.minPoint?.drivenBy ?? null, ожидалось: 'Кредит' });
  // Провал — это НЕ 77 000. Проверяем, что число конца периода нигде не выдаётся за ответ.
  !eq(num(p.pocket), 77000)
    ? record('5.18', 'СОШЛОСЬ', 'остаток на конец месяца 77 000 не выдаётся за ответ')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'показан остаток на конец периода — продукт не отличается от банковского приложения');

  const pMonth = await must('/pocket?scope=MONTHS%3A1');
  const конец = (pMonth.trajectory ?? []).slice(-1)[0];
  record('5.18', 'СМОТРЕТЬ', `на скоупе «1 месяц» траектория заканчивается на ${конец?.date} балансом ${money(конец?.balance)}, а кармашек ${money(pMonth.pocket)}`,
    { минимум: pMonth.minPoint });
  const день12 = (pMonth.trajectory ?? []).find((x) => x.date === '2026-09-12');
  eq(num(день12?.balance), -3000)
    ? record('5.18', 'СОШЛОСЬ', 'календарь-близнец на 12.09 показывает те же −3 000')
    : record('5.18', 'РАСХОЖДЕНИЕ', 'в траектории 12.09 не −3 000', { точка: день12 ?? null });
  record('5.18', 'РУКАМИ', 'фраза-ответ дословно, тон, выделено ли 12.09 в календаре, что показывает стратегия');

  // ── 5.14 фолбэк горизонта: убрать единственный доход ────────────────────
  console.log('\n5.14 · фолбэк горизонта');
  console.log('─'.repeat(78));
  const incomes = (await must(`/events?startDate=${today}&endDate=${shift(400)}`))
    .filter((e) => !e.deleted && e.type === 'INCOME');
  for (const e of incomes) await api(`/events/${e.id}`, { method: 'DELETE' });
  const pf = await must('/pocket');
  pf.horizon.fallback === true
    ? record('5.14', 'СОШЛОСЬ', 'горизонт помечен фолбэком')
    : record('5.14', 'РАСХОЖДЕНИЕ', 'плановых доходов нет, а флаг фолбэка не поднят', { горизонт: pf.horizon });
  pf.horizon.endDate === shift(30)
    ? record('5.14', 'СОШЛОСЬ', `горизонт откатился на 30 дней, до ${pf.horizon.endDate}`)
    : record('5.14', 'РАСХОЖДЕНИЕ', 'горизонт откатился не на 30 дней',
      { горизонт: pf.horizon.endDate, ожидалось: shift(30) });
  /доход/i.test(pf.horizon.label)
    ? record('5.14', 'СОШЛОСЬ', `подпись говорит о доходах прямо: «${pf.horizon.label}»`)
    : record('5.14', 'РАСХОЖДЕНИЕ', 'подпись не объясняет, почему горизонт такой — человек не узнает, что доходов нет',
      { подпись: pf.horizon.label });
  record('5.14', 'СМОТРЕТЬ', `кармашек на фолбэке ${money(pf.pocket)}, минимум ${money(pf.minPoint?.balance)} на ${pf.minPoint?.date}`,
    { строки: byType(pf) });

  // Доход дальше 92 дней «ближайшим» не считается — вторая половина оракула блока.
  const далеко = shift(120);
  await must('/events', send('POST', {
    date: далеко, type: 'INCOME', plannedAmount: 100000, categoryId: salary.id, description: 'Далёкий доход',
  }));
  const pFar = await must('/pocket');
  pFar.horizon.fallback === true && pFar.horizon.endDate !== далеко
    ? record('5.14', 'СОШЛОСЬ', `доход через 120 дней не считается ближайшим, горизонт остался ${pFar.horizon.endDate}`)
    : record('5.14', 'РАСХОЖДЕНИЕ', 'горизонт уехал на доход дальше 92 дней',
      { горизонт: pFar.horizon.endDate, доход: далеко, фолбэк: pFar.horizon.fallback });
}

async function phaseMidMonth() {
  console.log('\n5.21 · вход в середине месяца');
  console.log('─'.repeat(78));
  const cats = await must('/categories');
  const isPrimary = (c) => Boolean(c.primaryIncome ?? c.isPrimaryIncome);
  const salary = cats.find((c) => c.type === 'INCOME' && isPrimary(c)) ?? cats.find((c) => c.type === 'INCOME');
  const exp = cats.find((c) => c.type === 'EXPENSE');
  let accounts = await must('/accounts');
  if (!accounts.length) {
    await must('/accounts', send('POST', { name: 'Карта', kind: 'DEBIT' }));
    accounts = await must('/accounts');
  }
  const acc = accounts.find((a) => a.isDefault) ?? accounts[0];

  // Якорь семнадцатым числом прошлого месяца: человек поставил приложение не первого.
  const anchor = '2026-08-17';
  const r = await api('/balance-checkpoints', send('POST', { date: anchor, amount: 50000, accountId: acc.id }));
  r.ok
    ? record('5.21', 'СОШЛОСЬ', `якорь серединой месяца (${anchor}) принят`)
    : record('5.21', 'РАСХОЖДЕНИЕ', 'якорь серединой месяца отвергнут', { статус: r.status, тело: r.body });

  for (const [date, type, amount, description] of [
    ['2026-08-20', 'EXPENSE', 6000, 'Продукты половины августа'],
    ['2026-08-28', 'EXPENSE', 4000, 'Транспорт'],
    ['2026-09-15', 'INCOME', 90000, 'Зарплата'],
    ['2026-09-18', 'EXPENSE', 30000, 'Аренда'],
  ]) {
    await must('/events', send('POST', {
      date, type, plannedAmount: amount, description, categoryId: type === 'INCOME' ? salary.id : exp.id,
    }));
  }

  const p = await must('/pocket');
  const t = byType(p);
  console.log(`   якорь ${p.checkpointDate}, остаток ${money(p.currentBalance)}, горизонт ${p.horizon.endDate} «${p.horizon.label}»`);
  p.breakdown.forEach((l) => console.log(`   ${String(money(l.amount)).padStart(12)}  ${l.type}  ${l.label}`));
  Number.isFinite(num(p.pocket))
    ? record('5.21', 'СОШЛОСЬ', `кармашек считается при входе не первого числа: ${money(p.pocket)}`)
    : record('5.21', 'РАСХОЖДЕНИЕ', 'кармашек не посчитался', { ответ: p });
  p.horizon.endDate === '2026-09-15'
    ? record('5.21', 'СОШЛОСЬ', 'горизонт встал на ближайшую зарплату 15.09')
    : record('5.21', 'СМОТРЕТЬ', 'горизонт встал не на зарплату', { горизонт: p.horizon });

  t.UNPLANNED_FORECAST === undefined
    ? record('5.21', 'НЕЛЬЗЯ', 'окно размазки прогноза не проверить: после V21 прогноз незапланированных выключен у всех категорий')
    : record('5.21', 'СМОТРЕТЬ', 'строка прогноза есть — проверить окно размазки', { прогноз: t.UNPLANNED_FORECAST });

  for (const [имя, путь] of [
    ['аналитика августа', '/analytics/report?date=2026-08-20'],
    ['аналитика сентября', '/analytics/report?date=2026-09-05'],
    ['дашборд', '/analytics/dashboard'],
    ['стратегия', '/strategy/timeline'],
  ]) {
    const res = await api(путь);
    res.ok
      ? record('5.21', 'СОШЛОСЬ', `${имя} отвечает на неполном месяце`)
      : record('5.21', 'РАСХОЖДЕНИЕ', `${имя} падает на неполном месяце`, { статус: res.status, тело: String(JSON.stringify(res.body)).slice(0, 140) });
  }
  const augReport = await api('/analytics/report?date=2026-08-20');
  if (augReport.ok) {
    record('5.21', 'СМОТРЕТЬ', 'что показывает аналитика месяца входа — честный неполный месяц или провал',
      { секции: Object.keys(augReport.body) });
  }
  record('5.21', 'РУКАМИ', 'читается ли аналитика месяца входа как неполная, а не как провал');
}

async function main() {
  const phase = process.argv[process.argv.indexOf('--phase') + 1];
  console.log(`\nANO-50 · этап 5, чистый стенд · фаза «${phase}» · ${today}`);
  console.log('='.repeat(78));
  if (phase === 'empty') await phaseEmpty();
  else if (phase === 'fixed') await phaseFixed();
  else if (phase === 'midmonth') await phaseMidMonth();
  else throw new Error('нужен --phase empty|fixed|midmonth');

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   Расхождений: ${bad.length}   `
    + `Требует глаз: ${look.length}   Невыполнимо: ${log.filter((r) => r.verdict === 'НЕЛЬЗЯ').length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  if (look.length) {
    console.log('\nТРЕБУЕТ ГЛАЗ:');
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}`));
  }
  console.log('');
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

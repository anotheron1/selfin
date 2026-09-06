#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапу 9 — стратегия и аналитика.
 *
 * Экраны длинного горизонта и разбора прошлого. Считаются по своим правилам, значит
 * могут расходиться с кармашком — здесь это и проверяется.
 *
 * Три открытые задачи проверяются на регресс:
 *   ANO-47 — мостик в аналитике считает факты по своему правилу
 *   ANO-25 — факты через PATCH не попадали в разбор стратегии
 *   ANO-32 — цвета план-факта по доходу красили наоборот
 *
 *   node tools/ano50-stage9.mjs        # прогон, МЕНЯЕТ ДАННЫЕ и убирает за собой
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && a !== undefined && b !== undefined
  && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
// toISOString уводит локальную полночь на день назад (MSK = UTC+3), и последний день
// месяца выпадает из сравнений. Форматируем из локальных компонент.
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const today = iso(new Date());
const thisMonth = today.slice(0, 7);
const shift = (n) => iso(new Date(Date.now() + n * 86400000));
const monthStart = `${thisMonth}-01`;
const monthEnd = (() => { const d = new Date(); d.setMonth(d.getMonth() + 1, 0); return iso(d); })();
const MARK = 'ANO-50';

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  ->  ', 'НЕЛЬЗЯ': '  x  ' }[verdict];
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
  if (!r.ok) throw new Error(`${p} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 300)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});

const pocketOf = (scope) => must(`/pocket${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`);
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));
const strategy = () => must('/strategy/timeline');
const events = (from, to) => must(`/events?startDate=${from}&endDate=${to}`).then((r) => r.filter((e) => !e.deleted));
const del = async (id) => {
  const r = await api(`/events/${id}`, { method: 'DELETE' });
  return r.ok ? r : api(`/events/${id}?scope=ALL`, { method: 'DELETE' });
};

async function main() {
  console.log(`\nANO-50 · первый проход, этап 9 — стратегия и аналитика · ${today}`);
  console.log('='.repeat(78));

  const made = [];
  const p0 = await pocketOf();
  const cats = await must('/categories');
  const expCat = cats.find((c) => c.name === 'Продукты') ?? cats.find((c) => c.type === 'EXPENSE');
  console.log(`старт: кармашек ${money(p0.pocket)}, остаток ${money(p0.currentBalance)}\n`);

  // ── 9.1 стартовая точка кассового графика ────────────────────────────────
  {
    const s = await strategy();
    const now = (s.points ?? []).find((p) => String(p.yearMonth) === thisMonth);
    const accounts = await must('/accounts');
    const deposits = accounts.filter((a) => a.kind === 'DEPOSIT' && a.trackBalance)
      .reduce((acc, a) => acc + (num(a.balance) ?? 0), 0);

    if (!now) {
      record('9.1', 'РАСХОЖДЕНИЕ', `в стратегии нет точки текущего месяца ${thisMonth}`,
        { месяцы: (s.points ?? []).map((p) => String(p.yearMonth)).slice(0, 6) });
    } else {
      eq(num(now.balance), num(p0.currentBalance))
        ? record('9.1', 'СОШЛОСЬ', `стартовая точка стратегии = остаток кармашка: ${money(now.balance)}`)
        : record('9.1', 'РАСХОЖДЕНИЕ', 'стартовая точка стратегии не равна остатку кармашка',
          { стратегия: num(now.balance), кармашек: num(p0.currentBalance),
            разница: num(now.balance) - num(p0.currentBalance), вклады: deposits });
      // ANO-46: вклад в кассовый график входить не должен.
      deposits > 0 && eq(num(now.balance) - num(p0.currentBalance), deposits)
        ? record('9.1', 'РАСХОЖДЕНИЕ', 'кассовый график завышен ровно на сумму вкладов — регресс ANO-46',
          { вклады: deposits })
        : record('9.1', 'СОШЛОСЬ', `вклад ${money(deposits)} в кассовый график не протёк (ANO-46 не воспроизводится)`);
    }
    record('9.2', 'РУКАМИ', 'переключатели слоёв: появляются и исчезают, числа не меняются — только на экране');
    record('9.5', 'РУКАМИ', 'синхронное наведение между двумя графиками — только на экране');
  }

  // ── 9.3 состав подсказки против журнала ──────────────────────────────────
  {
    const s = await strategy();
    const pts = (s.points ?? []).filter((p) => p.breakdown);
    if (pts.length === 0) {
      record('9.3', 'СМОТРЕТЬ', 'ни в одной точке стратегии нет разбивки — подсказке нечего показывать',
        { точек: (s.points ?? []).length });
    } else {
      // берём месяц с самым крупным расходом — там подсказка важнее всего
      const worst = pts.reduce((m, p) => (num(p.expense) > num(m.expense) ? p : m));
      const ym = String(worst.yearMonth);
      const first = `${ym}-01`;
      const last = (() => { const [y, m] = ym.split('-').map(Number); return iso(new Date(y, m, 0)); })();
      const evs = await events(first, last);
      const expSum = evs.filter((e) => e.type === 'EXPENSE')
        .reduce((acc, e) => acc + (num(e.factAmount) ?? num(e.plannedAmount) ?? 0), 0);
      eq(num(worst.expense), expSum)
        ? record('9.3', 'СОШЛОСЬ', `расход месяца ${ym} в стратегии = сумме событий журнала: ${money(expSum)}`)
        : record('9.3', 'РАСХОЖДЕНИЕ', `расход месяца ${ym} в стратегии не равен сумме событий журнала`,
          { стратегия: num(worst.expense), журнал: expSum, разница: num(worst.expense) - expSum, событий: evs.length });
      record('9.3', 'СМОТРЕТЬ', `состав разбивки точки ${ym} — сверить категории глазами`,
        { разбивка: worst.breakdown });
    }
  }

  // ── 9.4 график капитала против экрана капитала ───────────────────────────
  {
    const s = await strategy();
    const now = (s.points ?? []).find((p) => String(p.yearMonth) === thisMonth);
    const cap = await must('/capital/summary');
    eq(num(now?.capital), num(cap.total))
      ? record('9.4', 'СОШЛОСЬ', `капитал на стратегии = итогу экрана капитала: ${money(cap.total)}`)
      : record('9.4', 'РАСХОЖДЕНИЕ', 'капитал на стратегии не равен итогу экрана капитала',
        { стратегия: num(now?.capital), капитал: num(cap.total), разница: num(now?.capital) - num(cap.total) });
    eq(num(now?.assets), num(cap.assetsTotal)) && eq(num(now?.liabilities), num(cap.liabilitiesTotal))
      ? record('9.4', 'СОШЛОСЬ', 'активы и обязательства тоже совпадают')
      : record('9.4', 'РАСХОЖДЕНИЕ', 'активы или обязательства разошлись',
        { стратегияАктивы: num(now?.assets), капиталАктивы: num(cap.assetsTotal),
          стратегияОбязательства: num(now?.liabilities), капиталОбязательства: num(cap.liabilitiesTotal) });
    // здравый смысл: обязательства в 234 миллиарда — это шрам данных, а не расчёт
    Math.abs(num(cap.liabilitiesTotal)) > 1e9
      ? record('9.4', 'СМОТРЕТЬ', `обязательства ${money(cap.liabilitiesTotal)} — величина нечеловеческая, похоже на шрам данных`,
        { итого: num(cap.total), активы: num(cap.assetsTotal) })
      : record('9.4', 'СОШЛОСЬ', 'порядок величин капитала правдоподобен');
  }

  // ── 9.6 зафиксированные хотелки на стратегии ─────────────────────────────
  {
    const before = await strategy();
    const targetDate = shift(45);
    const ym = targetDate.slice(0, 7);
    const balAt = (s) => num((s.points ?? []).find((p) => String(p.yearMonth) === ym)?.balance);
    const b0 = balAt(before);

    const wish = await must('/events/wishlist', send('POST', {
      description: `Хотелка на стратегию ${MARK}`, plannedAmount: 50000, date: targetDate,
    }));
    made.push(wish.id);
    const bOpen = balAt(await strategy());
    eq(bOpen, b0)
      ? record('9.6', 'СОШЛОСЬ', 'необсуждённая хотелка на стратегию не накладывается')
      : record('9.6', 'РАСХОЖДЕНИЕ', 'хотелка в статусе «обсуждается» уже двигает стратегию',
        { было: b0, стало: bOpen, сдвиг: bOpen - b0 });

    await must(`/wishlist/items/${wish.id}/fix`, send('POST', {
      sourceKind: 'WISHLIST', amount: 50000, date: targetDate, stretchMonths: 0,
    }));
    const bFixed = balAt(await strategy());
    eq(bFixed - b0, -50000)
      ? record('9.6', 'СОШЛОСЬ', `зафиксированная хотелка легла на стратегию ровно один раз: ${money(bFixed - b0)}`)
      : record('9.6', 'РАСХОЖДЕНИЕ', 'зафиксированная хотелка легла на стратегию не один раз',
        { было: b0, стало: bFixed, сдвиг: bFixed - b0, суммаХотелки: 50000 });

    const conv = await api(`/wishlist/items/${wish.id}/convert`, send('POST', {
      sourceKind: 'WISHLIST', target: 'PLAN_EVENT',
    }));
    if (conv.ok && conv.body.convertedTo?.id) made.push(conv.body.convertedTo.id);
    const bConv = balAt(await strategy());
    eq(bConv, bFixed)
      ? record('9.6', 'СОШЛОСЬ', 'после конверсии стратегия не сдвинулась — задвоения нет')
      : record('9.6', 'РАСХОЖДЕНИЕ', 'конверсия сдвинула стратегию — хотелка наложилась поверх своего же события',
        { доКонверсии: bFixed, после: bConv, сдвиг: bConv - bFixed });

    for (const id of made.slice().reverse()) await del(id);
    made.length = 0;
    eq(balAt(await strategy()), b0)
      ? record('9.6', 'СОШЛОСЬ', 'уборка вернула стратегию к исходной')
      : record('9.6', 'СМОТРЕТЬ', 'стратегия не вернулась после уборки', { было: b0, стало: balAt(await strategy()) });
  }

  // ── 9.7 мультимесячная таблица против журнала (ANO-47) ───────────────────
  {
    const from = (() => { const d = new Date(); d.setMonth(d.getMonth() - 2, 1); return iso(d); })();
    const report = await must(`/analytics/multi-month?startDate=${from}&endDate=${monthEnd}`);
    const rows = report.rows ?? [];
    const catRows = rows.filter((r) => r.categoryType && r.label);
    if (catRows.length === 0) {
      record('9.7', 'СМОТРЕТЬ', 'в таблице нет строк по категориям', { строк: rows.length, месяцы: report.months });
    } else {
      const mismatches = [];
      for (const row of catRows) {
        const cat = cats.find((c) => c.name === row.label);
        if (!cat) continue;
        for (const v of row.values ?? []) {
          if (v.actual === null || v.actual === undefined) continue;
          const first = `${v.month}-01`;
          const [y, m] = v.month.split('-').map(Number);
          const last = iso(new Date(y, m, 0));
          const evs = await events(first, last);
          const factSum = evs.filter((e) => e.categoryId === cat.id && e.factAmount != null)
            .reduce((acc, e) => acc + num(e.factAmount), 0);
          if (!eq(num(v.actual), factSum)) {
            mismatches.push({ категория: row.label, месяц: v.month, аналитика: num(v.actual), журнал: factSum,
              разница: num(v.actual) - factSum });
          }
        }
      }
      mismatches.length === 0
        ? record('9.7', 'СОШЛОСЬ', `факты в таблице сходятся с журналом во всех проверенных клетках (${catRows.length} строк)`)
        : record('9.7', 'РАСХОЖДЕНИЕ', `таблица расходится с журналом в ${mismatches.length} клетках — материал ANO-47`,
          { первые: mismatches.slice(0, 5) });
    }
  }

  // ── 9.8 план-факт: знаки и запрет 2 продуктовых правил ───────────────────
  {
    const rep = await must(`/analytics/report`);
    const pf = rep.planFact;
    const dash = await must('/analytics/dashboard');
    const dashKeys = Object.keys(dash);
    // Запрет 2: отчёт отклонений не в состоянии по умолчанию.
    const hasPlanFactByDefault = dashKeys.some((k) => /planFact|deviation|отклонен/i.test(k));
    hasPlanFactByDefault
      ? record('9.8', 'РАСХОЖДЕНИЕ',
        'дашборд по умолчанию несёт отчёт отклонений — нарушение запрета 2 продуктовых правил',
        { поляДашборда: dashKeys })
      : record('9.8', 'СОШЛОСЬ', 'дашборд по умолчанию отчёт отклонений не несёт — запрет 2 соблюдён');

    const rowsAll = [...(pf?.incomeRows ?? []), ...(pf?.expenseRows ?? []), ...(pf?.rows ?? [])];
    if (rowsAll.length === 0) {
      record('9.8', 'СМОТРЕТЬ', 'в план-факте нет строк — проверить структуру', { ключи: Object.keys(pf ?? {}) });
    } else {
      const wrong = rowsAll.filter((r) => {
        const plan = num(r.planned ?? r.plannedAmount);
        const fact = num(r.actual ?? r.factAmount);
        const diff = num(r.difference ?? r.diff);
        if (plan === null || fact === null || diff === null) return false;
        return !eq(diff, fact - plan) && !eq(diff, plan - fact);
      });
      wrong.length === 0
        ? record('9.8', 'СОШЛОСЬ', `разница считается последовательно во всех ${rowsAll.length} строках план-факта`)
        : record('9.8', 'РАСХОЖДЕНИЕ', 'разница в план-факте считается по-разному в разных строках',
          { примеры: wrong.slice(0, 3) });
      record('9.8', 'РУКАМИ',
        'цвета: перерасход по расходу тревожный, доход БОЛЬШЕ плана — хороший, не тревожный (регресс ANO-32)');
    }
  }

  // ── 9.9 факты, записанные разными путями (ANO-25) ────────────────────────
  {
    const d = today;
    // путь 1: отдельный факт без плана
    const standalone = await must('/events/facts', send('POST', {
      date: d, categoryId: expCat.id, type: 'EXPENSE', factAmount: 4400, description: `Факт напрямую ${MARK}`,
    }));
    made.push(standalone.id);
    // путь 2: план, затем факт к нему через PATCH
    const plan = await must('/events', send('POST', {
      date: d, categoryId: expCat.id, type: 'EXPENSE', plannedAmount: 4400, description: `План под факт ${MARK}`,
    }));
    made.push(plan.id);
    await must(`/events/${plan.id}/fact`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ factAmount: 4400 }),
    });

    const seen = {};
    const evs = await events(d, d);
    seen.журнал = {
      напрямую: evs.some((e) => e.id === standalone.id && num(e.factAmount) === 4400),
      черезPATCH: evs.some((e) => e.id === plan.id && num(e.factAmount) === 4400),
    };

    const rep = await must(`/analytics/multi-month?startDate=${monthStart}&endDate=${monthEnd}`);
    const catRow = (rep.rows ?? []).find((r) => r.label === expCat.name);
    const cell = (catRow?.values ?? []).find((v) => v.month === thisMonth);
    seen.аналитика = { факт: num(cell?.actual) };

    const s = await strategy();
    const pt = (s.points ?? []).find((p) => String(p.yearMonth) === thisMonth);
    seen.стратегия = { расходМесяца: num(pt?.expense) };

    const bothInJournal = seen.журнал.напрямую && seen.журнал.черезPATCH;
    bothInJournal
      ? record('9.9', 'СОШЛОСЬ', 'оба факта видны в журнале')
      : record('9.9', 'РАСХОЖДЕНИЕ', 'в журнале виден не каждый факт', seen.журнал);

    // Ключевая проверка ANO-25: снять оба и посмотреть, на сколько просядут отчёты.
    const evsBefore = { аналитика: num(cell?.actual), стратегия: num(pt?.expense) };
    await del(standalone.id);
    const repNoStandalone = await must(`/analytics/multi-month?startDate=${monthStart}&endDate=${monthEnd}`);
    const cellNoStandalone = ((repNoStandalone.rows ?? []).find((r) => r.label === expCat.name)?.values ?? [])
      .find((v) => v.month === thisMonth);
    const sNoStandalone = await strategy();
    const ptNoStandalone = (sNoStandalone.points ?? []).find((p) => String(p.yearMonth) === thisMonth);

    const dAnalytics1 = evsBefore.аналитика - num(cellNoStandalone?.actual);
    const dStrategy1 = evsBefore.стратегия - num(ptNoStandalone?.expense);
    eq(dAnalytics1, 4400)
      ? record('9.9', 'СОШЛОСЬ', 'факт напрямую виден в аналитике: снятие уронило её на 4 400')
      : record('9.9', 'РАСХОЖДЕНИЕ', 'снятие факта напрямую сдвинуло аналитику не на 4 400', { сдвиг: dAnalytics1 });
    eq(dStrategy1, 4400)
      ? record('9.9', 'СОШЛОСЬ', 'факт напрямую виден в стратегии')
      : record('9.9', 'РАСХОЖДЕНИЕ', 'снятие факта напрямую сдвинуло стратегию не на 4 400', { сдвиг: dStrategy1 });

    await del(plan.id);
    const repEmpty = await must(`/analytics/multi-month?startDate=${monthStart}&endDate=${monthEnd}`);
    const cellEmpty = ((repEmpty.rows ?? []).find((r) => r.label === expCat.name)?.values ?? [])
      .find((v) => v.month === thisMonth);
    const sEmpty = await strategy();
    const ptEmpty = (sEmpty.points ?? []).find((p) => String(p.yearMonth) === thisMonth);
    const dAnalytics2 = num(cellNoStandalone?.actual) - num(cellEmpty?.actual);
    const dStrategy2 = num(ptNoStandalone?.expense) - num(ptEmpty?.expense);
    eq(dAnalytics2, 4400)
      ? record('9.9', 'СОШЛОСЬ', 'факт через PATCH виден в аналитике так же, как факт напрямую')
      : record('9.9', 'РАСХОЖДЕНИЕ', 'факт через PATCH в аналитике учтён иначе — материал ANO-47',
        { сдвигНапрямую: dAnalytics1, сдвигЧерезPATCH: dAnalytics2 });
    eq(dStrategy2, 4400)
      ? record('9.9', 'СОШЛОСЬ', 'факт через PATCH виден в стратегии — ANO-25 не воспроизводится')
      : record('9.9', 'РАСХОЖДЕНИЕ', 'факт через PATCH в стратегию не попал — регресс ANO-25',
        { сдвигНапрямую: dStrategy1, сдвигЧерезPATCH: dStrategy2 });
    made.length = 0;
  }

  // ── 9.10 прогноз незапланированных ───────────────────────────────────────
  {
    const t = byType(await pocketOf());
    const withForecast = cats.filter((c) => c.forecastEnabled);
    if (t.UNPLANNED_FORECAST === undefined) {
      record('9.10', 'СОШЛОСЬ',
        `строки прогноза нет: V21 выключила прогноз по умолчанию, категорий с включённым прогнозом ${withForecast.length}`,
        withForecast.length ? { категории: withForecast.map((c) => c.name) } : null);
      record('9.10', 'СМОТРЕТЬ',
        'задвоение прогноза с планом (ANO-41) на этих данных проверить нельзя — механизм выключен, вернуться после пересмотра предикта');
    } else {
      const p = await pocketOf();
      const line = (p.breakdown ?? []).find((l) => l.type === 'UNPLANNED_FORECAST');
      const names = line?.details ?? [];
      const plannedCats = new Set((await events(today, monthEnd))
        .filter((e) => e.plannedAmount != null).map((e) => e.categoryName));
      const overlap = names.filter((n) => plannedCats.has(n));
      overlap.length === 0
        ? record('9.10', 'СОШЛОСЬ', `прогноз ${money(t.UNPLANNED_FORECAST)} не пересекается с запланированными категориями`)
        : record('9.10', 'РАСХОЖДЕНИЕ', 'категории с планом попали и в прогноз — двойной счёт, регресс ANO-41',
          { пересечение: overlap, прогноз: t.UNPLANNED_FORECAST });
    }
  }

  record('9.11', 'РУКАМИ', 'свободное исследование стратегии и аналитики, сорок минут');

  // ── уборка ────────────────────────────────────────────────────────────────
  for (const id of made.slice().reverse()) await del(id);
  const all = await must(`/events?startDate=2020-01-01&endDate=2051-12-31`);
  for (const e of all.filter((x) => !x.deleted && (x.description ?? '').includes(MARK))) await del(e.id);
  const rest = (await must(`/events?startDate=2020-01-01&endDate=2051-12-31`))
    .filter((e) => !e.deleted && (e.description ?? '').includes(MARK));

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${look.length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  if (look.length) {
    console.log('\nТРЕБУЕТ ГЛАЗ:');
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}`));
  }
  console.log(`\nуборка: осталось с меткой ${MARK} — ${rest.length}`);
  const fin = await pocketOf();
  console.log(`кармашек на выходе: ${money(fin.pocket)} (на входе ${money(p0.pocket)})\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

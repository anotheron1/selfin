#!/usr/bin/env node
/**
 * ANO-50, хвосты этапа 10 — сквозная согласованность: блоки 10.2, 10.5, 10.6, 10.7.
 *
 * 10.1 и 10.3 закрыты раньше (ano50-stage10.mjs), 10.4 заблокирован ANO-82.
 *
 * ВНИМАНИЕ: блоки 10.5 и 10.6 меняют стенд необратимо — ре-якорь и перевод в копилку
 * назад не отматываются (перевод односторонний, @Positive на сумме). Перед прогоном
 * снять дамп, после — восстановить:
 *
 *   docker exec selfin-test-db pg_dump -U admin -d selfin > ДАМП
 *   node tools/ano50-stage10-tails.mjs
 *   bash tools/ano50-reset-stand.sh ДАМП
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && a !== undefined && b !== undefined
  && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
/**
 * Сегодня по СЕРВЕРУ, а не по машине. Стенд живёт в UTC, машина в MSK: после 21:00
 * локальная дата уходит на сутки вперёд, и тогда ре-якорь падает с 400 «date must be
 * in the past or in the present», а свежий факт создаётся за пределами окна кармашка.
 * День 0 траектории — это и есть сегодня глазами бэкенда.
 */
let today = iso(new Date());
let thisMonth = today.slice(0, 7);
let monthStart = `${thisMonth}-01`;
let monthEnd = (() => { const d = new Date(); d.setMonth(d.getMonth() + 1, 0); return iso(d); })();
async function syncToday() {
  const p = await must('/pocket');
  const serverToday = (p.trajectory ?? [])[0]?.date;
  if (!serverToday || serverToday === today) return;
  console.log(`  дата синхронизирована с сервером: было ${today}, стало ${serverToday}\n`);
  today = serverToday;
  thisMonth = today.slice(0, 7);
  monthStart = `${thisMonth}-01`;
  const [y, m] = thisMonth.split('-').map(Number);
  monthEnd = iso(new Date(y, m, 0));
}
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
const send = (method, body, key) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key ?? crypto.randomUUID() },
  body: JSON.stringify(body),
});
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));
const del = async (id) => {
  const r = await api(`/events/${id}`, { method: 'DELETE' });
  return r.ok ? r : api(`/events/${id}?scope=ALL`, { method: 'DELETE' });
};

/**
 * Характерное число с каждого экрана — то, что человек на этом экране читает первым.
 * Ровно этот обход требуют блоки 10.5 и 10.6 («полный обход»).
 */
async function sweep() {
  const [pocket, accounts, capital, strat, wish, fundsRaw, events, dash] = await Promise.all([
    must('/pocket'), must('/accounts'), must('/capital/summary'), must('/strategy/timeline'),
    must('/wishlist/simulation?horizonMonths=12'), must('/funds'),
    must(`/events?startDate=${monthStart}&endDate=${monthEnd}`), must('/analytics/dashboard'),
  ]);
  const funds = fundsRaw.funds ?? fundsRaw;
  const cash = accounts.filter((a) => a.trackBalance && (a.kind === 'DEBIT' || a.kind === 'CASH'))
    .reduce((s, a) => s + (num(a.balance) ?? 0), 0);
  const now = (strat.points ?? []).find((p) => String(p.yearMonth) === thisMonth);
  return {
    'Дашборд · кармашек': num(pocket.pocket),
    'Дашборд · остаток': num(pocket.currentBalance),
    'Счета · сумма денежных': cash,
    'Капитал · итого': num(capital.total),
    'Капитал · ликвид': num(capital.liquid),
    'Стратегия · баланс месяца': num(now?.balance),
    'Стратегия · капитал месяца': num(now?.capital),
    'Хотелки · доступный капитал': num(wish.constraints?.currentCapital),
    'Копилки · сумма балансов': funds.filter((f) => !f.deleted).reduce((s, f) => s + (num(f.currentBalance) ?? 0), 0),
    'Журнал · событий в месяце': events.filter((e) => !e.deleted).length,
    'Дашборд · строк план-факта': (dash.progressBars ?? []).length,
  };
}

const same = (x, y) => (x === null && y === null) || eq(x, y) || x === y;
const diffSweep = (a, b) => Object.keys(a)
  .filter((k) => !same(a[k], b[k]))
  .map((k) => ({ экран: k, было: a[k], стало: b[k], сдвиг: (num(b[k]) ?? 0) - (num(a[k]) ?? 0) }));

async function main() {
  console.log(`\nANO-50 · хвосты этапа 10 — сквозная согласованность · ${today}`);
  console.log('='.repeat(78));
  await syncToday();
  const s0 = await sweep();
  console.log('старт:');
  for (const [k, v] of Object.entries(s0)) console.log(`   ${String(v).padStart(14)}  ${k}`);
  console.log();

  // ── 10.2 плановые расходы месяца на трёх экранах ─────────────────────────
  {
    const evs = (await must(`/events?startDate=${monthStart}&endDate=${monthEnd}`)).filter((e) => !e.deleted);
    const journal = evs
      .filter((e) => e.type === 'EXPENSE' && e.eventKind === 'PLAN' && e.plannedAmount != null)
      .reduce((s, e) => s + num(e.plannedAmount), 0);

    const rep = await must('/analytics/report');
    const analytics = num(rep.planFact?.totalPlannedExpense);

    const p = await must('/pocket?scope=MONTHS%3A1');
    const line = (p.breakdown ?? []).find((l) => l.type === 'PLANNED_EXPENSES');
    const pocketExpenses = line ? Math.abs(num(line.amount)) : 0;
    const minDate = p.minPoint?.date;

    eq(journal, analytics)
      ? record('10.2', 'СОШЛОСЬ', `журнал и аналитика сходятся: ${money(journal)} плановых расходов за ${thisMonth}`)
      : record('10.2', 'РАСХОЖДЕНИЕ', 'плановые расходы месяца в журнале и аналитике не совпадают',
        { журнал: journal, аналитика: analytics, разница: journal - analytics });

    // Кармашек считает не месяц, а отрезок (сегодня, дата минимума] — и этот отрезок
    // может уходить в следующий месяц, поэтому события берутся отдельным запросом,
    // а не фильтрацией месячной выборки.
    const windowEvents = (await must(`/events?startDate=${today}&endDate=${minDate}`))
      .filter((e) => !e.deleted);
    // Кармашек вдобавок исключает хотелки: в траекторию попадают только обычные события
    // и датированные FIXED-неконвертированные (PocketEngine.allowedInTrajectory). Поэтому
    // LOW-строки из суммы журнала надо вычесть, иначе сравниваются разные множества.
    const inWindow = windowEvents
      .filter((e) => e.type === 'EXPENSE' && e.eventKind === 'PLAN' && e.plannedAmount != null
        && e.factAmount == null && e.date > today && e.date <= minDate);
    const sinceToday = inWindow
      .filter((e) => e.priority !== 'LOW')
      .reduce((s, e) => s + num(e.plannedAmount), 0);
    const wishlistInWindow = inWindow
      .filter((e) => e.priority === 'LOW')
      .reduce((s, e) => s + num(e.plannedAmount), 0);
    eq(pocketExpenses, journal)
      ? record('10.2', 'СОШЛОСЬ', 'кармашек показывает ту же сумму, что журнал за месяц')
      : record('10.2', 'СМОТРЕТЬ',
        `кармашек показывает ${money(pocketExpenses)} против ${money(journal)} в журнале — граница другая: `
        + `строка режется по дате минимума ${minDate}, а не по концу месяца`,
        { кармашек: pocketExpenses, журналЗаМесяц: journal, журналДоМинимума: sinceToday, датаМинимума: minDate,
          подписьСтроки: line?.label ?? null });
    eq(pocketExpenses, sinceToday)
      ? record('10.2', 'СОШЛОСЬ',
        'расхождение объясняется полностью: кармашек = журнал в окне (сегодня, минимум] без хотелок')
      : record('10.2', 'СМОТРЕТЬ',
        'кармашек не равен журналу в окне даже без хотелок — остаток разницы требует объяснения',
        { кармашек: pocketExpenses, журналБезХотелок: sinceToday, хотелокВОкне: wishlistInWindow,
          разница: pocketExpenses - sinceToday, окно: `(${today}, ${minDate}]` });
  }

  // ── 10.7 свежесть данных после записи ────────────────────────────────────
  {
    const cats = await must('/categories');
    const expCat = cats.find((c) => c.name === 'Продукты') ?? cats.find((c) => c.type === 'EXPENSE');
    const before = await sweep();
    const fact = await must('/events/facts', send('POST', {
      date: today, categoryId: expCat.id, type: 'EXPENSE', factAmount: 3300,
      description: `Свежесть ${MARK}`,
    }));
    // Сразу, без пауз: если где-то кэш, он проявится именно здесь.
    const after = await sweep();
    const moved = diffSweep(before, after);
    const pocketMoved = moved.find((m) => m.экран === 'Дашборд · кармашек');
    pocketMoved && eq(pocketMoved.сдвиг, -3300)
      ? record('10.7', 'СОШЛОСЬ', 'кармашек увидел факт немедленно, без перезагрузки')
      : record('10.7', 'РАСХОЖДЕНИЕ', 'кармашек не увидел свежий факт или сдвинулся не на 3 300',
        { сдвиг: pocketMoved?.сдвиг ?? null });
    const journalMoved = moved.find((m) => m.экран === 'Журнал · событий в месяце');
    journalMoved && journalMoved.сдвиг === 1
      ? record('10.7', 'СОШЛОСЬ', 'журнал увидел новую запись немедленно')
      : record('10.7', 'РАСХОЖДЕНИЕ', 'журнал не увидел новую запись', { сдвиг: journalMoved?.сдвиг ?? null });
    record('10.7', 'СМОТРЕТЬ', 'какие экраны сдвинулись от одного факта на 3 300', { сдвиги: moved });

    // Второй заход тех же ручек обязан дать то же самое — иначе кэш на сервере.
    const again = await sweep();
    const drift = diffSweep(after, again);
    drift.length === 0
      ? record('10.7', 'СОШЛОСЬ', 'повторный обход дал те же числа — серверного кэша нет')
      : record('10.7', 'РАСХОЖДЕНИЕ', 'повторный обход дал другие числа', { разошлись: drift });
    await del(fact.id);
    record('10.7', 'РУКАМИ', 'обход экранов БЕЗ перезагрузки страницы — кэш живёт на клиенте, API его не видит');
  }

  // ── 10.5 пересчёт после ре-якоря ─────────────────────────────────────────
  {
    const before = await sweep();
    const accounts = await must('/accounts');
    const def = accounts.find((a) => a.isDefault);
    const newAmount = num(def.balance) + 10000;
    const cp = await api('/balance-checkpoints', send('POST', {
      date: today, amount: newAmount, accountId: def.id,
    }));
    if (!cp.ok) {
      record('10.5', 'РАСХОЖДЕНИЕ', `ре-якорь упал с ${cp.status}`, { тело: cp.body });
    } else {
      const after = await sweep();
      const moved = diffSweep(before, after);
      const expected = ['Дашборд · кармашек', 'Дашборд · остаток', 'Счета · сумма денежных',
        'Капитал · итого', 'Капитал · ликвид', 'Стратегия · баланс месяца', 'Стратегия · капитал месяца',
        'Хотелки · доступный капитал'];
      const byScreen = Object.fromEntries(moved.map((m) => [m.экран, m.сдвиг]));
      const right = expected.filter((k) => eq(byScreen[k], 10000));
      const wrong = expected.filter((k) => byScreen[k] !== undefined && !eq(byScreen[k], 10000));
      const silent = expected.filter((k) => byScreen[k] === undefined);

      right.length === expected.length
        ? record('10.5', 'СОШЛОСЬ', `ре-якорь +10 000 доехал одинаково до всех ${expected.length} экранов`)
        : record('10.5', 'РАСХОЖДЕНИЕ', 'ре-якорь доехал до экранов по-разному',
          { сдвинулисьНа10000: right, сдвинулисьИначе: wrong.map((k) => ({ экран: k, сдвиг: byScreen[k] })),
            неСдвинулисьВовсе: silent });
      record('10.5', 'СМОТРЕТЬ', 'полный обход после ре-якоря', { сдвиги: moved });
    }
  }

  // ── 10.6 пересчёт после перевода в копилку ───────────────────────────────
  {
    const before = await sweep();
    const fundsRaw = await must('/funds');
    const funds = (fundsRaw.funds ?? fundsRaw).filter((f) => !f.deleted);
    const target = funds[0];
    if (!target) {
      record('10.6', 'СМОТРЕТЬ', 'копилок нет — перевод не на что делать');
    } else {
      const tr = await api(`/funds/${target.id}/transfer`, send('POST', { amount: 15000 }));
      if (!tr.ok) {
        record('10.6', 'РАСХОЖДЕНИЕ', `перевод упал с ${tr.status}`, { тело: tr.body });
      } else {
        const after = await sweep();
        const moved = diffSweep(before, after);
        const by = Object.fromEntries(moved.map((m) => [m.экран, m.сдвиг]));

        // Закон сохранения: переложить — не потратить. Капитал обязан остаться.
        by['Капитал · итого'] === undefined
          ? record('10.6', 'СОШЛОСЬ', 'капитал не изменился — перекладывание не трата')
          : record('10.6', 'РАСХОЖДЕНИЕ', 'перевод в копилку изменил капитал — нарушен закон сохранения',
            { сдвигКапитала: by['Капитал · итого'] });
        eq(by['Копилки · сумма балансов'], 15000)
          ? record('10.6', 'СОШЛОСЬ', 'копилка приняла ровно 15 000')
          : record('10.6', 'РАСХОЖДЕНИЕ', 'копилка приняла не 15 000', { сдвиг: by['Копилки · сумма балансов'] ?? 0 });
        by['Дашборд · кармашек'] !== undefined && by['Дашборд · кармашек'] < 0
          ? record('10.6', 'СОШЛОСЬ', `кармашек уменьшился на ${money(Math.abs(by['Дашборд · кармашек']))}`)
          : record('10.6', 'РАСХОЖДЕНИЕ', 'кармашек не уменьшился после перевода',
            { сдвиг: by['Дашборд · кармашек'] ?? 0 });
        record('10.6', 'СМОТРЕТЬ', 'поведение ликвида — зависит от привязки копилки к счёту, фиксирую как есть',
          { ликвид: by['Капитал · ликвид'] ?? 0, копилка: target.name, привязанКСчёту: target.accountId ?? null });
        record('10.6', 'СМОТРЕТЬ', 'полный обход после перевода', { сдвиги: moved });
      }
    }
  }

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
  console.log('\nСТЕНД ИЗМЕНЁН НЕОБРАТИМО (ре-якорь + перевод). Восстановить из дампа.\n');
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

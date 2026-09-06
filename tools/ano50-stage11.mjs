#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапу 11 — объём и отзывчивость.
 *
 * Пороги назначены планом заранее, чтобы «вроде терпимо» не стало оценкой:
 *   интерактивный экран — 300 мс
 *   тяжёлый отчёт       — 1000 мс
 *
 * Меряется серверная часть. Рендер — отдельно, глазами и через браузер: подвисание
 * графика примерки (ANO-111) живёт именно там, запросов оно не делает.
 *
 * Первый вызов каждой ручки прогревочный и в статистику не идёт: сразу после
 * перезапуска контейнера бэкенд отвечает в разы медленнее, и это свойство стенда,
 * а не продукта.
 *
 *   node tools/ano50-stage11.mjs        # прогон, для 11.5 СОЗДАЁТ объекты и убирает их
 */

const API = 'http://localhost:8081/api/v1';
const FAST = 300;
const HEAVY = 1000;
const RUNS = 7;
const MARK = 'ANO-50';

const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const today = iso(new Date());
const addMonths = (n, day) => {
  const d = new Date();
  if (day) d.setDate(1);
  d.setMonth(d.getMonth() + n);
  if (day) d.setDate(day);
  return iso(d);
};
const ms = (v) => `${v.toFixed(0)} мс`;

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
  return { status: res.status, ok: res.ok, body, bytes: text.length };
}
async function must(p, i) {
  const r = await api(p, i);
  if (!r.ok) throw new Error(`${p} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 200)}`);
  return r.body;
}
const send = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify(body),
});

/** Замер одной ручки: прогрев + RUNS замеров, медиана и худший. */
async function measure(path) {
  await api(path);                                   // прогрев, в статистику не идёт
  const times = [];
  let bytes = 0;
  for (let i = 0; i < RUNS; i++) {
    const t0 = performance.now();
    const r = await api(path);
    times.push(performance.now() - t0);
    bytes = r.bytes;
  }
  times.sort((a, b) => a - b);
  return {
    медиана: times[Math.floor(times.length / 2)],
    лучший: times[0],
    худший: times[times.length - 1],
    килобайт: Math.round(bytes / 1024),
  };
}

/** Проверка против порога с человеческим сообщением. */
function verdictFor(block, label, m, limit) {
  const line = `${label}: медиана ${ms(m.медиана)}, худший ${ms(m.худший)}, ${m.килобайт} КБ`;
  if (m.медиана <= limit && m.худший <= limit * 1.5) {
    record(block, 'СОШЛОСЬ', `${line} — порог ${limit} мс держится`);
  } else if (m.медиана <= limit) {
    record(block, 'СМОТРЕТЬ', `${line} — медиана в пороге, но худший вылетает`, m);
  } else {
    record(block, 'РАСХОЖДЕНИЕ', `${line} — порог ${limit} мс превышен`, m);
  }
  return m;
}

async function main() {
  console.log(`\nANO-50 · первый проход, этап 11 — объём и отзывчивость · ${today}`);
  console.log(`пороги: интерактивный экран ${FAST} мс, тяжёлый отчёт ${HEAVY} мс, замеров на ручку ${RUNS}`);
  console.log('='.repeat(78));

  const allEvents = (await must('/events?startDate=2020-01-01&endDate=2051-12-31')).filter((e) => !e.deleted);
  const byMonth = {};
  for (const e of allEvents) byMonth[e.date.slice(0, 7)] = (byMonth[e.date.slice(0, 7)] ?? 0) + 1;
  const densest = Object.entries(byMonth).sort((a, b) => b[1] - a[1])[0];
  console.log(`объём стенда: ${allEvents.length} событий, плотнейший месяц ${densest[0]} — ${densest[1]} событий\n`);

  // ── 11.1 кармашек на всех скоупах ────────────────────────────────────────
  {
    const scopes = [
      ['до дохода', ''],
      ['1 месяц', '?scope=MONTHS%3A1'],
      ['3 месяца', '?scope=MONTHS%3A3'],
      ['12 месяцев', '?scope=MONTHS%3A12'],
      ['36 месяцев', '?scope=MONTHS%3A36'],
    ];
    const got = [];
    for (const [label, q] of scopes) {
      const m = await measure(`/pocket${q}`);
      verdictFor('11.1', `кармашек, ${label}`, m, FAST);
      got.push({ скоуп: label, медиана: Math.round(m.медиана) });
    }
    // Рост нелинейный — находка. Сравниваем крайние скоупы.
    const first = got[0].медиана;
    const last = got[got.length - 1].медиана;
    const ratio = first > 0 ? last / first : 0;
    ratio <= 3
      ? record('11.1', 'СОШЛОСЬ', `рост от «до дохода» к 36 месяцам умеренный: ${ratio.toFixed(1)}x`)
      : record('11.1', 'СМОТРЕТЬ', `36 месяцев считаются в ${ratio.toFixed(1)} раза дольше горизонта «до дохода»`,
        { профиль: got });
  }

  // ── 11.2 стратегия и симуляция хотелок ───────────────────────────────────
  {
    verdictFor('11.2', 'стратегия, 36 месяцев', await measure('/strategy/timeline'), HEAVY);
    verdictFor('11.2', 'примерка хотелок, 36 месяцев', await measure('/wishlist/simulation?horizonMonths=36'), HEAVY);
    verdictFor('11.2', 'примерка хотелок, 12 месяцев', await measure('/wishlist/simulation?horizonMonths=12'), HEAVY);
    verdictFor('11.2', 'капитал, сводка', await measure('/capital/summary'), FAST);
  }

  // ── 11.3 журнал на реальном объёме ───────────────────────────────────────
  {
    const m = verdictFor('11.3', `журнал целиком, ${allEvents.length} событий`,
      await measure('/events?startDate=2020-01-01&endDate=2051-12-31'), HEAVY);
    verdictFor('11.3', 'журнал за месяц', await measure(`/events?startDate=${today.slice(0, 8)}01&endDate=${today.slice(0, 8)}28`), FAST);
    record('11.3', 'СМОТРЕТЬ',
      `журнал отдаётся одним куском на ${m.килобайт} КБ — постраничности в API нет, вся нагрузка на клиенте`,
      { событий: allEvents.length, килобайт: m.килобайт });
    record('11.3', 'РУКАМИ', 'плавность прокрутки и возможность найти конкретную запись — только на экране');
  }

  // ── 11.4 месяц с плотной сеткой событий ──────────────────────────────────
  {
    const ym = densest[0];
    const [y, mo] = ym.split('-').map(Number);
    const first = `${ym}-01`;
    const last = iso(new Date(y, mo, 0));
    record('11.4', 'НЕЛЬЗЯ',
      'создать сто событий ежедневным правилом невозможно: RecurringFrequency = {MONTHLY, YEARLY}. '
      + `Мерю на реальной плотности — ${ym}, ${densest[1]} событий`);
    verdictFor('11.4', `журнал плотного месяца ${ym}`, await measure(`/events?startDate=${first}&endDate=${last}`), FAST);
    verdictFor('11.4', `аналитика плотного месяца ${ym}`, await measure(`/analytics/report?date=${first}`), HEAVY);
    verdictFor('11.4', `дашборд плотного месяца ${ym}`, await measure(`/analytics/dashboard?date=${first}`), FAST);
    verdictFor('11.4', 'мультимесячный отчёт за полгода',
      await measure(`/analytics/multi-month?startDate=${addMonths(-5, 1)}&endDate=${addMonths(1, 1)}`), HEAVY);
  }

  // ── 11.5 двадцать копилок и двадцать хотелок ─────────────────────────────
  {
    const before = {
      стратегия: (await measure('/strategy/timeline')).медиана,
      примерка: (await measure('/wishlist/simulation?horizonMonths=36')).медиана,
      копилки: (await measure('/funds')).медиана,
    };
    const madeFunds = [];
    const madeWishes = [];
    for (let i = 1; i <= 20; i++) {
      const f = await api('/funds', send('POST', {
        name: `Копилка ${i} ${MARK}`, targetAmount: 50000 + i * 1000,
        targetDate: addMonths(6 + (i % 12), 15), purchaseType: 'SAVINGS',
      }));
      if (f.ok) madeFunds.push(f.body.id);
      const w = await api('/events/wishlist', send('POST', {
        description: `Хотелка ${i} ${MARK}`, plannedAmount: 20000 + i * 500, date: addMonths(3 + (i % 18), 10),
      }));
      if (w.ok) madeWishes.push(w.body.id);
    }
    record('11.5', madeFunds.length === 20 && madeWishes.length === 20 ? 'СОШЛОСЬ' : 'СМОТРЕТЬ',
      `создано копилок ${madeFunds.length}, хотелок ${madeWishes.length}`);

    const after = {
      стратегия: verdictFor('11.5', 'стратегия при 20 копилках и 20 хотелках', await measure('/strategy/timeline'), HEAVY),
      примерка: verdictFor('11.5', 'примерка при 20 копилках и 20 хотелках',
        await measure('/wishlist/simulation?horizonMonths=36'), HEAVY),
      копилки: verdictFor('11.5', 'экран копилок', await measure('/funds'), FAST),
      кармашек: verdictFor('11.5', 'кармашек при 40 новых объектах', await measure('/pocket'), FAST),
    };
    const growth = {
      стратегия: `${before.стратегия.toFixed(0)} -> ${after.стратегия.медиана.toFixed(0)} мс`,
      примерка: `${before.примерка.toFixed(0)} -> ${after.примерка.медиана.toFixed(0)} мс`,
      копилки: `${before.копилки.toFixed(0)} -> ${after.копилки.медиана.toFixed(0)} мс`,
    };
    const worst = Math.max(
      after.примерка.медиана / Math.max(before.примерка, 1),
      after.стратегия.медиана / Math.max(before.стратегия, 1),
    );
    worst <= 2
      ? record('11.5', 'СОШЛОСЬ', `сорок новых объектов замедлили тяжёлые экраны не более чем вдвое`, null)
      : record('11.5', 'СМОТРЕТЬ', `сорок новых объектов замедлили тяжёлые экраны в ${worst.toFixed(1)} раза`, growth);
    record('11.5', 'СМОТРЕТЬ', 'рост времени по каждому экрану', growth);
    record('11.5', 'РУКАМИ', 'читаемость списков из двадцати копилок и двадцати хотелок — только на экране');

    for (const id of madeFunds) await api(`/funds/${id}`, { method: 'DELETE' });
    for (const id of madeWishes) await api(`/events/${id}`, { method: 'DELETE' });
  }

  // ── уборка и итог ────────────────────────────────────────────────────────
  const restEvents = (await must('/events?startDate=2020-01-01&endDate=2051-12-31'))
    .filter((e) => !e.deleted && (e.description ?? '').includes(MARK));
  const restFunds = (await must('/funds').then((r) => r.funds ?? r))
    .filter((f) => !f.deleted && (f.name ?? '').includes(MARK));

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Превышений порога: ${bad.length}   Требует глаз: ${look.length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nПРЕВЫШЕНИЯ ПОРОГА:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}`));
  }
  if (look.length) {
    console.log('\nТРЕБУЕТ ГЛАЗ:');
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}${r.data ? `  ${JSON.stringify(r.data)}` : ''}`));
  }
  console.log(`\nуборка: осталось событий с меткой ${MARK} — ${restEvents.length}, копилок — ${restFunds.length}\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

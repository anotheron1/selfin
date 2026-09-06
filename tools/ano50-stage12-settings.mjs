#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапу 12 — настройки, снимки и остальное.
 *
 * ВНИМАНИЕ: имя занято под этапы 1–2 (ano50-stage12.mjs = «этапы 1 и 2»). Этот файл —
 * этап ДВЕНАДЦАТЬ. Чтобы не путать, он называется ano50-stage12-settings.mjs.
 *
 *   node tools/ano50-stage12-settings.mjs
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eq = (a, b) => a !== null && b !== null && a !== undefined && b !== undefined
  && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const today = iso(new Date());
const thisMonth = today.slice(0, 7);
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
const put = (body) => ({ method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

const pocketOf = () => must('/pocket');
const byType = (p) => Object.fromEntries((p.breakdown ?? []).map((l) => [l.type, num(l.amount)]));

/** Зоны риска считаются на фронте (wishlistUtils.riskZones). Повторяю формулу дословно. */
const worse = (a, b) => ({ green: 0, yellow: 1, red: 2 }[a] >= { green: 0, yellow: 1, red: 2 }[b] ? a : b);
function riskZones(points, thresholds, monthlyExpensesAvg) {
  const buffer = monthlyExpensesAvg * thresholds.cashBufferMonths;
  return points.map((p) => {
    const account = num(p.balance);
    const capital = num(p.capital);
    const accountRisk = account < 0 ? 'red' : account < buffer ? 'yellow' : 'green';
    let capitalRisk = 'green';
    if (thresholds.capitalThresholdRub != null) {
      const t = num(thresholds.capitalThresholdRub);
      capitalRisk = capital < t ? 'red' : capital < t * 1.1 ? 'yellow' : 'green';
    }
    return worse(accountRisk, capitalRisk);
  });
}
const countZones = (z) => z.reduce((acc, k) => ({ ...acc, [k]: (acc[k] ?? 0) + 1 }), {});

/** Шесть чисел — проверять, что чтение ничего не меняет. */
async function six() {
  const [p, accounts, capital] = await Promise.all([
    must('/pocket'), must('/accounts'), must('/capital/summary'),
  ]);
  const cash = accounts.filter((a) => a.trackBalance && (a.kind === 'DEBIT' || a.kind === 'CASH'))
    .reduce((s, a) => s + (num(a.balance) ?? 0), 0);
  return {
    кармашек: num(p.pocket), минимум: num(p.minPoint?.balance), датаМинимума: p.minPoint?.date ?? null,
    остаток: cash, капитал: num(capital.total), обязательства: num(capital.liabilitiesTotal),
  };
}
const diffSix = (a, b) => Object.keys(a)
  .filter((k) => (typeof a[k] === 'number' ? !eq(a[k], b[k]) : a[k] !== b[k]));

async function main() {
  console.log(`\nANO-50 · первый проход, этап 12 — настройки, снимки и остальное · ${today}`);
  console.log('='.repeat(78));

  const pocket0 = await pocketOf();
  const pocketSettings0 = await must('/settings/pocket');
  const wishSettings0 = await must('/settings/wishlist');
  console.log(`старт: кармашек ${money(pocket0.pocket)}, буфер ${money(pocketSettings0.bufferAmount)}, `
    + `порог капитала ${wishSettings0.capitalThresholdRub ?? 'выключен'}, `
    + `буфер хотелок ${wishSettings0.cashBufferMonths} мес\n`);

  // ── 12.1 снимок бюджета ──────────────────────────────────────────────────
  {
    const before = await six();
    const listBefore = await must('/snapshots');
    const snap = await api('/snapshots', send('POST', {}));
    if (!snap.ok) {
      record('12.1', 'РАСХОЖДЕНИЕ', `создание снимка упало с ${snap.status}`, { тело: snap.body });
    } else {
      const listAfter = await must('/snapshots');
      const found = listAfter.find((s) => s.id === snap.body.id);
      found
        ? record('12.1', 'СОШЛОСЬ', `снимок в списке: период ${found.periodStart}…${found.periodEnd}, создан ${String(found.snapshotDate).slice(0, 19)}`)
        : record('12.1', 'РАСХОЖДЕНИЕ', 'созданный снимок не появился в списке', { id: snap.body.id });

      const after = await six();
      const moved = diffSix(before, after);
      moved.length === 0
        ? record('12.1', 'СОШЛОСЬ', 'создание снимка не тронуло ни одного из шести чисел')
        : record('12.1', 'РАСХОЖДЕНИЕ', 'создание снимка сдвинуло данные — снимок это чтение',
          { разошлись: moved, до: before, после: after });

      // Идемпотентность объявлена в описании ручки: повтор за тот же месяц вернёт тот же снимок.
      const again = await api('/snapshots', send('POST', {}));
      const listRepeat = await must('/snapshots');
      again.ok && again.body.id === snap.body.id
        ? record('12.1', 'СОШЛОСЬ', 'повторный снимок за тот же месяц вернул существующий — идемпотентно')
        : record('12.1', 'РАСХОЖДЕНИЕ', 'повтор создал второй снимок за тот же месяц',
          { первый: snap.body.id, второй: again.body?.id, сталоВсего: listRepeat.length, былоДо: listBefore.length });
    }
  }

  // ── 12.2 снимок против текущего состояния ────────────────────────────────
  {
    const list = await must('/snapshots');
    const one = list[0];
    const keys = one ? Object.keys(one) : [];
    const hasContent = keys.some((k) => /data|events|items|rows|payload/i.test(k));
    if (hasContent) {
      record('12.2', 'СМОТРЕТЬ', 'в ответе снимка есть поле с содержимым — проверить неизменность', { поля: keys });
    } else {
      record('12.2', 'НЕЛЬЗЯ',
        'содержимое снимка прочитать нечем: API отдаёт только даты, ручки детального просмотра нет',
        { поляОтвета: keys, естьРучки: ['POST /snapshots', 'GET /snapshots'] });
      record('12.2', 'РАСХОЖДЕНИЕ',
        'снимок бюджета пишется, но не читается: snapshot_data сохраняется в базу и наружу не отдаётся ни одним эндпоинтом',
        { комментарийВКоде: 'запрашивается отдельно при необходимости — такой ручки не существует' });
    }
  }

  // ── 12.3 настройки кармашка ──────────────────────────────────────────────
  {
    const before = await pocketOf();
    const put1 = await api('/settings/pocket', put({ bufferAmount: 12345 }));
    const read1 = await must('/settings/pocket');
    const after = await pocketOf();

    put1.ok && eq(num(read1.bufferAmount), 12345)
      ? record('12.3', 'СОШЛОСЬ', 'буфер 12 345 сохранился и читается обратно')
      : record('12.3', 'РАСХОЖДЕНИЕ', 'буфер не сохранился', { статус: put1.status, прочитано: read1 });
    eq(num(after.pocket) - num(before.pocket), -12345)
      ? record('12.3', 'СОШЛОСЬ', 'настройка применилась: кармашек упал ровно на 12 345')
      : record('12.3', 'РАСХОЖДЕНИЕ', 'настройка сохранилась, но на кармашек не подействовала',
        { было: num(before.pocket), стало: num(after.pocket), сдвиг: num(after.pocket) - num(before.pocket) });
    eq(byType(after).BUFFER, -12345)
      ? record('12.3', 'СОШЛОСЬ', 'строка буфера появилась в разбивке')
      : record('12.3', 'РАСХОЖДЕНИЕ', 'строки буфера в разбивке нет', { строка: byType(after).BUFFER ?? null });

    // Персистентность через «перезагрузку страницы» = независимый повторный GET.
    const read2 = await must('/settings/pocket');
    eq(num(read2.bufferAmount), 12345)
      ? record('12.3', 'СОШЛОСЬ', 'значение пережило повторное чтение — персистентность держится')
      : record('12.3', 'РАСХОЖДЕНИЕ', 'при повторном чтении значение изменилось', { прочитано: read2 });

    const bad = await api('/settings/pocket', put({ bufferAmount: -500 }));
    bad.status === 400
      ? record('12.3', 'СОШЛОСЬ', 'отрицательный буфер отвергнут с 400')
      : record('12.3', 'СМОТРЕТЬ', `отрицательный буфер принят со статусом ${bad.status}`,
        { статус: bad.status, послеЗаписи: (await must('/settings/pocket')).bufferAmount });

    await must('/settings/pocket', put({ bufferAmount: pocketSettings0.bufferAmount ?? 0 }));
    eq(num((await pocketOf()).pocket), num(before.pocket))
      ? record('12.3', 'СОШЛОСЬ', 'возврат буфера вернул кармашек в точности')
      : record('12.3', 'РАСХОЖДЕНИЕ', 'после возврата буфера кармашек не сошёлся');
    record('12.3', 'РУКАМИ', 'в интерфейсе буфер задать нечем — ANO-92; проверка сделана только по API');
  }

  // ── 12.4 настройки хотелок и зоны риска ──────────────────────────────────
  {
    const sim0 = await must('/wishlist/simulation?horizonMonths=12');
    const pts = (sim0.baseline?.points ?? []).filter((p) => p.phase === 'FUTURE');
    const avg = num(sim0.constraints?.monthlyExpensesAvg) ?? 0;
    const zonesBefore = riskZones(pts, sim0.thresholds, avg);

    // Порог выше текущего капитала — обязан покрасить будущее в красный.
    const cap = num((await must('/capital/summary')).total);
    const highThreshold = Math.max(cap * 2, cap + 1000000);
    const putRes = await api('/settings/wishlist', put({
      capitalThresholdRub: highThreshold, cashBufferMonths: wishSettings0.cashBufferMonths,
    }));
    const sim1 = await must('/wishlist/simulation?horizonMonths=12');
    const zonesAfter = riskZones((sim1.baseline?.points ?? []).filter((p) => p.phase === 'FUTURE'),
      sim1.thresholds, num(sim1.constraints?.monthlyExpensesAvg) ?? 0);

    putRes.ok && eq(num(sim1.thresholds?.capitalThresholdRub), highThreshold)
      ? record('12.4', 'СОШЛОСЬ', `порог капитала ${money(highThreshold)} сохранился и вернулся в примерке`)
      : record('12.4', 'РАСХОЖДЕНИЕ', 'порог капитала не сохранился или не доехал до примерки',
        { статус: putRes.status, вПримерке: sim1.thresholds });

    const cBefore = countZones(zonesBefore);
    const cAfter = countZones(zonesAfter);
    // Итоговая зона = худшая из счётной и капитальной. Если счётная уже красная на всём
    // горизонте, порог капитала не может ничего изменить — сравнивать надо капитальную
    // составляющую отдельно, иначе проверка ничего не проверяет.
    const capitalOnly = (points, threshold) => points.map((p) => {
      if (threshold == null) return 'green';
      const c = num(p.capital);
      const t = num(threshold);
      return c < t ? 'red' : c < t * 1.1 ? 'yellow' : 'green';
    });
    const capBefore = countZones(capitalOnly(pts, sim0.thresholds?.capitalThresholdRub));
    const capAfter = countZones(capitalOnly((sim1.baseline?.points ?? []).filter((p) => p.phase === 'FUTURE'),
      sim1.thresholds?.capitalThresholdRub));
    JSON.stringify(capBefore) !== JSON.stringify(capAfter)
      ? record('12.4', 'СОШЛОСЬ',
        `капитальная составляющая зон перекрасилась: было ${JSON.stringify(capBefore)}, стало ${JSON.stringify(capAfter)}`)
      : record('12.4', 'РАСХОЖДЕНИЕ', 'порог капитала поднят выше капитала, а капитальная составляющая зон не изменилась',
        { порог: highThreshold, капитал: cap, было: capBefore, стало: capAfter });
    JSON.stringify(cBefore) === JSON.stringify(cAfter) && Object.keys(cAfter).length === 1
      ? record('12.4', 'СМОТРЕТЬ',
        `итоговые зоны не меняются: весь горизонт уже «${Object.keys(cAfter)[0]}» из-за счётного риска, и порог капитала на картинку не влияет`,
        { итоговые: cAfter, счётныйБуфер: `${avg} × ${sim0.thresholds?.cashBufferMonths} мес` })
      : record('12.4', 'СОШЛОСЬ', `итоговые зоны: было ${JSON.stringify(cBefore)}, стало ${JSON.stringify(cAfter)}`);

    const badThreshold = await api('/settings/wishlist', put({
      capitalThresholdRub: -1, cashBufferMonths: wishSettings0.cashBufferMonths,
    }));
    badThreshold.status === 400
      ? record('12.4', 'СОШЛОСЬ', 'отрицательный порог капитала отвергнут с 400')
      : record('12.4', 'СМОТРЕТЬ', `отрицательный порог принят со статусом ${badThreshold.status}`, { статус: badThreshold.status });

    await must('/settings/wishlist', put({
      capitalThresholdRub: wishSettings0.capitalThresholdRub,
      cashBufferMonths: wishSettings0.cashBufferMonths,
    }));
    const restored = await must('/settings/wishlist');
    JSON.stringify(restored) === JSON.stringify(wishSettings0)
      ? record('12.4', 'СОШЛОСЬ', 'настройки хотелок возвращены к исходным')
      : record('12.4', 'СМОТРЕТЬ', 'настройки хотелок вернулись не в точности', { было: wishSettings0, стало: restored });
  }

  record('12.5', 'РУКАМИ', 'мобильный вид восьми экранов — отдельным проходом через браузер');
  record('12.6', 'РУКАМИ', 'свободное исследование продукта целиком, час');

  // ── итог ─────────────────────────────────────────────────────────────────
  const finalPocket = await pocketOf();
  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  const look = log.filter((r) => r.verdict === 'СМОТРЕТЬ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${look.length}   `
    + `Невыполнимо: ${log.filter((r) => r.verdict === 'НЕЛЬЗЯ').length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  if (look.length) {
    console.log('\nТРЕБУЕТ ГЛАЗ:');
    look.forEach((r) => console.log(`  ${r.block}: ${r.message}`));
  }
  console.log(`\nкармашек на выходе: ${money(finalPocket.pocket)} (на входе ${money(pocket0.pocket)})\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

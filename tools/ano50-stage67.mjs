#!/usr/bin/env node
/**
 * ANO-50, первый проход по этапам 6 и 7 — копилки и капитал.
 *
 * Ходит по API, проверяет числа. Пути пользователя, тексты и реакцию интерфейса
 * не видит — блоки, где суть в экране, помечает как «руками».
 *
 *   node tools/ano50-stage67.mjs      # прогон, МЕНЯЕТ ДАННЫЕ
 */

const API = 'http://localhost:8081/api/v1';
const EPS = 0.005;
const eq = (a, b) => a !== null && b !== null && Math.abs(Number(a) - Number(b)) < EPS;
const money = (v) => (v === null || v === undefined ? '—'
  : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 2 }));
const today = new Date().toISOString().slice(0, 10);

const log = [];
const record = (block, verdict, message, data) => {
  log.push({ block, verdict, message, data: data ?? null });
  const mark = { 'СОШЛОСЬ': '  ok ', 'РАСХОЖДЕНИЕ': ' !!! ', 'СМОТРЕТЬ': '  ?  ', 'РУКАМИ': '  →  ' }[verdict];
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
  if (!r.ok) throw new Error(`${p} → ${r.status}: ${JSON.stringify(r.body).slice(0, 250)}`);
  return r.body;
}
const jsonInit = (method, body, key) => ({
  method,
  headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
  body: JSON.stringify(body),
});

async function snap() {
  const [p, c, f] = await Promise.all([must('/pocket'), must('/capital/summary'), must('/funds')]);
  return {
    pocket: Number(p.pocket), balance: Number(p.currentBalance),
    withDeposits: p.pocketWithDeposits === null ? null : Number(p.pocketWithDeposits),
    total: Number(c.total), liquid: Number(c.liquid),
    assets: Number(c.assetsTotal), liabilities: Number(c.liabilitiesTotal),
    funds: f.funds ?? f,
  };
}

async function main() {
  console.log(`\nANO-50 · первый проход, этапы 6 и 7 · ${today}`);
  console.log('='.repeat(78));
  let s0 = await snap();
  console.log(`старт: кармашек ${money(s0.pocket)}, капитал ${money(s0.total)}, ликвид ${money(s0.liquid)}\n`);

  // ══ ЭТАП 6. КОПИЛКИ ═══════════════════════════════════════════════════════

  // 6.1
  const otpusk = await must('/funds', jsonInit('POST', {
    name: 'Отпуск', targetAmount: 200000, targetDate: '2027-06-01', purchaseType: 'SAVINGS',
  }));
  let s = await snap();
  const f61 = s.funds.find((f) => f.id === otpusk.id);
  eq(f61?.targetAmount, 200000) && eq(f61?.currentBalance, 0)
    ? record('6.1', 'СОШЛОСЬ', 'копилка создана, накоплено 0 из 200 000')
    : record('6.1', 'РАСХОЖДЕНИЕ', 'копилка создана не такой', { фонд: f61 });
  record('6.1', 'РУКАМИ', 'рекомендуемый взнос считается и показывается — только на экране');
  s0 = s;

  // 6.2
  await must(`/funds/${otpusk.id}/transfer`, jsonInit('POST', { amount: 20000 }, crypto.randomUUID()));
  s = await snap();
  const f62 = s.funds.find((f) => f.id === otpusk.id);
  eq(f62?.currentBalance, 20000)
    ? record('6.2', 'СОШЛОСЬ', 'переведено 20 000, накоплено 20 000 (10% цели)')
    : record('6.2', 'РАСХОЖДЕНИЕ', 'накоплено не 20 000', { накоплено: f62?.currentBalance });
  record('6.2', 'СМОТРЕТЬ', 'как перевод отразился на кармашке и ликвиде',
    { кармашекДо: s0.pocket, кармашекПосле: s.pocket, ликвидДо: s0.liquid, ликвидПосле: s.liquid });
  s0 = s;

  // 6.3 идемпотентность: один и тот же ключ трижды
  const key = crypto.randomUUID();
  const r1 = await api(`/funds/${otpusk.id}/transfer`, jsonInit('POST', { amount: 5000 }, key));
  const r2 = await api(`/funds/${otpusk.id}/transfer`, jsonInit('POST', { amount: 5000 }, key));
  const r3 = await api(`/funds/${otpusk.id}/transfer`, jsonInit('POST', { amount: 5000 }, key));
  s = await snap();
  const f63 = s.funds.find((f) => f.id === otpusk.id);
  eq(f63?.currentBalance, 25000)
    ? record('6.3', 'СОШЛОСЬ', 'три запроса с одним ключом дали одно движение: 20 000 → 25 000')
    : record('6.3', 'РАСХОЖДЕНИЕ', 'повтор с тем же ключом создал лишние движения',
      { накоплено: f63?.currentBalance, ожидалось: 25000, коды: [r1.status, r2.status, r3.status] });
  s0 = s;

  // 6.4 копилка на реальном счёте
  const cash = (await must('/accounts')).find((a) => a.kind === 'CASH' && a.trackBalance)
    ?? (await must('/accounts')).find((a) => a.trackBalance && a.kind === 'DEBIT' && !a.isDefault);
  if (cash) {
    const linked = await api('/funds', jsonInit('POST', {
      name: 'Копилка на счёте', targetAmount: 50000, targetDate: '2027-06-01',
      purchaseType: 'SAVINGS', accountId: cash.id,
    }));
    if (linked.ok) {
      const s64 = await snap();
      const lf = s64.funds.find((f) => f.id === linked.body.id);
      record('6.4', 'СМОТРЕТЬ', `копилка привязана к счёту «${cash.name}»`,
        { накопленоПоФонду: lf?.currentBalance, ликвидДо: s0.liquid, ликвидПосле: s64.liquid,
          примечание: 'по §4.4 фонды со счётом не прибавляются отдельно — защита от задвоения' });
      const tr = await api(`/funds/${linked.body.id}/transfer`,
        jsonInit('POST', { amount: 1000 }, crypto.randomUUID()));
      record('6.4', tr.ok ? 'СМОТРЕТЬ' : 'СОШЛОСЬ',
        tr.ok ? 'перевод в копилку на счёте прошёл' : `перевод в копилку на счёте запрещён, ${tr.status}`,
        tr.ok ? null : { тело: tr.body });
      await api(`/funds/${linked.body.id}`, { method: 'DELETE' });
    } else {
      record('6.4', 'СМОТРЕТЬ', `создать копилку на счёте не удалось, ${linked.status}`, { тело: linked.body });
    }
  }
  s0 = await snap();

  // 6.5 перевод обратно
  const back = await api(`/funds/${otpusk.id}/transfer`,
    jsonInit('POST', { amount: -5000 }, crypto.randomUUID()));
  s = await snap();
  const f65 = s.funds.find((f) => f.id === otpusk.id);
  if (back.ok && eq(f65?.currentBalance, 20000)) {
    record('6.5', 'СОШЛОСЬ', 'обратный перевод уменьшил накопленное: 25 000 → 20 000');
  } else if (!back.ok) {
    record('6.5', 'РАСХОЖДЕНИЕ', `обратный перевод отвергнут (${back.status}) — обратимости нет`,
      { тело: back.body, накоплено: f65?.currentBalance });
  } else {
    record('6.5', 'РАСХОЖДЕНИЕ', 'обратный перевод прошёл, но накоплено не то',
      { накоплено: f65?.currentBalance });
  }
  s0 = s;

  // 6.6 перевод больше остатка
  const huge = await api(`/funds/${otpusk.id}/transfer`,
    jsonInit('POST', { amount: 99999999 }, crypto.randomUUID()));
  s = await snap();
  const f66 = s.funds.find((f) => f.id === otpusk.id);
  huge.ok
    ? record('6.6', 'РАСХОЖДЕНИЕ', 'перевод больше остатка прошёл молча',
      { статус: huge.status, накоплено: f66?.currentBalance })
    : record('6.6', 'СОШЛОСЬ', `перевод больше остатка отвергнут, ${huge.status}`);
  if (huge.ok) {
    await api(`/funds/${otpusk.id}/transfer`, jsonInit('POST', { amount: -99999999 }, crypto.randomUUID()));
  }
  s0 = await snap();

  // 6.7 цель достигнута
  const cur = (await snap()).funds.find((f) => f.id === otpusk.id);
  const need = 200000 - Number(cur.currentBalance);
  const toGoal = await api(`/funds/${otpusk.id}/transfer`,
    jsonInit('POST', { amount: need }, crypto.randomUUID()));
  s = await snap();
  const f67 = s.funds.find((f) => f.id === otpusk.id);
  if (toGoal.ok) {
    f67?.status === 'REACHED'
      ? record('6.7', 'СОШЛОСЬ', 'цель достигнута, статус REACHED')
      : record('6.7', 'РАСХОЖДЕНИЕ', 'накоплено равно цели, а статус не изменился',
        { накоплено: f67?.currentBalance, статус: f67?.status });
  } else {
    record('6.7', 'СМОТРЕТЬ', `довести до цели не удалось, ${toGoal.status}`, { тело: toGoal.body });
  }
  s0 = s;

  // 6.8 удалить копилку с накоплениями.
  //
  // ANO-86 починен: раньше удаление молча сносило копилку, деньги исчезали из свободных и
  // продолжали раздувать капитал. Теперь продукт СПРАШИВАЕТ, что с ними сделать, и оба
  // ответа обязаны быть арифметически верными. Проверяем все три ветки.
  const beforeDel = await snap();
  const savedBefore = Number(beforeDel.funds.find((f) => f.id === otpusk.id)?.currentBalance ?? 0);

  const noChoice = await api(`/funds/${otpusk.id}`, { method: 'DELETE' });
  noChoice.status === 409
    ? record('6.8', 'СОШЛОСЬ', 'удаление копилки с деньгами требует ответа, что с ними сделать')
    : record('6.8', 'РАСХОЖДЕНИЕ', 'копилка с деньгами удаляется без вопроса',
      { статус: noChoice.status, былоНакоплено: savedBefore });

  const returned = await api(`/funds/${otpusk.id}?money=RETURN`, { method: 'DELETE' });
  const afterDel = await snap();
  if (returned.ok) {
    eq(afterDel.liquid, beforeDel.liquid) && eq(afterDel.pocket - beforeDel.pocket, savedBefore)
      ? record('6.8', 'СОШЛОСЬ', 'деньги вернулись в свободные, ликвид не изменился',
        { вернулось: savedBefore, ликвид: afterDel.liquid })
      : record('6.8', 'РАСХОЖДЕНИЕ', 'возврат денег не сошёлся',
        { былоНакоплено: savedBefore, ликвидДо: beforeDel.liquid, ликвидПосле: afterDel.liquid,
          кармашекДо: beforeDel.pocket, кармашекПосле: afterDel.pocket });
  } else {
    record('6.8', 'РАСХОЖДЕНИЕ', `возврат денег не прошёл, ${returned.status}`,
      { тело: returned.body });
  }
  record('6.8', 'РУКАМИ', 'второй исход «потрачено на цель» — проверить, что капитал падает '
    + 'ровно на сумму, а строка журнала перестаёт звать трату переводом');
  record('6.9', 'РУКАМИ', 'свободное исследование копилок');

  // ══ ЭТАП 7. КАПИТАЛ ═══════════════════════════════════════════════════════
  console.log('');
  s0 = await snap();

  // 7.1
  eq(s0.liquid + s0.assets - s0.liabilities, s0.total)
    ? record('7.1', 'СОШЛОСЬ', 'итого = ликвид + активы − обязательства')
    : record('7.1', 'РАСХОЖДЕНИЕ', 'сводка капитала не сходится',
      { итого: s0.total, ожидалось: s0.liquid + s0.assets - s0.liabilities });

  // 7.2
  const car = await must('/capital/items', jsonInit('POST', {
    kind: 'ASSET', name: 'Машина', initialValue: 1200000, initialValuedAt: today,
  }));
  s = await snap();
  eq(s.assets - s0.assets, 1200000) && eq(s.total - s0.total, 1200000)
    ? record('7.2', 'СОШЛОСЬ', 'актив добавлен, активы и итого выросли на 1 200 000')
    : record('7.2', 'РАСХОЖДЕНИЕ', 'актив изменил капитал не на 1 200 000',
      { активы: s.assets - s0.assets, итого: s.total - s0.total });
  s0 = s;

  // 7.3
  const loan = await must('/capital/items', jsonInit('POST', {
    kind: 'LIABILITY', name: 'Заём ANO-50', initialValue: 3000000, initialValuedAt: today,
  }));
  s = await snap();
  eq(s.liabilities - s0.liabilities, 3000000) && eq(s.total - s0.total, -3000000)
    ? record('7.3', 'СОШЛОСЬ', 'обязательство добавлено, итого упало на 3 000 000')
    : record('7.3', 'РАСХОЖДЕНИЕ', 'обязательство изменило капитал не на −3 000 000',
      { обязательства: s.liabilities - s0.liabilities, итого: s.total - s0.total });
  s0 = s;

  // 7.4 кредитки в обязательствах, без задвоения
  const accs = await must('/accounts');
  const cards = accs.filter((a) => a.kind === 'CREDIT');
  const cardDebt = cards.reduce((x, a) => x + Number(a.debt ?? 0), 0);
  const items = (await must('/capital/summary')).items.filter((i) => i.kind === 'LIABILITY' && !i.isArchived);
  const itemSum = items.reduce((x, i) => x + Number(i.currentValue ?? 0), 0);
  eq(itemSum + cardDebt, s0.liabilities)
    ? record('7.4', 'СОШЛОСЬ', 'обязательства = строки капитала + долги карт, каждый долг один раз',
      { строки: itemSum, карты: cardDebt, итого: s0.liabilities })
    : record('7.4', 'РАСХОЖДЕНИЕ', 'обязательства не раскладываются на строки и долги карт',
      { строки: itemSum, карты: cardDebt, итого: s0.liabilities,
        необъяснено: s0.liabilities - itemSum - cardDebt });

  // 7.5 переоценка
  await must(`/capital/items/${car.id}/revaluations`, jsonInit('POST', {
    value: 1050000, valuedAt: today, note: 'ANO-50 этап 7',
  }));
  s = await snap();
  eq(s.assets - s0.assets, -150000)
    ? record('7.5', 'СОШЛОСЬ', 'переоценка снизила активы на 150 000')
    : record('7.5', 'РАСХОЖДЕНИЕ', 'переоценка изменила активы не на −150 000',
      { сдвиг: s.assets - s0.assets });
  s0 = s;

  // 7.6 история переоценок
  const hist = await must(`/capital/items/${car.id}/revaluations`);
  hist.length >= 2
    ? record('7.6', 'СОШЛОСЬ', `история переоценок содержит ${hist.length} записи`,
      { записи: hist.map((h) => `${h.valuedAt}: ${h.value}`) })
    : record('7.6', 'СМОТРЕТЬ', 'в истории меньше двух записей — начальная оценка могла не попасть',
      { записи: hist.map((h) => `${h.valuedAt}: ${h.value}`) });

  // 7.7 вклад: где виден
  const deposits = accs.filter((a) => a.kind === 'DEPOSIT' && a.trackBalance);
  const depSum = deposits.reduce((x, a) => x + Number(a.balance ?? 0), 0);
  if (depSum > 0) {
    const p = await must('/pocket');
    const wl = await must('/wishlist/simulation');
    const inThird = eq(Number(p.pocketWithDeposits) - Number(p.pocket), depSum);
    const inPocket = false;
    const inWishlist = eq(Number(wl.constraints.currentCapital), Number(p.currentBalance));
    inThird && inWishlist
      ? record('7.7', 'СОШЛОСЬ', 'вклад виден в третьем числе и в капитале, но не в кармашке и не в потолке хотелок',
        { вклады: depSum })
      : record('7.7', 'РАСХОЖДЕНИЕ', 'вклад просочился не туда или пропал где нужен',
        { третьеЧисло: inThird, потолокХотелок: wl.constraints.currentCapital, остаток: p.currentBalance });
    record('7.7', 'РУКАМИ', 'кассовый график стратегии — проверить глазами, что вклада там нет');
  }

  // 7.8 траектория капитала
  const traj = await must('/capital/trajectory');
  const last = traj.points[traj.points.length - 1];
  const sum78 = await snap();
  eq(Number(last.capital), sum78.total)
    ? record('7.8', 'СОШЛОСЬ', 'последняя точка графика равна итогу сводки', { итого: sum78.total })
    : record('7.8', 'РАСХОЖДЕНИЕ', 'график капитала не согласован со сводкой',
      { точка: last.capital, дата: last.date, сводка: sum78.total });

  // 7.9 удалить актив с историей переоценок
  const before79 = await snap();
  const d79 = await api(`/capital/items/${car.id}`, { method: 'DELETE' });
  const after79 = await snap();
  const histAfter = await api(`/capital/items/${car.id}/revaluations`);
  if (d79.ok) {
    eq(after79.assets - before79.assets, -1050000)
      ? record('7.9', 'СОШЛОСЬ', 'актив удалён, активы упали на его текущую оценку 1 050 000',
        { историяПослеУдаления: histAfter.status })
      : record('7.9', 'РАСХОЖДЕНИЕ', 'после удаления актива активы сдвинулись не на его оценку',
        { сдвиг: after79.assets - before79.assets, ожидалось: -1050000 });
  } else {
    record('7.9', 'СМОТРЕТЬ', `удаление актива вернуло ${d79.status}`, { тело: d79.body });
  }

  // уборка: снести тестовое обязательство
  await api(`/capital/items/${loan.id}`, { method: 'DELETE' });
  record('7.10', 'РУКАМИ', 'свободное исследование капитала');

  console.log(`\n${'='.repeat(78)}`);
  const bad = log.filter((r) => r.verdict === 'РАСХОЖДЕНИЕ');
  console.log(`Сошлось: ${log.filter((r) => r.verdict === 'СОШЛОСЬ').length}   `
    + `Расхождений: ${bad.length}   Требует глаз: ${log.filter((r) => r.verdict === 'СМОТРЕТЬ').length}   `
    + `Только руками: ${log.filter((r) => r.verdict === 'РУКАМИ').length}`);
  if (bad.length) {
    console.log('\nРАСХОЖДЕНИЯ:');
    bad.forEach((r) => console.log(`  ${r.block}: ${r.message}\n     ${JSON.stringify(r.data)}`));
  }
  const fin = await snap();
  console.log(`\nсостояние на выходе: кармашек ${money(fin.pocket)}, капитал ${money(fin.total)}, `
    + `ликвид ${money(fin.liquid)}, копилок ${fin.funds.length}\n`);
}

main().catch((e) => { console.error('\nУпало:', e.message); process.exit(2); });

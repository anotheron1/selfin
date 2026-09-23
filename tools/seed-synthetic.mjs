#!/usr/bin/env node
/**
 * Синтетические данные для проверки миграций в CI (ANO-26).
 *
 * Пишет ТОЛЬКО через API — значит, создаёт только те состояния, которые продукт умеет
 * создавать сам, и не даёт ложных красных на невозможных данных. Реальных данных здесь нет:
 * имена выдуманы, суммы круглые. Даты — от сегодняшнего дня в часовом поясе окружения
 * (в CI — Europe/Moscow, как в боевом docker-compose.yml).
 *
 *   node tools/seed-synthetic.mjs [--api http://localhost:8099/api/v1]
 *
 * Пишет только в чистую базу: если в журнале уже есть события, отказывается. Порт по
 * умолчанию нарочно не 8080 и не 8081 — там боевой бэкенд и стенд.
 *
 * Любой ответ не из 2xx — выход с кодом 1. Сеятель обязан краснеть, когда API main перестал
 * принимать то, что принимал: это и есть смоук-проверка API.
 *
 * Состав и зачем он такой — docs/superpowers/specs/2026-09-23-ci-on-pr-design.md, раздел 4.
 */

const args = process.argv.slice(2);
const apiArg = args.indexOf('--api');
const API = apiArg >= 0 ? args[apiArg + 1] : 'http://localhost:8099/api/v1';

// Дата по местному времени, а не через toISOString: та выдаёт дату в UTC и у полуночи
// сдвигает её на день — та же ловушка, что сторожит frontend/src/lib/factDate.test.ts.
const pad = (n) => String(n).padStart(2, '0');
const iso = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const NOW = new Date();
const today = iso(NOW);
const day = (offset) => iso(new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() + offset, 12));
/** День месяца со сдвигом на monthOffset месяцев; 31-е в коротком месяце прижимается к последнему дню. */
const monthDay = (monthOffset, dom) => {
  const first = new Date(NOW.getFullYear(), NOW.getMonth() + monthOffset, 1, 12);
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  return iso(new Date(first.getFullYear(), first.getMonth(), Math.min(dom, last), 12));
};

let calls = 0;
async function call(method, path, body) {
  calls++;
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (method === 'POST') init.headers['Idempotency-Key'] = crypto.randomUUID();
  const res = await fetch(API + path, init);
  const text = await res.text();
  if (!res.ok) {
    console.error(`${method} ${path} -> ${res.status}: ${text.slice(0, 500)}`);
    process.exit(1);
  }
  return text ? JSON.parse(text) : null;
}
const get = (p) => call('GET', p);
const post = (p, b) => call('POST', p, b);
const put = (p, b) => call('PUT', p, b);
const patch = (p, b) => call('PATCH', p, b);
const del = (p) => call('DELETE', p);

/** PUT /events перезаписывает событие целиком (ANO-162), поэтому тело собирается из всех полей. */
const eventBody = (e, override) => ({
  date: e.date,
  categoryId: e.categoryId,
  type: e.type,
  plannedAmount: e.plannedAmount,
  priority: e.priority,
  description: e.description,
  rawInput: e.rawInput,
  targetFundId: e.targetFundId,
  ...override,
});

// Без дат ручка журнала может отдать не всё, поэтому окно задаётся заведомо шире данных.
const EVERYTHING = '/events?startDate=2000-01-01&endDate=2100-12-31';

// ── Только чистая база ──────────────────────────────────────────────────────
if ((await get(EVERYTHING)).length > 0) {
  console.error(`В журнале ${API} уже есть события. Сеятель пишет только в чистую базу.`);
  process.exit(2);
}

// ── Категории: стандартные из миграции V2 плюс свои ───────────────────────────
const categories = await get('/categories');
async function category(name, type, priority) {
  const found = categories.find((c) => c.name === name);
  if (found) return found;
  const created = await post('/categories', { name, type, priority });
  categories.push(created);
  return created;
}
const salary = await category('Зарплата', 'INCOME', 'HIGH');
const loan = await category('Кредит / Долг', 'EXPENSE', 'HIGH');
const internet = await category('Интернет / Связь', 'EXPENSE', 'HIGH');
const groceries = await category('Еда / Продукты', 'EXPENSE', 'MEDIUM');
const cafe = await category('Кафе / Рестораны', 'EXPENSE', 'MEDIUM');
const health = await category('Здоровье', 'EXPENSE', 'MEDIUM');
const other = await category('Прочее', 'EXPENSE', 'LOW');
const club = await category('Кружок', 'EXPENSE', 'MEDIUM');
const hobby = await category('Старое хобби', 'EXPENSE', 'LOW');
await patch(`/categories/${health.id}/priority`);

// ── Счета и сверки остатка ──────────────────────────────────────────────────
const accounts = await get('/accounts');
const card = accounts.find((a) => a.isDefault) ?? accounts[0];
const savingsAccount = await post('/accounts', { name: 'Накопительный', kind: 'DEBIT', trackBalance: true, sortOrder: 2 });
await post('/accounts', { name: 'Наличные', kind: 'DEBIT', trackBalance: false, sortOrder: 3 });

await post('/balance-checkpoints', { date: day(-60), amount: 180000, accountId: card.id });
await post('/balance-checkpoints', { date: day(-10), amount: 142500, accountId: card.id });
await post('/balance-checkpoints', { date: day(-30), amount: 250000, accountId: savingsAccount.id });

// ── Прошлые месяцы: разовые планы с фактами ─────────────────────────────────
// Правило нельзя начать в прошлом (инвариант I3: startDate не раньше сегодня), поэтому
// история — разовые планы, как у того, кто завёл цепочку не с первого месяца. Ипотека —
// без описания, как у владельца; связь в прошлом месяце — с превышением.
const history = [
  { dom: 10, category: salary, type: 'INCOME', amount: 120000, description: 'Зарплата' },
  { dom: 25, category: salary, type: 'INCOME', amount: 80000, description: 'Аванс' },
  { dom: 5, category: loan, type: 'EXPENSE', amount: 45000 },
  { dom: 31, category: internet, type: 'EXPENSE', amount: 800, description: 'Интернет' },
];
for (let m = -6; m <= 0; m++) {
  for (const h of history) {
    const date = monthDay(m, h.dom);
    if (date >= today) continue;
    const plan = await post('/events', {
      date, categoryId: h.category.id, type: h.type, plannedAmount: h.amount,
      priority: 'HIGH', description: h.description,
    });
    const overpaid = h.dom === 31 && m === -1;
    await post(`/events/${plan.id}/facts`, { date, factAmount: overpaid ? 850 : h.amount });
  }
}

// ── Повторяющиеся правила — с сегодняшнего дня ──────────────────────────────
const monthly = (dom, endDate = null) => ({ frequency: 'MONTHLY', dayOfMonth: dom, startDate: today, endDate });

await post('/events', {
  date: today, categoryId: salary.id, type: 'INCOME', plannedAmount: 120000,
  priority: 'HIGH', description: 'Зарплата', recurring: monthly(10),
});
await post('/events', {
  date: today, categoryId: salary.id, type: 'INCOME', plannedAmount: 80000,
  priority: 'HIGH', description: 'Аванс', recurring: monthly(25),
});
// На двадцать лет вперёд — генерация ограничена двенадцатью месяцами, правило продлевается само.
const mortgageRule = await post('/events', {
  date: today, categoryId: loan.id, type: 'EXPENSE', plannedAmount: 45000,
  priority: 'HIGH', recurring: monthly(5, monthDay(240, 5)),
});
// 31-е прижимается к последнему дню короткого месяца.
const internetRule = await post('/events', {
  date: today, categoryId: internet.id, type: 'EXPENSE', plannedAmount: 800,
  priority: 'HIGH', description: 'Интернет', recurring: monthly(31),
});
// Звено на сегодня — на него ляжет факт: факт, унаследовавший правило (форма V23).
const clubRule = await post('/events', {
  date: today, categoryId: club.id, type: 'EXPENSE', plannedAmount: 6000,
  priority: 'MEDIUM', description: 'Бассейн', recurring: monthly(NOW.getDate()),
});
const insuranceStart = monthDay(3, 20);
await post('/events', {
  date: insuranceStart, categoryId: other.id, type: 'EXPENSE', plannedAmount: 12000,
  priority: 'HIGH', description: 'Страховка',
  recurring: {
    frequency: 'YEARLY', dayOfMonth: 20, monthOfYear: Number(insuranceStart.slice(5, 7)),
    startDate: insuranceStart, endDate: null,
  },
});

// ── Разовые планы: продукты понедельно и без описания, кафе, мелкая хотелка ───
const monday = (weekOffset) => {
  const d = new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate(), 12);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7) + 7 * weekOffset);
  return iso(d);
};
const groceryPlans = [];
for (let w = -8; w <= 8; w++) {
  groceryPlans.push(await post('/events', {
    date: monday(w), categoryId: groceries.id, type: 'EXPENSE', plannedAmount: 8000, priority: 'MEDIUM',
  }));
}
const cafePast = await post('/events', {
  date: day(-20), categoryId: cafe.id, type: 'EXPENSE', plannedAmount: 2000, priority: 'MEDIUM',
});
await post('/events', {
  date: day(12), categoryId: cafe.id, type: 'EXPENSE', plannedAmount: 3000, priority: 'MEDIUM',
  description: 'День рождения друга',
});
await post('/events', {
  date: day(25), categoryId: other.id, type: 'EXPENSE', plannedAmount: 3500, priority: 'LOW',
  description: 'Настольная игра',
});
const hobbyPlan = await post('/events', {
  date: day(-15), categoryId: hobby.id, type: 'EXPENSE', plannedAmount: 4000, priority: 'LOW',
  description: 'Краски',
});

// ── Факты ───────────────────────────────────────────────────────────────────
const journal = await get(`/events?startDate=${monthDay(-7, 1)}&endDate=${monthDay(14, 28)}`);
const chain = (rule) => journal
  .filter((e) => e.recurringRuleId === rule.recurringRuleId && e.eventKind === 'PLAN')
  .sort((a, b) => a.date.localeCompare(b.date));
const past = (events) => events.filter((e) => e.date <= today);
const future = (events) => events.filter((e) => e.date > today);

const clubToday = chain(clubRule).find((e) => e.date === today);
await post(`/events/${clubToday.id}/facts`, { date: today, factAmount: 6000 });

// Продукты: полный, частичный, с превышением, два факта на один план; последние недели без фактов.
const groceryFacts = [[8000], [5000], [9500], [3000, 4000], [8000], [6500]];
const pastGroceries = past(groceryPlans);
for (let i = 0; i < Math.min(groceryFacts.length, pastGroceries.length); i++) {
  for (const amount of groceryFacts[i]) {
    await post(`/events/${pastGroceries[i].id}/facts`, { date: pastGroceries[i].date, factAmount: amount });
  }
}
// Факт без плана — без описания и с выражением вместо числа.
await post('/events/facts', { date: day(-3), categoryId: cafe.id, type: 'EXPENSE', factAmount: 1450 });
await post('/events/facts', {
  date: day(-1), categoryId: health.id, type: 'EXPENSE', factAmount: 1550,
  description: 'Аптека', rawInput: '1200+350',
});
// Старый путь записи факта прямо в план (ANO-25).
await patch(`/events/${cafePast.id}/fact`, { factAmount: 2100 });
await post(`/events/${hobbyPlan.id}/facts`, { date: hobbyPlan.date, factAmount: 3800 });

// ── Правки цепочек ──────────────────────────────────────────────────────────
const mortgageAhead = future(chain(mortgageRule));
await put(`/events/${mortgageAhead[2].id}?scope=FOLLOWING`, eventBody(mortgageAhead[2], { plannedAmount: 43000 }));
const internetAhead = future(chain(internetRule));
await put(`/events/${internetAhead[1].id}?scope=THIS`, eventBody(internetAhead[1], { date: day(40) }));
await del(`/events/${internetAhead[3].id}?scope=THIS`);
// Смена твёрдости у одной строки и удалённый разовый план.
await patch(`/events/${groceryPlans[12].id}/priority`);
const cancelled = await post('/events', {
  date: day(30), categoryId: cafe.id, type: 'EXPENSE', plannedAmount: 5000, priority: 'MEDIUM',
  description: 'Отменённый ужин',
});
await del(`/events/${cancelled.id}`);

// Категория, на которую легли события, уходит в удалённые (форма ANO-178).
await del(`/categories/${hobby.id}`);

// ── Копилки ─────────────────────────────────────────────────────────────────
const cushion = await post('/funds', { name: 'Подушка', targetAmount: 300000, priority: 1 });
for (let i = 0; i < 3; i++) await post(`/funds/${cushion.id}/transfer`, { amount: 15000, confirm: true });
await post('/events', {
  date: monthDay(1, 12), type: 'FUND_TRANSFER', plannedAmount: 10000, priority: 'HIGH',
  targetFundId: cushion.id,
});
// Переводы до привязки к счёту — форма ANO-163.
const vacationBody = { name: 'Отпуск', targetAmount: 150000, priority: 2, targetDate: monthDay(8, 1) };
const vacation = await post('/funds', vacationBody);
await post(`/funds/${vacation.id}/transfer`, { amount: 20000, confirm: true });
await put(`/funds/${vacation.id}`, { ...vacationBody, accountId: savingsAccount.id });
await post('/funds', {
  name: 'Ноутбук', targetAmount: 90000, priority: 3, targetDate: monthDay(2, 1),
  purchaseType: 'CREDIT', creditRate: 19.9, creditTermMonths: 12,
});
const gift = await post('/funds', { name: 'Подарок', targetAmount: 10000, priority: 4 });
await post(`/funds/${gift.id}/transfer`, { amount: 10000, confirm: true });
const bike = await post('/funds', { name: 'Велосипед', targetAmount: 40000, priority: 5 });
await post(`/funds/${bike.id}/transfer`, { amount: 5000, confirm: true });
await del(`/funds/${bike.id}?money=RETURN`);

// ── Хотелки: открытая, зафиксированная без конверсии, отклонённая, две сконвертированные ──
await post('/events/wishlist', { description: 'Самокат', plannedAmount: 25000 });
const chair = await post('/events/wishlist', { description: 'Кресло', plannedAmount: 18000, date: monthDay(4, 10) });
await patch(`/events/${chair.id}/wishlist-status`, { status: 'FIXED' });
const phone = await post('/events/wishlist', { description: 'Новый телефон', plannedAmount: 60000, date: monthDay(5, 1) });
await patch(`/events/${phone.id}/wishlist-status`, { status: 'DISMISSED' });
const course = await post('/events/wishlist', { description: 'Курс английского', plannedAmount: 40000, date: monthDay(3, 1) });
await post(`/wishlist/items/${course.id}/convert`, { sourceKind: 'WISHLIST', target: 'PLAN_EVENT', planDate: monthDay(3, 1) });
const tent = await post('/events/wishlist', { description: 'Палатка', plannedAmount: 15000, date: monthDay(6, 1) });
await post(`/wishlist/items/${tent.id}/convert`, {
  sourceKind: 'WISHLIST', target: 'FUND', fundTargetDate: monthDay(6, 1), createRecurringPayments: false,
});

// ── Настройки, капитал, снимок ──────────────────────────────────────────────
await put('/settings/pocket', { bufferAmount: 10000 });
await put('/settings/wishlist', { capitalThresholdRub: 500000, cashBufferMonths: 2 });
const flat = await post('/capital/items', { kind: 'ASSET', name: 'Квартира', initialValue: 8000000, initialValuedAt: day(-365) });
await post(`/capital/items/${flat.id}/revaluations`, { value: 8400000, valuedAt: day(-30), note: 'оценка' });
await post('/capital/items', { kind: 'LIABILITY', name: 'Остаток ипотеки', initialValue: 5200000, initialValuedAt: day(-365) });
await post(`/snapshots?date=${today}`);

// ── Итог ────────────────────────────────────────────────────────────────────
const events = await get(EVERYTHING);
const plans = events.filter((e) => e.eventKind === 'PLAN').length;
console.log(`посеяно за ${calls} запросов к ${API}: живых событий ${events.length} `
  + `(планов ${plans}, фактов ${events.length - plans}), сегодня ${today}`);

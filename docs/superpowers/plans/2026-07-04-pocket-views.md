# Pocket Views (ANO-12, шаги 3–4) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dashboard показывает кармашек из `/pocket`, кассовый календарь рендерится из `trajectory` того же ответа (календарь-близнец) — закрытие шагов 3–4 спеки §8 и структурное устранение ANO-6.

**Architecture:** Расширяем `TrajectoryPoint` дневными суммами (`income`, `expense`) — спека §3.6 (дополнение 2026-07-04). Dashboard добавляет готовый `PocketCard` и переводит `CashFlowSection` с `analytics.cashFlow` на `pocket.trajectory`; запрос `/analytics/report` с Dashboard уходит. Бэкенд-расчёт `cashFlow` в AnalyticsService НЕ трогаем (ревизия Analytics — вне скоупа).

**Tech Stack / прогоны:** как в плане 2026-07-02 (maven из `backend/`, JAVA_HOME jbr-21.0.8; `npm run build` из `frontend/`). Ветка `feature/pocket-views` (создана от main после merge PR #13).

---

## Chunk 1: всё (изменение маленькое — один чанк)

### Task 1: TrajectoryPoint с дневными суммами (движок, TDD)

**Files:**
- Modify: `backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java` (nested record TrajectoryPoint)
- Modify: `backend/src/main/java/ru/selfin/backend/service/PocketEngine.java` (день 0 + цикл)
- Test: `backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java`

- [ ] **Step 1: Добавить падающий тест** в `PocketEngineTest`:

```java
    @Test
    @DisplayName("Траектория несёт дневные суммы: income/expense по дням, прогноз входит в expense")
    void trajectoryDailySums() {
        // Чекпоинт 10 000; просрочка 1 000; расход сегодня 500; 5.03 доход 20 000 и расход 3 000;
        // прогноз 1 400 на окно 2.03..15.03 (14 дней → 100/день)
        PocketInput in = base()
                .overdue(plan(EventType.EXPENSE, LocalDate.of(2026, 2, 20), 1_000, Priority.HIGH))
                .events(plan(EventType.EXPENSE, TODAY, 500, Priority.MEDIUM),
                        plan(EventType.INCOME, LocalDate.of(2026, 3, 5), 20_000, Priority.HIGH),
                        plan(EventType.EXPENSE, LocalDate.of(2026, 3, 5), 3_000, Priority.MEDIUM))
                .forecast(1_400, "Продукты")
                .build();
        PocketResultDto r = PocketEngine.calculate(in);

        PocketResultDto.TrajectoryPoint day0 = r.trajectory().get(0);
        assertThat(day0.income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(day0.expense()).isEqualByComparingTo(dec(1_500)); // просрочка 1 000 + сегодня 500

        PocketResultDto.TrajectoryPoint mar5 = r.trajectory().stream()
                .filter(p -> p.date().equals(LocalDate.of(2026, 3, 5))).findFirst().orElseThrow();
        assertThat(mar5.income()).isEqualByComparingTo(dec(20_000));
        assertThat(mar5.expense()).isEqualByComparingTo(dec(3_100)); // 3 000 + 100 прогноза

        // Инвариант: balance(i) = balance(i-1) + income(i) − expense(i) — на каждой точке после нулевой
        for (int i = 1; i < r.trajectory().size(); i++) {
            PocketResultDto.TrajectoryPoint prev = r.trajectory().get(i - 1);
            PocketResultDto.TrajectoryPoint cur = r.trajectory().get(i);
            assertThat(prev.balance().add(cur.income()).subtract(cur.expense()))
                    .isEqualByComparingTo(cur.balance());
        }
    }
```

- [ ] **Step 2: Прогнать — падает** (у TrajectoryPoint нет income/expense)

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test -Dtest=PocketEngineTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Реализовать**

`PocketResultDto`: `public record TrajectoryPoint(LocalDate date, BigDecimal balance, BigDecimal income, BigDecimal expense) {}`

`PocketEngine.calculate`, п.5:
- День 0: `trajectory.add(new ...TrajectoryPoint(in.asOfDate(), running, BigDecimal.ZERO, overdue.add(todayExpenses)));`
- В цикле: завести `BigDecimal dayIncome = ZERO, dayExpense = ZERO` в начале итерации дня; в ветке INCOME — `dayIncome = dayIncome.add(amount)`, иначе `dayExpense = dayExpense.add(amount)`; дневную долю прогноза (`dayForecast`) прибавлять к `dayExpense`; точка дня: `new ...TrajectoryPoint(d, running, dayIncome, dayExpense)`.

- [ ] **Step 4: Прогнать — зелёный** (15 тестов), затем полный юнит-набор

Run: `cd backend && JAVA_HOME="/c/Users/Kirill/.jdks/jbr-21.0.8" ./mvnw test`
Expected: BUILD SUCCESS (все зелёные — dev-БД починена 2026-07-03)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/selfin/backend/dto/pocket/PocketResultDto.java backend/src/main/java/ru/selfin/backend/service/PocketEngine.java backend/src/test/java/ru/selfin/backend/service/PocketEngineTest.java
git commit -m "feat(pocket): daily income/expense sums in trajectory points (ANO-12 step 4 prep)"
```

### Task 2: Dashboard на PocketCard + календарь-близнец

**Files:**
- Modify: `frontend/src/types/api.ts` (trajectory в PocketResponse)
- Modify: `frontend/src/pages/Dashboard.tsx`

- [ ] **Step 1: Тип** — в `PocketResponse.trajectory`: `{ date: string; balance: number; income: number; expense: number }[]`.

- [ ] **Step 2: Dashboard.tsx**

1. Импорт: `PocketCard` (default) + `PocketResponse` (type); добавить стейт `const [pocket, setPocket] = useState<PocketResponse | null>(null);`.
2. Из `loadAll`/Promise.all убрать `fetchAnalyticsReport()` и стейт `analytics` (его единственный потребитель — CashFlowSection); импорты `fetchAnalyticsReport`, `AnalyticsReport` удалить.
3. После hero-блока (после закрывающего `</div>` баланса, перед «Алерт кассового разрыва») вставить: `<PocketCard onData={setPocket} refreshSignal={refreshSignal} />`.
4. `CashFlowSection`: пропсы → `{ trajectory: PocketResponse['trajectory'] }`; рендер по дням траектории (включая день 0 — подписать «сегодня» через `fmtDay`), `isGap` = `day.balance < 0`, суммы дня из `day.income`/`day.expense` (показывать строку, если любая > 0). Вызов: `{pocket && <CashFlowSection trajectory={pocket.trajectory} />}`.
5. Блок зарплатных горизонтов, cashGapAlert, прогресс-бары — НЕ трогать (спека §8.3).

Готовый рендер календаря (замена тела CashFlowSection):

```tsx
function CashFlowSection({ trajectory }: { trajectory: PocketResponse['trajectory'] }) {
    if (trajectory.length === 0) return null;
    const todayStr = trajectory[0].date;

    return (
        <div className="rounded-2xl p-5"
            style={{ background: 'var(--color-surface)', border: '1px solid var(--color-border)' }}>
            <h3 className="font-semibold text-sm mb-3" style={{ color: 'var(--color-text-muted)' }}>
                КАССОВЫЙ КАЛЕНДАРЬ
            </h3>
            <div className="flex gap-2 overflow-x-auto pb-2" style={{ scrollbarWidth: 'thin' }}>
                {trajectory.map(day => {
                    const isGap = day.balance < 0;
                    const balanceColor = isGap ? 'var(--color-danger)' : 'var(--color-success)';
                    return (
                        <div key={day.date}
                            className="flex-none rounded-xl p-2 text-center"
                            style={{
                                minWidth: '64px',
                                background: isGap ? 'rgba(239,68,68,0.12)' : 'var(--color-surface-2)',
                                border: isGap ? '1px solid var(--color-danger)' : '1px solid var(--color-border)',
                            }}>
                            <p className="text-xs mb-1" style={{ color: 'var(--color-text-muted)' }}>
                                {day.date === todayStr ? 'сегодня' : fmtDay(day.date)}
                            </p>
                            <p className="text-xs font-bold" style={{ color: balanceColor }}>
                                {fmt(day.balance)}
                            </p>
                            {(day.income > 0 || day.expense > 0) && (
                                <p className="mt-1" style={{ color: 'var(--color-text-muted)', fontSize: '10px' }}>
                                    {day.income > 0 && <span style={{ color: 'var(--color-success)' }}>+{fmt(day.income)}</span>}
                                    {day.expense > 0 && <span style={{ color: 'var(--color-danger)' }}>{' '}−{fmt(day.expense)}</span>}
                                </p>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
```

- [ ] **Step 3: Сборка** — `cd frontend && npm run build` → чисто. Проверить, что `CashFlowDay` в types/api.ts остаётся (тип отчёта аналитики, бэкенд его всё ещё отдаёт).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/pages/Dashboard.tsx
git commit -m "feat(dashboard): PocketCard + cash calendar as trajectory twin (ANO-12 steps 3-4, ANO-6)"
```

### Task 3: Верификация и финализация

- [ ] **Step 1:** Полный бэкенд-прогон + `npm run build` — зелёные.
- [ ] **Step 2:** Живая проверка: поднять бэкенд (dev-БД починена — обычный `spring-boot:run`) + vite; на Dashboard: PocketCard и календарь показывают ОДНО число (день минимума в календаре = minPoint кармашка); ввод события через FAB обновляет оба (refreshSignal) — это сценарий ANO-6 вживую.
- [ ] **Step 3:** Linear: коммент в ANO-12 (шаги 3–4 готовы); коммент в ANO-6 — структурно устранён (календарь = буквально тот же расчёт, что кармашек; тот же refresh), просьба к юзеру подтвердить на своих данных перед закрытием.
- [ ] **Step 4:** superpowers:finishing-a-development-branch → PR в main.

**Вне скоупа:** зарплатные горизонты Dashboard (ANO-13/14), ревизия AnalyticsService.cashFlow на бэке, полноценная подача кармашка (ANO-13/14).

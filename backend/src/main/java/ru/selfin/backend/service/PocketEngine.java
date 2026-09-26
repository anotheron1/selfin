package ru.selfin.backend.service;

import ru.selfin.backend.dto.pocket.BreakdownType;
import ru.selfin.backend.dto.pocket.EventSnapshot;
import ru.selfin.backend.dto.pocket.FallbackKind;
import ru.selfin.backend.dto.pocket.PocketInput;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.SyntheticKind;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.WishlistStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Чистый движок кармашка (спека 2026-07-02-pocket-core-design.md §3, §5).
 * Ни одного обращения к БД и Spring-зависимостей — только вход → выход.
 *
 * <p>Формула: кармашек(скоуп) = min прогнозной траектории баланса − буфер.
 * Breakdown-инвариант: STARTING − OVERDUE − EXPENSES(≤min) − CONTRIB(≤min) + INCOME(≤min)
 * − FORECAST(≤min) = MIN; MIN − BUFFER = POCKET (CONTRIB — взносы в копилки, ANO-16 §6).
 */
public final class PocketEngine {

    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Минимальное окно календаря (спека §3.9, ANO-24): короткий горизонт получает информационный хвост. */
    public static final int MIN_CALENDAR_DAYS = 7;

    /**
     * Конец траектории = max(horizonEnd, asOf + {@link #MIN_CALENDAR_DAYS}).
     * Хвост за горизонтом — чисто информационный: min/кармашек/breakdown его не видят.
     * Публичный, чтобы PocketService расширял выборку событий той же формулой.
     */
    public static LocalDate trajectoryEnd(LocalDate asOfDate, LocalDate horizonEnd) {
        LocalDate minEnd = asOfDate.plusDays(MIN_CALENDAR_DAYS);
        return horizonEnd.isBefore(minEnd) ? minEnd : horizonEnd;
    }

    private PocketEngine() {}

    public static PocketResultDto calculate(PocketInput in) {
        // 1. Текущий баланс: checkpoint + факты (правило §3.2), попавшие в окно якоря по
        //    asOfDate. Сумма якоря — «число из банка», и оно содержит операции, которые
        //    существовали В МОМЕНТ СВЕРКИ (ANO-15 §5, закрывает задвоение из §3.3). Записанное
        //    после ввода якоря в то число попасть не могло и считается (ANO-82).
        //    Граница окна больше не зеркалится по трём местам (ANO-23) — она одна и живёт в
        //    AnchorWindow; сюда её зовут так же, как AccountBalanceService.factsDelta и
        //    BalanceCheckpointService.findAll() (дрейф).
        BigDecimal currentBalance = in.checkpointAmount();
        for (EventSnapshot e : in.events()) {
            if (e.wishlistStatus() != null || e.factAmount() == null || e.date() == null) continue;
            if (!AnchorWindow.countsTowardBalance(e.date(), e.createdAt(),
                    in.checkpointDate(), in.checkpointCreatedAt(), in.asOfDate())) continue;
            currentBalance = currentBalance.add(signed(e.type(), e.factAmount()));
        }
        // Прочие счета (спека §4.1): их остатки уже посчитаны сборщиком входа,
        // безадресные факты к ним не применяются — они относятся к дефолтному счёту.
        currentBalance = currentBalance.add(in.otherAccountsBalanceOrZero());

        // ANO-155: сколько по каждому плану уже погашено фактами. Факт не замещает
        // обязательство, а уменьшает его на свою сумму — как «частично оплачен» в
        // платёжном календаре. Раньше первый же факт снимал план целиком, и чек на 300
        // освобождал резерв продуктов на 20 000.
        Map<UUID, BigDecimal> settled = PlanRemainder.settledBySnapshots(in.events());

        // 2. День 0: − резерв просрочки − плановые расходы сегодняшнего дня.
        //    Плановые доходы с датой ≤ asOfDate НЕ учитываются (консервативная асимметрия §3.3.2).
        BigDecimal overdue = in.overdueEvents().stream()
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayExpenses = in.events().stream()
                .filter(e -> isPendingPlan(e, settled))
                .filter(e -> e.wishlistStatus() == null)
                .filter(e -> in.asOfDate().equals(e.date()) && e.type() != EventType.INCOME)
                .map(e -> remainderOf(e, settled))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2а. ANO-119: те же строки, что удержаны выше и ниже, — списком для экрана.
        //     Собирается здесь, а не отдельной выборкой рядом: правило отбора, живущее
        //     в двух экземплярах, однажды расходится (ANO-82, ANO-155). Фильтры взяты
        //     ровно те же, что у сумм, — включая «сегодня хотелки не держим».
        //     Хвост за горизонтом (§3.9) в список НЕ идёт: он информационный, кармашек
        //     его не вычитает, и показывать его как «осталось потратить» значило бы
        //     обещать вычет, которого нет.
        List<PocketResultDto.UpcomingItem> upcoming = new ArrayList<>();
        for (EventSnapshot e : in.overdueEvents()) {
            upcoming.add(upcomingOf(e,
                    e.plannedAmount() != null ? e.plannedAmount() : BigDecimal.ZERO, true));
        }
        // ANO-185: строки впереди — сегодня и до конца горизонта — одним списком. Из него
        // и «осталось потратить», и признак «есть ли в плане ожидание»: по тем строкам,
        // которыми кармашек держит деньги, и судим, полон ли план.
        List<EventSnapshot> ahead = new ArrayList<>();
        in.events().stream()
                .filter(e -> isPendingPlan(e, settled))
                .filter(e -> e.wishlistStatus() == null)
                .filter(e -> in.asOfDate().equals(e.date()) && e.type() != EventType.INCOME)
                .forEach(ahead::add);
        in.events().stream()
                .filter(e -> isPendingPlan(e, settled))
                .filter(PocketEngine::allowedInTrajectory)
                .filter(e -> e.type() != EventType.INCOME)
                .filter(e -> e.date() != null
                        && e.date().isAfter(in.asOfDate()) && !e.date().isAfter(in.horizonEnd()))
                .sorted(java.util.Comparator.comparing(EventSnapshot::date))
                .forEach(ahead::add);
        ahead.forEach(e -> upcoming.add(upcomingOf(e, remainderOf(e, settled), false)));
        // Ожидание — расход в плане человека с характером «Ожидание». Перевод в копилку и
        // синтетика носят этот характер не по выбору человека: переводу форма ставит его
        // принудительно, а взносы кармашка заводит сам продукт (ревью Codex #63).
        boolean planHasExpectations = ahead.stream()
                .anyMatch(e -> e.type() == EventType.EXPENSE && e.syntheticKind() == null
                        && e.priority() == Priority.MEDIUM);

        // 3. Прогноз незапланированных по дням: текущий месяц (§3.5) + будущие месяцы (ANO-36).
        //    Обе части — одна и та же величина «сверх плана», просто из разных источников:
        //    текущий месяц из дневного темпа, будущие — из медианы по истории.
        Map<LocalDate, BigDecimal> forecastByDay = buildForecastByDay(in);

        // 4. Плановые события будущих дней (фильтр хотелок §3.2 применён).
        //    Диапазон — до конца траектории с хвостом (§3.9), не только до горизонта.
        LocalDate trajEnd = trajectoryEnd(in.asOfDate(), in.horizonEnd());
        Map<LocalDate, List<EventSnapshot>> futureByDay = in.events().stream()
                .filter(e -> isPendingPlan(e, settled))
                .filter(PocketEngine::allowedInTrajectory)
                .filter(e -> e.date() != null
                        && e.date().isAfter(in.asOfDate()) && !e.date().isAfter(trajEnd))
                .collect(Collectors.groupingBy(EventSnapshot::date));

        // 5. Траектория + минимум + суммы-до-минимума (для breakdown-инварианта §5).
        List<PocketResultDto.TrajectoryPoint> trajectory = new ArrayList<>();
        BigDecimal running = currentBalance.subtract(overdue).subtract(todayExpenses);
        trajectory.add(new PocketResultDto.TrajectoryPoint(
                in.asOfDate(), running, BigDecimal.ZERO, overdue.add(todayExpenses), null));

        BigDecimal minBalance = running;
        LocalDate minDate = in.asOfDate();
        String minDrivenBy = null;
        BigDecimal expensesCum = todayExpenses;
        BigDecimal incomeCum = BigDecimal.ZERO;
        BigDecimal contribCum = BigDecimal.ZERO;
        java.util.LinkedHashSet<String> contribNames = new java.util.LinkedHashSet<>();
        BigDecimal expensesAtMin = todayExpenses;
        BigDecimal incomeAtMin = BigDecimal.ZERO;
        BigDecimal contribAtMin = BigDecimal.ZERO;
        List<String> contribNamesAtMin = List.of();

        // ANO-80. Вторая кумулята: тот же бегущий остаток, но с прогнозом. Главное число
        // кармашка считается по ПЕРВОЙ — предположение не входит в сумму, которой человек
        // распоряжается, — а вторая даёт оговорку «с обычными тратами» и отдельный, более
        // ранний минимум для мягкой плашки разрыва.
        BigDecimal runningForecast = running;
        BigDecimal minForecast = runningForecast;
        LocalDate minForecastDate = in.asOfDate();
        String minForecastDrivenBy = null;
        boolean hasForecast = false;

        for (LocalDate d = in.asOfDate().plusDays(1); !d.isAfter(trajEnd); d = d.plusDays(1)) {
            BigDecimal dayIncome = BigDecimal.ZERO;
            BigDecimal dayExpense = BigDecimal.ZERO;
            BigDecimal dayTopExpenseAmount = BigDecimal.ZERO;
            String dayTopExpense = null;
            for (EventSnapshot e : futureByDay.getOrDefault(d, List.of())) {
                // ANO-155: удерживается непогашенный остаток, а не полная плановая сумма.
                BigDecimal amount = remainderOf(e, settled);
                if (e.type() == EventType.INCOME) {
                    dayIncome = dayIncome.add(amount);
                    incomeCum = incomeCum.add(amount);
                    running = running.add(amount);
                    runningForecast = runningForecast.add(amount);
                } else {
                    dayExpense = dayExpense.add(amount);
                    // Взносы в копилки (ANO-16 §6) — своя строка breakdown, не PLANNED_EXPENSES
                    if (e.syntheticKind() == SyntheticKind.SAVINGS_CONTRIBUTION) {
                        contribCum = contribCum.add(amount);
                        if (e.description() != null) contribNames.add(e.description());
                    } else {
                        expensesCum = expensesCum.add(amount);
                    }
                    running = running.subtract(amount);
                    runningForecast = runningForecast.subtract(amount);
                    if (amount.compareTo(dayTopExpenseAmount) > 0) {
                        dayTopExpenseAmount = amount;
                        dayTopExpense = e.description();
                    }
                }
            }
            // Прогноз двигает ТОЛЬКО вторую кумуляту. В dayExpense он больше не подмешивается:
            // поле означает расход по планам, как и написано на его этикетке.
            BigDecimal dayForecast = forecastByDay.getOrDefault(d, BigDecimal.ZERO);
            if (dayForecast.signum() != 0) {
                hasForecast = true;
                runningForecast = runningForecast.subtract(dayForecast);
            }
            trajectory.add(new PocketResultDto.TrajectoryPoint(d, running, dayIncome, dayExpense,
                    hasForecast ? runningForecast : null));
            // Минимум ищем ТОЛЬКО внутри горизонта — хвост информационный (§3.9)
            if (!d.isAfter(in.horizonEnd())) {
                if (running.compareTo(minBalance) < 0) {
                    minBalance = running;
                    minDate = d;
                    minDrivenBy = dayTopExpense;
                    expensesAtMin = expensesCum;
                    incomeAtMin = incomeCum;
                    contribAtMin = contribCum;
                    contribNamesAtMin = List.copyOf(contribNames);
                }
                if (runningForecast.compareTo(minForecast) < 0) {
                    minForecast = runningForecast;
                    minForecastDate = d;
                    minForecastDrivenBy = dayTopExpense;
                }
            }
        }

        BigDecimal buffer = in.bufferAmount() != null ? in.bufferAmount() : BigDecimal.ZERO;
        BigDecimal pocket = minBalance.subtract(buffer);

        // ANO-80: второе число и его минимум. null, а не ноль: «оговаривать нечего» и
        // «оговорка равна нулю» — для экрана одно и то же, и null избавляет фронт от решения.
        BigDecimal pocketWithForecast = hasForecast ? minForecast.subtract(buffer) : null;
        PocketResultDto.MinPoint minPointWithForecast = hasForecast
                ? new PocketResultDto.MinPoint(minForecastDate, minForecast, minForecastDrivenBy)
                : null;

        // 5а. Второе и третье числа (ANO-9 §4.2, §4.3). Оба — оговорки к кармашку, а не
        //     части его вычисления: pocket выше уже посчитан и ниже не меняется.
        //     null вместо нуля осознанно: «оговаривать нечего» и «оговорка равна нулю» — для
        //     экрана одно и то же, а null избавляет фронт от решения, показывать ли строку.
        BigDecimal creditReserve = in.creditRestoreReserveOrZero();
        BigDecimal pocketAfterCreditRestore = creditReserve.signum() > 0
                ? pocket.subtract(creditReserve) : null;
        BigDecimal semiLiquid = in.semiLiquidBalanceOrZero();
        BigDecimal pocketWithDeposits = semiLiquid.signum() > 0 ? pocket.add(semiLiquid) : null;

        // 6. Кандидаты-хотелки — ТОЛЬКО из отдельной выборки (§3.1): OPEN неконвертированные
        //    + FIXED-неконвертированные без даты. Датированные FIXED уже в траектории из events.
        //
        //    ANO-103: до V22 состояние «OPEN и при этом сконвертирована» было невозможно —
        //    его запрещало check-ограничение в базе, и потому здесь стояло «OPEN любые».
        //    V22 это ограничение снимает (возврат в обсуждение с сохранением артефакта —
        //    прямое обещание спеки), и без проверки !converted() вернувшаяся хотелка
        //    попала бы в кандидаты ВТОРОЙ раз: её план уже лежит в траектории. Это ровно
        //    форма ANO-106 — две неотличимые копии одного и того же на экране, плюс
        //    завышенная строка WISHLIST_INFO. Инвариант, на который код опирался молча,
        //    теперь записан здесь явно.
        List<PocketResultDto.WishlistCandidate> candidates = in.wishlistEvents().stream()
                .filter(e -> !e.converted())
                .filter(e -> e.wishlistStatus() == WishlistStatus.OPEN
                        || (e.wishlistStatus() == WishlistStatus.FIXED && e.date() == null))
                .map(e -> new PocketResultDto.WishlistCandidate(e.id(), e.description(),
                        e.plannedAmount(), e.date(), e.wishlistStatus() == WishlistStatus.FIXED))
                .toList();

        List<PocketResultDto.BreakdownLine> breakdown = buildBreakdown(in, currentBalance, overdue,
                expensesAtMin, incomeAtMin, contribAtMin, contribNamesAtMin,
                minBalance, minDate, buffer, pocket, candidates, creditReserve,
                pocketWithForecast != null ? pocketWithForecast.subtract(pocket) : null,
                minForecastDate);

        return new PocketResultDto(pocket, currentBalance, buffer, in.checkpointDate(),
                new PocketResultDto.Horizon(in.scope().type(), in.horizonEnd(),
                        horizonLabel(in), in.fallbackKind() != FallbackKind.NONE),
                new PocketResultDto.MinPoint(minDate, minBalance, minDrivenBy),
                breakdown, trajectory, candidates,
                pocketAfterCreditRestore, pocketWithDeposits,
                pocketWithForecast, minPointWithForecast, upcoming, planHasExpectations);
    }

    // ── правила фильтрации (спека §3.2) ─────────────────────────────────────

    /**
     * PLAN(PLANNED) с непогашенным остатком — он ещё стоит в пути денег.
     *
     * <p>ANO-155: условие «без факта» осталось, но значит теперь другое. {@code factAmount}
     * у самого плана — легаси-путь PATCH, такие строки удерживать нельзя (расход посчитался
     * бы дважды). А вот привязанные факты-дети гасят план ПОСТЕПЕННО, и пока остаток
     * положителен, план обязан удерживаться.
     */
    private static boolean isPendingPlan(EventSnapshot e, Map<UUID, BigDecimal> settled) {
        return e.factAmount() == null
                && e.eventKind() == EventKind.PLAN && e.status() == EventStatus.PLANNED
                && remainderOf(e, settled).signum() > 0;
    }

    /**
     * Строка списка «осталось потратить» (ANO-119). Имя категории движку не видно —
     * он работает на плоских снимках без JPA; подставляет {@code PocketService}.
     */
    private static PocketResultDto.UpcomingItem upcomingOf(EventSnapshot e, BigDecimal amount,
                                                           boolean overdue) {
        return new PocketResultDto.UpcomingItem(e.id(), e.date(), null, amount,
                e.description(), overdue, e.wishlistStatus() != null, e.priority(), e.type());
    }

    /** Непогашенная часть плана (ANO-155). Правило живёт в {@link PlanRemainder}, не здесь. */
    private static BigDecimal remainderOf(EventSnapshot e, Map<UUID, BigDecimal> settled) {
        // Синтетика (взносы копилок, примерка) детей не имеет — id у неё null.
        BigDecimal paid = e.id() == null ? null : settled.get(e.id());
        return PlanRemainder.of(e.plannedAmount(), paid);
    }

    /** Фильтр хотелок для траектории: обычные события + датированные FIXED-неконвертированные. */
    private static boolean allowedInTrajectory(EventSnapshot e) {
        if (e.wishlistStatus() == null) return true;
        return e.wishlistStatus() == WishlistStatus.FIXED && !e.converted() && e.date() != null;
    }

    private static BigDecimal signed(EventType type, BigDecimal amount) {
        return type == EventType.INCOME ? amount : amount.negate();
    }

    // ── breakdown (спека §5) ────────────────────────────────────────────────

    /**
     * Своё имя строки для {@code details} — или пустая строка, если его нет (ANO-101). Пустое
     * и пробельное описание — тоже «нет»: раньше ловился только null, и описание «» давало
     * висячую запятую. Подпись для человека (категория, «ещё N платежей») собирает фронт —
     * категорий движок не видит.
     */
    private static String ownName(EventSnapshot e) {
        return e.description() == null || e.description().isBlank() ? "" : e.description();
    }

    private static List<PocketResultDto.BreakdownLine> buildBreakdown(
            PocketInput in, BigDecimal currentBalance, BigDecimal overdue,
            BigDecimal expensesAtMin, BigDecimal incomeAtMin,
            BigDecimal contribAtMin, List<String> contribNames,
            BigDecimal minBalance, LocalDate minDate, BigDecimal buffer, BigDecimal pocket,
            List<PocketResultDto.WishlistCandidate> candidates, BigDecimal creditReserve,
            BigDecimal forecastDifference, LocalDate minForecastDate) {

        List<PocketResultDto.BreakdownLine> lines = new ArrayList<>();
        String minDateLabel = DD_MM.format(minDate);

        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.STARTING_BALANCE,
                in.checkpointDate() != null
                        ? "Остаток на счёте (чекпоинт " + DD_MM.format(in.checkpointDate()) + " + движение)"
                        : "Остаток на счёте (по событиям, чекпоинта нет)",
                currentBalance, List.of()));

        if (overdue.signum() != 0) {
            List<String> details = in.overdueEvents().stream().map(PocketEngine::ownName).toList();
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.OVERDUE_RESERVE,
                    "Брони с прошедшей датой (" + in.overdueEvents().size() + " шт)",
                    overdue.negate(), details));
        }
        if (expensesAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_EXPENSES,
                    "Плановые расходы до " + minDateLabel, expensesAtMin.negate(), List.of()));
        }
        if (contribAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.SAVINGS_CONTRIBUTIONS,
                    "Взносы в копилки (" + contribNames.size() + " шт)",
                    contribAtMin.negate(), contribNames));
        }
        if (incomeAtMin.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.PLANNED_INCOME,
                    "Плановые доходы до " + minDateLabel, incomeAtMin, List.of()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.TRAJECTORY_MIN,
                "Самый низкий остаток (" + minDateLabel + ")", minBalance, List.of()));
        if (buffer.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.BUFFER,
                    "НЗ (настройка)", buffer.negate(), List.of()));
        }
        lines.add(new PocketResultDto.BreakdownLine(BreakdownType.POCKET, "Кармашек", pocket, List.of()));

        // ANO-80: прогноз — оговорка, а не слагаемое кармашка. Стоит после POCKET по той же
        // причине, что WISHLIST_INFO и CREDIT_RESTORE: всё до кармашка объясняет, из чего
        // число сложилось, всё после — то, что человек может учесть, а может нет.
        //
        // В строке — РАЗНИЦА между двумя числами экрана, а не прогноз, накопленный к минимуму.
        // Минимумы стоят на разных днях, и «прогноз до минимума» с разницей чисел не сошёлся
        // бы: человек увидел бы две величины, которые не бьются. Разница сходится всегда.
        if (forecastDifference != null && forecastDifference.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.UNPLANNED_FORECAST,
                    "С обычными тратами до " + DD_MM.format(minForecastDate),
                    forecastDifference, in.forecastContributors()));
        }

        // ПОСЛЕ POCKET, рядом с WISHLIST_INFO: строка информационная и в инвариант не входит.
        // Порядок здесь — не про рендер, а про смысл: всё до кармашка объясняет, из чего он
        // сложился; всё после — оговорки, которые пользователь может учесть, а может нет.
        // ANO-79: разбивка объясняла состояние и никогда — изменение. Строка OVERDUE_RESERVE
        // после ре-якоря просто исчезала, и автор продукта не смог определить, верное ли у него
        // число. Теперь видно, сколько именно этот якорь снял с брони и почему. Сумма
        // ПОЛОЖИТЕЛЬНАЯ и без знака: это деньги, которые НЕ вычитаются, как у WISHLIST_INFO.
        List<EventSnapshot> released = in.releasedOverdueOrEmpty();
        BigDecimal releasedSum = released.stream()
                .map(EventSnapshot::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (releasedSum.signum() != 0 && in.checkpointDate() != null) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.OVERDUE_RELEASED,
                    "Больше не бронируется: остаток обновлён " + DD_MM.format(in.checkpointDate())
                            + " (" + released.size() + " шт)",
                    releasedSum,
                    released.stream().map(PocketEngine::ownName).toList()));
        }

        if (creditReserve.signum() > 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.CREDIT_RESTORE,
                    "Погасить карты до планки", creditReserve.negate(), List.of()));
        }

        BigDecimal wishlistSum = candidates.stream()
                .map(PocketResultDto.WishlistCandidate::plannedAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (wishlistSum.signum() != 0) {
            lines.add(new PocketResultDto.BreakdownLine(BreakdownType.WISHLIST_INFO,
                    "Хотелки-кандидаты (не вычтены)", wishlistSum, List.of()));
        }
        return lines;
    }

    /** Шаблоны label горизонта — без категорийных эвристик, правдивые в фолбэках (спека §4, ANO-14 §4). */
    private static String horizonLabel(PocketInput in) {
        if (in.fallbackKind() == FallbackKind.NO_INCOMES) return "30 дней вперёд (нет плановых доходов)";
        if (in.fallbackKind() == FallbackKind.SECOND_NOT_FOUND)
            return "до " + DD_MM.format(in.horizonEnd()) + " (второй доход не найден)";
        return switch (in.scope().type()) {
            case NEXT_INCOME -> "до дохода " + DD_MM.format(in.horizonEnd());
            case SECOND_INCOME -> "до 2-го дохода " + DD_MM.format(in.horizonEnd());
            case MONTHS -> in.scope().months() + " мес (до " + DD_MM.format(in.horizonEnd()) + ")";
            case DATE -> "до " + DD_MM_YYYY.format(in.horizonEnd());
        };
    }

    /**
     * Прогноз «сверх плана» по дням (ANO-36). Две части одной величины:
     * текущий месяц из дневного темпа (§3.5) и будущие месяцы из медианы по истории.
     *
     * <p>Только внутри горизонта: информационный хвост §3.9 прогнозом не нагружаем,
     * иначе минимум искался бы по данным, которых пользователь не запрашивал.
     */
    private static Map<LocalDate, BigDecimal> buildForecastByDay(PocketInput in) {
        Map<LocalDate, BigDecimal> byDay = new java.util.HashMap<>();
        LocalDate firstDay = in.asOfDate().plusDays(1);

        // Текущий месяц: прогноз — остаток месячной нормы, он приходится на asOf+1 .. конец месяца.
        // В горизонт попадает его доля: asOf+1 .. min(конец месяца, горизонт).
        LocalDate monthEnd = in.asOfDate().withDayOfMonth(in.asOfDate().lengthOfMonth());
        LocalDate curEnd = monthEnd.isBefore(in.horizonEnd()) ? monthEnd : in.horizonEnd();
        spreadShare(byDay, firstDay, curEnd, firstDay, monthEnd, in.unplannedForecast());

        // Будущие месяцы: прогноз — на весь месяц, в горизонт попадает доля обрезанного окна
        for (Map.Entry<java.time.YearMonth, BigDecimal> e : in.futureForecastOrEmpty().entrySet()) {
            LocalDate start = e.getKey().atDay(1);
            LocalDate end = e.getKey().atEndOfMonth();
            LocalDate from = start.isBefore(firstDay) ? firstDay : start;
            LocalDate to = end.isAfter(in.horizonEnd()) ? in.horizonEnd() : end;
            spreadShare(byDay, from, to, start, end, e.getValue());
        }
        return byDay;
    }

    /**
     * Сумма {@code total} приходится на дни [periodFrom..periodTo]; в окно [from..to] ложится её
     * доля по числу дней и ровно распределяется по ним.
     *
     * <p>ANO-140: раньше в окно ложилась вся сумма. На скоупе «до дохода» с горизонтом завтра
     * месячный прогноз сжимался в один день, и второе число занижалось в разы.
     */
    private static void spreadShare(Map<LocalDate, BigDecimal> byDay, LocalDate from, LocalDate to,
                                    LocalDate periodFrom, LocalDate periodTo, BigDecimal total) {
        if (total == null || total.signum() <= 0 || from.isAfter(to)) return;
        long windowDays = ChronoUnit.DAYS.between(from, to) + 1;
        long periodDays = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        BigDecimal share = windowDays >= periodDays ? total
                : total.multiply(BigDecimal.valueOf(windowDays))
                        .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
        spreadEvenly(byDay, from, to, share);
    }

    /** Ровно распределяет сумму по дням [from..to]; последний день добирает остаток копеек. */
    private static void spreadEvenly(Map<LocalDate, BigDecimal> byDay,
                                     LocalDate from, LocalDate to, BigDecimal total) {
        if (total == null || total.signum() <= 0 || from.isAfter(to)) return;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        BigDecimal daily = total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        BigDecimal spread = BigDecimal.ZERO;
        LocalDate d = from;
        for (long i = 0; i < days; i++, d = d.plusDays(1)) {
            BigDecimal amount = (i == days - 1) ? total.subtract(spread) : daily;
            spread = spread.add(amount);
            byDay.merge(d, amount, BigDecimal::add);
        }
    }
}

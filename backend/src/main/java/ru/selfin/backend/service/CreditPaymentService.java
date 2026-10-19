package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.model.enums.ScopeEnum;
import ru.selfin.backend.model.enums.SystemCategory;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * График платежей по кредиту на копилке-кредите (Р2-Б, ANO-40; спека
 * {@code 2026-10-05-credit-fix-payments-design.md}). Зафиксировал кредит — платежи бронью в плане;
 * отказался — будущие платежи сняты. Одно место на четырёх вызывающих: фиксация в примерке, диалог
 * «Что с капиталом», смена статуса и удаление копилки — разъехавшись, они дали бы два ответа на один
 * вопрос, как правило «остаток счёта» (ANO-23).
 */
@Service
@RequiredArgsConstructor
public class CreditPaymentService {

    private final FinancialEventRepository eventRepository;
    private final RecurringRuleService recurringRuleService;
    private final CategoryService categoryService;
    private final Clock clock;

    /**
     * Ставит график бронью на эту же копилку: MONTHLY-правило, первый платёж — через месяц после
     * покупки тем же днём, последний — через срок кредита. Стоявший график сначала снимается: двух
     * у копилки не бывает.
     *
     * <p>Ставится остаток графика (ANO-225): первый платёж — не раньше сегодняшнего дня (правило не
     * начинается в прошлом, I3) и после последнего внесённого платежа прежнего графика. Прошедшие
     * платежи — история, снятие их не трогает. Все платежи в прошлом — ставить нечего. У фиксации
     * покупка в будущем, и график, как раньше, — весь срок.
     *
     * <p>Платёж — аннуитет {@link SandboxLayout#monthlyPmt}, единственная формула PMT в проекте. Раньше
     * он брался из {@code computeCreditDelta} с горизонтом «срок + 1 месяц» — и у покупки за этим
     * горизонтом или в прошлом выходил нулём.
     *
     * @return id правила; {@code null}, если все платежи кредита в прошлом
     * @throws ResponseStatusException 400 без ставки, срока или даты покупки
     */
    @Transactional
    public UUID schedule(TargetFund credit) {
        requireCreditParams(credit);
        unschedule(credit.getId());
        int term = credit.getCreditTermMonths();
        LocalDate purchase = credit.getTargetDate();
        LocalDate first = firstPaymentFrom(purchase, resumeFrom(credit.getId()));
        LocalDate last = purchase.plusMonths(term);
        if (first.isAfter(last)) return null;
        BigDecimal pmt = SandboxLayout.monthlyPmt(credit.getTargetAmount(), credit.getCreditRate(), term);
        var cfg = new RecurringConfigDto(RecurringFrequency.MONTHLY, purchase.getDayOfMonth(), null, first, last);
        // ANO-188: у платежа по кредиту сумма и дата известны заранее — это бронь, как ипотека.
        // С «Ожиданием» пропущенный платёж выпадал из резерва (findOverdueMandatoryExpenses — только брони).
        return recurringRuleService.createFromDto(creditCategory(), EventType.EXPENSE, pmt, Priority.HIGH,
                credit.getName() + " — платёж по кредиту", credit.getId(), null, cfg).rule().getId();
    }

    /**
     * Снимает будущие платежи графика копилки — с сегодняшнего дня, тем же путём, что «эту и
     * следующие» в журнале. Прошедшие и исполненные остаются: это история. Без графика — ничего.
     */
    @Transactional
    public void unschedule(UUID fundId) {
        LocalDate today = LocalDate.now(clock);
        for (UUID ruleId : eventRepository.findCreditPaymentRuleIds(fundId)) {
            eventRepository
                    .findFirstByRecurringRuleIdAndDeletedFalseAndStatusAndDateGreaterThanEqualOrderByDateAsc(
                            ruleId, EventStatus.PLANNED, today)
                    .ifPresent(first -> recurringRuleService.deleteScope(first, ScopeEnum.FOLLOWING));
        }
    }

    /**
     * Переставляет действующий график по нынешним условиям кредита (ANO-225): правка суммы, даты
     * покупки, ставки или срока. Действующий — с незаплаченным платежом не раньше сегодняшнего дня.
     * График, снятый при отказе, и закончившийся правка условий не возвращает, а кредиту, зафиксированному
     * без графика, не заводит: без графика его зафиксировали явно.
     */
    @Transactional
    public void reschedule(TargetFund credit) {
        LocalDate today = LocalDate.now(clock);
        boolean active = eventRepository.findCreditPaymentRuleIds(credit.getId()).stream()
                .anyMatch(ruleId -> eventRepository
                        .findFirstByRecurringRuleIdAndDeletedFalseAndStatusAndDateGreaterThanEqualOrderByDateAsc(
                                ruleId, EventStatus.PLANNED, today)
                        .isPresent());
        if (active) schedule(credit);
    }

    /**
     * С какого дня ставить остаток графика: не раньше сегодня (I3) и после последнего внесённого
     * платежа прежнего графика — внесённый заранее после снятия остаётся, и новый график поверх него
     * задвоил бы месяц (ANO-225).
     */
    private LocalDate resumeFrom(UUID fundId) {
        LocalDate from = LocalDate.now(clock);
        for (UUID ruleId : eventRepository.findCreditPaymentRuleIds(fundId)) {
            LocalDate paid = eventRepository.findMaxExecutedDateByRule(ruleId).orElse(null);
            if (paid != null && !paid.isBefore(from)) from = paid.plusDays(1);
        }
        return from;
    }

    /** Первый платёж не раньше {@code from}: через k ≥ 1 месяцев после покупки, тем же днём. */
    static LocalDate firstPaymentFrom(LocalDate purchase, LocalDate from) {
        int months = 1;
        while (purchase.plusMonths(months).isBefore(from)) months++;
        return purchase.plusMonths(months);
    }

    /** Ставка, срок и дата покупки — 400 до любой записи, как в диалоге «Что с капиталом». */
    static void requireCreditParams(TargetFund credit) {
        if (credit.getCreditRate() == null || credit.getCreditTermMonths() == null
                || credit.getCreditTermMonths() <= 0 || credit.getTargetDate() == null
                || credit.getTargetAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "credit rate, positive term, amount and purchase date are required for credit payments");
        }
    }

    /**
     * Системная категория «Кредит» (EXPENSE): {@code RecurringRule#getCategory()} обязателен, правилу
     * платежей нужна настоящая категория. Через неё же идёт план из копилки в конверсии. Удалённую
     * возвращает (ANO-224).
     */
    Category creditCategory() {
        return categoryService.systemCategory(SystemCategory.CREDIT_NAME);
    }
}

package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.model.enums.ScopeEnum;
import ru.selfin.backend.repository.CategoryRepository;
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

    private static final String CREDIT_CATEGORY_NAME = "Кредит";

    private final FinancialEventRepository eventRepository;
    private final RecurringRuleService recurringRuleService;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    /**
     * Ставит график бронью на эту же копилку: MONTHLY-правило, первый платёж — через месяц после
     * покупки тем же днём, последний — через срок кредита. Стоявший график сначала снимается: двух
     * у копилки не бывает.
     *
     * <p>Платёж — аннуитет {@link SandboxLayout#monthlyPmt}, единственная формула PMT в проекте. Раньше
     * он брался из {@code computeCreditDelta} с горизонтом «срок + 1 месяц» — и у покупки за этим
     * горизонтом или в прошлом выходил нулём.
     *
     * @return id правила
     * @throws ResponseStatusException 400 без ставки, срока или даты покупки
     */
    @Transactional
    public UUID schedule(TargetFund credit) {
        requireCreditParams(credit);
        unschedule(credit.getId());
        int term = credit.getCreditTermMonths();
        LocalDate purchase = credit.getTargetDate();
        BigDecimal pmt = SandboxLayout.monthlyPmt(credit.getTargetAmount(), credit.getCreditRate(), term);
        var cfg = new RecurringConfigDto(RecurringFrequency.MONTHLY, purchase.getDayOfMonth(), null,
                purchase.plusMonths(1), purchase.plusMonths(term));
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
     * платежей нужна настоящая категория.
     */
    Category creditCategory() {
        return categoryRepository.findByNameAndDeletedFalse(CREDIT_CATEGORY_NAME)
                .orElseGet(() -> categoryRepository.save(Category.builder()
                        .name(CREDIT_CATEGORY_NAME).type(CategoryType.EXPENSE).system(true).build()));
    }
}

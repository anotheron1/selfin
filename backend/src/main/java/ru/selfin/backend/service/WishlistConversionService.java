package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.RecurringConfigDto;
import ru.selfin.backend.dto.wishlist.ConvertWishlistRequestDto;
import ru.selfin.backend.dto.wishlist.ConvertWishlistResponseDto;
import ru.selfin.backend.dto.wishlist.WishlistItemDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.EventKind;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.model.enums.EventType;
import ru.selfin.backend.model.enums.FundPurchaseType;
import ru.selfin.backend.model.enums.FundStatus;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.model.enums.RecurringFrequency;
import ru.selfin.backend.model.enums.WishlistStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Конвертирует wishlist-item (OPEN-хотелку / копилку / кредит) в реальный артефакт:
 * PLAN-событие, копилку (SAVINGS-фонд) или кредит (CREDIT-фонд) с опциональным
 * recurring-правилом ежемесячных платежей (PMT).
 *
 * <p>Источник переводится в статус {@link WishlistStatus#FIXED} и получает ссылку
 * {@code convertedToEventId}/{@code convertedToFundId} на созданный артефакт. Повторная
 * конверсия уже сконвертированного item'а → 409. Не найден → 404.
 *
 * <p>Вся логика в одном {@link Transactional}-методе: при любой ошибке откатывается
 * целиком (all-or-nothing) — источник не остаётся помеченным FIXED без артефакта.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WishlistConversionService {

    private final FinancialEventRepository eventRepository;
    private final TargetFundRepository fundRepository;
    private final RecurringRuleService recurringRuleService;
    private final CategoryRepository categoryRepository;

    /** Имя системной категории для платежей по кредиту (recurring PMT). */
    private static final String CREDIT_CATEGORY_NAME = "Кредит";

    /**
     * Конвертирует item в артефакт. Источник определяется по {@code sourceKind}:
     * WISHLIST → событие; SAVINGS/CREDIT → фонд.
     *
     * @param itemId id исходного item'а
     * @param req    параметры конверсии
     * @return ссылка на созданный артефакт + новый статус
     * @throws ResourceNotFoundException 404, если источник не найден
     * @throws ResponseStatusException   409, если источник уже сконвертирован
     */
    @Transactional
    public ConvertWishlistResponseDto convertItem(UUID itemId, ConvertWishlistRequestDto req) {
        boolean fromEvent = "WISHLIST".equals(req.sourceKind());
        return fromEvent
                ? convertFromEvent(itemId, req)
                : convertFromFund(itemId, req);
    }

    // ====== WISHLIST event source ======

    private ConvertWishlistResponseDto convertFromEvent(UUID itemId, ConvertWishlistRequestDto req) {
        FinancialEvent src = eventRepository.findById(itemId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", itemId));
        ensureNotConverted(src.getConvertedToEventId(), src.getConvertedToFundId());

        WishlistItemDto.ConvertedToDto convertedTo;
        String artifactKind;
        UUID recurringRuleId = null;

        switch (req.target()) {
            case "PLAN_EVENT" -> {
                FinancialEvent created = buildPlanEvent(
                        src.getCategory(), src.getPlannedAmount(), src.getDate(), src.getDescription());
                FinancialEvent saved = eventRepository.save(created);
                src.setConvertedToEventId(saved.getId());
                convertedTo = new WishlistItemDto.ConvertedToDto("EVENT", saved.getId());
                artifactKind = "PLAN_EVENT";
            }
            case "FUND" -> {
                TargetFund saved = fundRepository.save(buildSavingsFund(
                        src.getDescription(), src.getPlannedAmount(),
                        req.fundTargetDate() != null ? req.fundTargetDate() : src.getDate()));
                src.setConvertedToFundId(saved.getId());
                convertedTo = new WishlistItemDto.ConvertedToDto("FUND", saved.getId());
                artifactKind = "FUND";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported target for WISHLIST source: " + req.target());
        }

        src.setWishlistStatus(WishlistStatus.FIXED);
        eventRepository.save(src);
        return new ConvertWishlistResponseDto(itemId, "FIXED", convertedTo, artifactKind, recurringRuleId);
    }

    // ====== SAVINGS / CREDIT fund source ======

    private ConvertWishlistResponseDto convertFromFund(UUID itemId, ConvertWishlistRequestDto req) {
        TargetFund src = fundRepository.findById(itemId)
                .filter(f -> !f.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TargetFund", itemId));
        ensureNotConverted(src.getConvertedToEventId(), src.getConvertedToFundId());

        WishlistItemDto.ConvertedToDto convertedTo;
        String artifactKind;
        UUID recurringRuleId = null;

        switch (req.target()) {
            case "PLAN_EVENT" -> {
                FinancialEvent created = buildPlanEvent(
                        creditCategory(), src.getTargetAmount(), src.getTargetDate(), src.getName());
                FinancialEvent saved = eventRepository.save(created);
                src.setConvertedToEventId(saved.getId());
                convertedTo = new WishlistItemDto.ConvertedToDto("EVENT", saved.getId());
                artifactKind = "PLAN_EVENT";
            }
            case "FUND" -> {
                TargetFund saved = fundRepository.save(buildSavingsFund(
                        src.getName(), src.getTargetAmount(),
                        req.fundTargetDate() != null ? req.fundTargetDate() : src.getTargetDate()));
                src.setConvertedToFundId(saved.getId());
                convertedTo = new WishlistItemDto.ConvertedToDto("FUND", saved.getId());
                artifactKind = "FUND";
            }
            case "FUND_WITH_CREDIT" -> {
                if (src.getCreditRate() == null
                        || src.getCreditTermMonths() == null
                        || src.getCreditTermMonths() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "credit rate and positive term are required for FUND_WITH_CREDIT conversion");
                }
                TargetFund saved = fundRepository.save(buildCreditFund(src));
                src.setConvertedToFundId(saved.getId());
                convertedTo = new WishlistItemDto.ConvertedToDto("FUND", saved.getId());
                artifactKind = "FUND_WITH_CREDIT";

                if (Boolean.TRUE.equals(req.createRecurringPayments())) {
                    recurringRuleId = createCreditPmtRule(src, saved);
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported target for fund source: " + req.target());
        }

        src.setWishlistStatus(WishlistStatus.FIXED);
        fundRepository.save(src);
        return new ConvertWishlistResponseDto(itemId, "FIXED", convertedTo, artifactKind, recurringRuleId);
    }

    // ====== Builders ======

    private FinancialEvent buildPlanEvent(Category category, BigDecimal amount,
                                          java.time.LocalDate date, String description) {
        return FinancialEvent.builder()
                .eventKind(EventKind.PLAN)
                .status(EventStatus.PLANNED)
                .type(EventType.EXPENSE)
                .priority(Priority.LOW)
                .wishlistStatus(null)
                .category(category)
                .plannedAmount(amount)
                .date(date)
                .description(description)
                .build();
    }

    private TargetFund buildSavingsFund(String name, BigDecimal amount, java.time.LocalDate targetDate) {
        return TargetFund.builder()
                .name(name)
                .status(FundStatus.FUNDING)
                .purchaseType(FundPurchaseType.SAVINGS)
                .wishlistStatus(WishlistStatus.FIXED)
                .targetAmount(amount)
                .targetDate(targetDate)
                .build();
    }

    private TargetFund buildCreditFund(TargetFund src) {
        return TargetFund.builder()
                .name(src.getName())
                .status(FundStatus.FUNDING)
                .purchaseType(FundPurchaseType.CREDIT)
                .wishlistStatus(WishlistStatus.FIXED)
                .targetAmount(src.getTargetAmount())
                .targetDate(src.getTargetDate())
                .creditRate(src.getCreditRate())
                .creditTermMonths(src.getCreditTermMonths())
                .build();
    }

    /**
     * Создаёт MONTHLY recurring-правило ежемесячного платежа по кредиту.
     * Сумма платежа (PMT) выводится из {@link WishlistSimulationService#computeCreditDelta}.
     * Первый платёж — в месяц после покупки; последний — через {@code termMonths}.
     */
    private UUID createCreditPmtRule(TargetFund src, TargetFund savedFund) {
        int termMonths = src.getCreditTermMonths() != null ? src.getCreditTermMonths() : 0;
        BigDecimal monthlyPMT = WishlistSimulationService.computeCreditDelta(
                src.getTargetAmount(),
                src.getTargetDate(),
                YearMonth.now(),
                Math.max(termMonths + 1, 1),
                src.getCreditRate(),
                termMonths).monthlyPMT();

        var cfg = new RecurringConfigDto(
                RecurringFrequency.MONTHLY,
                src.getTargetDate().getDayOfMonth(),       // dayOfMonth
                null,                                        // monthOfYear (MONTHLY → null)
                src.getTargetDate().plusMonths(1),           // startDate: first payment month after purchase
                src.getTargetDate().plusMonths(termMonths)); // endDate
        var ruleResult = recurringRuleService.createFromDto(
                creditCategory(),                            // PMT category (system "Кредит")
                EventType.EXPENSE,
                monthlyPMT,
                Priority.MEDIUM,
                src.getName() + " — платёж по кредиту",
                savedFund.getId(),
                null,
                cfg);
        return ruleResult.rule().getId();
    }

    // ====== Helpers ======

    private void ensureNotConverted(UUID convertedToEventId, UUID convertedToFundId) {
        if (convertedToEventId != null || convertedToFundId != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already converted");
        }
    }

    /**
     * Возвращает или создаёт системную категорию «Кредит» (EXPENSE).
     * {@link RecurringRule#getCategory()} объявлен {@code nullable = false}, поэтому
     * правилу платежей по кредиту нужна реальная категория, а не null.
     */
    private Category creditCategory() {
        return categoryRepository.findByNameAndDeletedFalse(CREDIT_CATEGORY_NAME)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name(CREDIT_CATEGORY_NAME)
                                .type(CategoryType.EXPENSE)
                                .system(true)
                                .build()));
    }
}

package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Тонкая обвязка кармашка: parse скоупа (→ 400) + сборка входа {@link PocketInputAssembler}
 * + чистый {@link PocketEngine}. Вся выборка и резолюция горизонта — в ассемблере
 * (общий код с POST /pocket/sandbox, спека sandbox §3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PocketService {

    private final PocketInputAssembler assembler;
    /** ANO-119: только чтобы подставить имена категорий в список «осталось потратить». */
    private final FinancialEventRepository eventRepository;

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return withCategoryNames(
                PocketEngine.calculate(assembler.build(scope, asOfDate).input()));
    }

    /**
     * Подставляет имена категорий в список «осталось потратить» (ANO-119).
     *
     * <p>Движок работает на плоских снимках без JPA и имён категорий не видит. Тянуть их
     * в снимок значило бы трогать лениво загруженную связь на всей траектории — это до
     * тысячи строк. Здесь запрос идёт ровно по тем идентификаторам, которые попали
     * в список: их единицы, блок читается человеком.
     *
     * <p>Имя ставится СВОЕЙ строке по идентификатору, а не по порядку выдачи из базы:
     * порядок списка задаёт движок (просрочка, затем по датам), и база его не повторяет.
     * У синтетики (взносы копилок) идентификатора нет — она остаётся со своим описанием.
     */
    private PocketResultDto withCategoryNames(PocketResultDto result) {
        List<UUID> ids = result.upcoming().stream()
                .map(PocketResultDto.UpcomingItem::id)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return result;

        Map<UUID, String> names = new HashMap<>();
        for (FinancialEvent e : eventRepository.findAllById(ids)) {
            if (e.getCategory() != null) names.put(e.getId(), e.getCategory().getName());
        }
        return result.withUpcoming(result.upcoming().stream()
                .map(i -> new PocketResultDto.UpcomingItem(i.id(), i.date(), names.get(i.id()),
                        i.amount(), i.description(), i.overdue(), i.wishlist()))
                .toList());
    }
}

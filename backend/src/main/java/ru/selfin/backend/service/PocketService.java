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
    /** ANO-119, ANO-95: только чтобы подставить имена категорий — в «осталось потратить» и виновнику минимума. */
    private final FinancialEventRepository eventRepository;

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return withCulpritCategories(withCategoryNames(
                PocketEngine.calculate(assembler.build(scope, asOfDate).input())));
    }

    /**
     * Называет виновника дня минимума категорией, если у него нет описания (ANO-95).
     *
     * <p>Движок берёт виновником описание крупнейшего расхода дня минимума, а без описания
     * отдавал {@code null} — и плашка разрыва на 122 212 ₽ молчала о причине, хотя расход
     * в этот день был. Порядок — описание, затем категория того же расхода (решение владельца
     * в задаче). Приём тот же, что у {@link #withCategoryNames}: запрос по одному-двум id.
     * У синтетики id нет — она остаётся со своим описанием.
     */
    PocketResultDto withCulpritCategories(PocketResultDto result) {
        List<UUID> ids = java.util.stream.Stream.of(result.minPoint(), result.minPointWithForecast())
                .filter(PocketService::needsName)
                .map(PocketResultDto.MinPoint::drivenByEventId)
                .toList();
        if (ids.isEmpty()) return result;

        Map<UUID, String> names = new HashMap<>();
        for (FinancialEvent e : eventRepository.findAllById(ids)) {
            if (e.getCategory() != null) names.put(e.getId(), e.getCategory().getName());
        }
        return result.withMinPoints(named(result.minPoint(), names), named(result.minPointWithForecast(), names));
    }

    private static boolean needsName(PocketResultDto.MinPoint p) {
        return p != null && p.drivenByEventId() != null && (p.drivenBy() == null || p.drivenBy().isBlank());
    }

    private static PocketResultDto.MinPoint named(PocketResultDto.MinPoint p, Map<UUID, String> names) {
        return needsName(p) ? p.withDrivenBy(names.get(p.drivenByEventId())) : p;
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
                .map(i -> i.withCategoryName(names.get(i.id())))
                .toList());
    }
}

package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.TargetFund;
import ru.selfin.backend.repository.FinancialEventRepository;
import ru.selfin.backend.repository.TargetFundRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Единственное место, где живёт правило «можно ли удалить артефакт, созданный конверсией
 * хотелки» (ANO-103, спека {@code 2026-05-29-wishlist-planning-design.md:66}).
 *
 * <p>Живёт отдельным сервисом, а не методом в одном из двух вызывающих, по той же причине,
 * по которой в этом проекте уже есть {@code AccountBalanceService}: хотелкой может быть и
 * событие, и копилка, вызывающих ровно двое, и разъехавшись, они дали бы два разных ответа
 * на один вопрос. Этим кодовая база уже болеет — правило «остаток счёта на дату» записано
 * трижды (ANO-23), и каждая правка обязана трогать все копии синхронно.
 *
 * <p><b>Отказ, а не молчаливое удаление.</b> За артефактом могли появиться деньги: у плана —
 * привязанные факты, у копилки — переводы. Удалить такой артефакт значит уничтожить реальную
 * историю трат или повторить ANO-86, где удаление копилки оставляет движения живыми, а деньги
 * для человека пропадают. Поэтому здесь отказ 409, а вызывающий откатывается целиком.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistArtifactService {

    private final FinancialEventRepository eventRepository;
    private final TargetFundRepository fundRepository;

    /**
     * Мягко удаляет артефакт, на который ссылается хотелка. Ссылку очищает вызывающий.
     *
     * <p>Обе ссылки пустые — делать нечего, это не ошибка: «удалить по явному выбору» при
     * отсутствии артефакта просто не имеет объекта. Идемпотентно.
     *
     * @param convertedToEventId ссылка на созданный план либо {@code null}
     * @param convertedToFundId  ссылка на созданную копилку либо {@code null}
     * @throws ResponseStatusException 409, если за артефактом стоят деньги
     */
    @Transactional
    public void deleteArtifact(UUID convertedToEventId, UUID convertedToFundId) {
        if (convertedToEventId != null) deletePlan(convertedToEventId);
        if (convertedToFundId != null) deleteFund(convertedToFundId);
    }

    private void deletePlan(UUID planId) {
        FinancialEvent plan = eventRepository.findById(planId)
                .filter(e -> !e.isDeleted())
                .orElse(null);
        if (plan == null) return;   // уже удалён — считаем, что выбор исполнен

        // Факты живут отдельными строками с parentEventId. Удалив план, мы осиротили бы их:
        // траты были настоящими, а их родитель исчез бы из журнала.
        boolean hasFacts = !eventRepository.findFactAggregatesByPlanIds(List.of(planId)).isEmpty();
        if (hasFacts) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan already has recorded facts and cannot be deleted");
        }
        plan.setDeleted(true);
        eventRepository.save(plan);
        log.info("wishlist_artifact_deleted kind=PLAN id={}", planId);
    }

    private void deleteFund(UUID fundId) {
        TargetFund fund = fundRepository.findById(fundId)
                .filter(f -> !f.isDeleted())
                .orElse(null);
        if (fund == null) return;

        // Сознательно НЕ зовём TargetFundService.delete, и после починки ANO-86 причина
        // изменилась. Раньше тот метод сам был дефектом: помечал копилку удалённой, оставляя
        // движения и событие перевода живыми. Теперь он исправен, но требует явного ответа,
        // что сделать с деньгами — вернуть или признать потраченными на цель. Через возврат
        // хотелки в обсуждение этот вопрос задать негде, а решать за человека продукт не
        // вправе. Поэтому здесь по-прежнему честный отказ: копилку с деньгами удаляют на
        // своём экране, где выбор есть.
        BigDecimal balance = fund.getCurrentBalance();
        if (balance != null && balance.signum() != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fund holds money and cannot be deleted");
        }
        fund.setDeleted(true);
        fundRepository.save(fund);
        log.info("wishlist_artifact_deleted kind=FUND id={}", fundId);
    }
}

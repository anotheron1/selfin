package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.selfin.backend.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByDeletedFalseOrderBySortOrderAscNameAsc();

    Optional<Account> findByDefaultAccountTrueAndDeletedFalse();

    /**
     * То же, что {@link #findAllByDeletedFalseOrderBySortOrderAscNameAsc()}, но с подтянутой
     * зоной ответственности. Экран счетов показывает её имя у каждой карточки, а ассоциация
     * ленивая — без {@code JOIN FETCH} это N+1 запрос на список (план, «Поправки после ревью
     * чанка 1», п.1: обход ленивой ассоциации в цикле под {@code open-in-view} тихо работает
     * в проде и падает в тестах).
     */
    @Query("""
        SELECT a FROM Account a LEFT JOIN FETCH a.purposeCategory
        WHERE a.deleted = false ORDER BY a.sortOrder ASC, a.name ASC
        """)
    List<Account> findAllActiveWithPurpose();

    /**
     * Счета, чья зона ответственности указывает на удаляемую категорию. Категории удаляются
     * мягко, но ссылка на удалённую зону счёту не нужна: §8 спеки требует обнулить её и
     * оставить счёт жить.
     */
    List<Account> findAllByPurposeCategoryIdAndDeletedFalse(UUID categoryId);
}

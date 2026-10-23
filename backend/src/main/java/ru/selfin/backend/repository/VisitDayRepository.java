package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.VisitDay;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Дни визита (ANO-102). Вставка нативная — ради {@code ON CONFLICT}, владелец в ней — {@code current_owner()}.
 *
 * <p>Пишется только владельцу ({@code owner}) и человеку, принявшему согласие редакции {@code since} или новее:
 * дни визита — цель, которую добавила эта редакция (ревью Codex на #187). Версии — даты редакций, строки
 * сравниваются в календарном порядке.
 */
public interface VisitDayRepository extends JpaRepository<VisitDay, UUID> {

    /** «Открывал»: строка за день уже есть — ничего не пишется. */
    @Modifying
    @Query(value = "INSERT INTO visit_days (user_id, day) SELECT u.id, :day FROM users u "
            + "WHERE u.id = current_owner() AND (u.id = :owner OR u.consent_version >= :since) "
            + "ON CONFLICT (user_id, day) DO NOTHING", nativeQuery = true)
    void markOpened(@Param("day") LocalDate day, @Param("owner") UUID owner, @Param("since") String since);

    /** «Записывал» — вместе с «открывал»; уже отмечен — ничего не пишется. */
    @Modifying
    @Query(value = "INSERT INTO visit_days (user_id, day, wrote) SELECT u.id, :day, TRUE FROM users u "
            + "WHERE u.id = current_owner() AND (u.id = :owner OR u.consent_version >= :since) "
            + "ON CONFLICT (user_id, day) DO UPDATE SET wrote = TRUE WHERE visit_days.wrote = FALSE", nativeQuery = true)
    void markWrote(@Param("day") LocalDate day, @Param("owner") UUID owner, @Param("since") String since);

    @Query("SELECT MAX(v.day) FROM VisitDay v WHERE v.day < :day")
    Optional<LocalDate> findLastBefore(@Param("day") LocalDate day);
}

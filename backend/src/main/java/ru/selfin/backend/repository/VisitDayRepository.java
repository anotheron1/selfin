package ru.selfin.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.selfin.backend.model.VisitDay;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Дни визита (ANO-102). Вставка нативная — ради {@code ON CONFLICT}, владелец в ней — {@code current_owner()}. */
public interface VisitDayRepository extends JpaRepository<VisitDay, UUID> {

    /** «Открывал»: строка за день уже есть — ничего не пишется. */
    @Modifying
    @Query(value = "INSERT INTO visit_days (user_id, day) VALUES (current_owner(), :day) "
            + "ON CONFLICT (user_id, day) DO NOTHING", nativeQuery = true)
    void markOpened(@Param("day") LocalDate day);

    /** «Записывал» — вместе с «открывал»; уже отмечен — ничего не пишется. */
    @Modifying
    @Query(value = "INSERT INTO visit_days (user_id, day, wrote) VALUES (current_owner(), :day, TRUE) "
            + "ON CONFLICT (user_id, day) DO UPDATE SET wrote = TRUE WHERE visit_days.wrote = FALSE", nativeQuery = true)
    void markWrote(@Param("day") LocalDate day);

    @Query("SELECT MAX(v.day) FROM VisitDay v WHERE v.day < :day")
    Optional<LocalDate> findLastBefore(@Param("day") LocalDate day);
}

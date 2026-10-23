package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.repository.VisitDayRepository;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Дни визита (ANO-102, шаг 13 плана спринта 6): отметки «открывал» и «записывал» и день прошлого визита — для
 * строки о перерыве под «Свободно» и для частот открытия и ввода (ANO-78). День — по часам приложения, как
 * «сегодня» везде на сервере.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
 */
@Service
@RequiredArgsConstructor
public class VisitService {

    /**
     * Редакция согласия, которая добавила дни визита. Человек с прежней их не принимал — ему дни не пишутся
     * (ревью Codex на #187); владельцу, оператору данных, согласие не нужно. Повторного согласия у знакомого
     * человека пока нет — ANO-238.
     */
    static final String CONSENT_SINCE = "2026-10-19";

    private final VisitDayRepository visits;
    private final Clock clock;

    /**
     * Отметка за сегодня от имени владельца запроса: «открывал» — всегда, «записывал» — если {@code wrote}.
     *
     * <p>ponytail: запрос в базу на каждый запрос человека, хотя пишет он раз в день — строка уже есть, и вставка
     * ничего не меняет. Станет заметно — помнить в процессе, кого сегодня уже отметили.
     */
    @Transactional
    public void mark(boolean wrote) {
        LocalDate today = LocalDate.now(clock);
        if (wrote) visits.markWrote(today, OwnerService.SYNTHETIC_OWNER, CONSENT_SINCE);
        else visits.markOpened(today, OwnerService.SYNTHETIC_OWNER, CONSENT_SINCE);
    }

    /** Последний день визита до сегодняшнего; первый визит — {@code null}. */
    @Transactional(readOnly = true)
    public LocalDate lastVisit() {
        return visits.findLastBefore(LocalDate.now(clock)).orElse(null);
    }
}

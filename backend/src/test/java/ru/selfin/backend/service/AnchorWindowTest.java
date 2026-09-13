package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-82/ANO-125. Правило «входит ли факт в остаток» — единственное место (спека §4).
 *
 * <p>До этой правки правило было записано ТРИЖДЫ и опиралось на голую дату, отчего факт,
 * записанный после сверки остатка, пропадал навсегда.
 */
class AnchorWindowTest {

    private static final LocalDate ANCHOR_DAY = LocalDate.of(2026, 9, 5);
    private static final LocalDateTime ANCHOR_ENTERED = LocalDateTime.of(2026, 9, 5, 10, 0);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    @Test
    @DisplayName("факт ПОСЛЕ даты якоря считается всегда")
    void factAfterAnchorDate_counts() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 6), LocalDateTime.of(2026, 9, 6, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, LocalDate.of(2026, 9, 7)))
                .isTrue();
    }

    @Test
    @DisplayName("ANO-82: факт дня якоря, записанный ПОСЛЕ сверки, считается")
    void factOnAnchorDay_recordedAfter_counts() {
        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, LocalDateTime.of(2026, 9, 5, 18, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("человек сверился утром и записал траты вечером — правило 6")
                .isTrue();
    }

    @Test
    @DisplayName("ANO-28: факт дня якоря, записанный ДО сверки, НЕ считается")
    void factOnAnchorDay_recordedBefore_doesNotCount() {
        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, LocalDateTime.of(2026, 9, 5, 9, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("трата была на экране, когда человек вводил число из банка — оно её содержит")
                .isFalse();
    }

    @Test
    @DisplayName("факт РАНЬШЕ даты якоря не считается, когда бы ни был записан")
    void factBeforeAnchorDate_neverCounts() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 1), LocalDateTime.of(2026, 9, 9, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("до дня якоря правило прежнее: банк уже всё учёл")
                .isFalse();
    }

    @Test
    @DisplayName("якорь задним числом: граница по времени ввода, а не по дате")
    void backdatedAnchor_usesEntryTime() {
        LocalDate anchorDay = LocalDate.of(2026, 8, 29);
        LocalDateTime enteredLater = LocalDateTime.of(2026, 9, 5, 15, 12);

        assertThat(AnchorWindow.countsTowardBalance(
                anchorDay, LocalDateTime.of(2026, 8, 29, 20, 0),
                anchorDay, enteredLater, TODAY))
                .as("записано до ввода якоря — человек видел это в банке 05.09")
                .isFalse();

        assertThat(AnchorWindow.countsTowardBalance(
                anchorDay, LocalDateTime.of(2026, 9, 6, 11, 0),
                anchorDay, enteredLater, TODAY))
                .as("вспомнили и записали позже ввода — в число попасть не могло")
                .isTrue();
    }

    @Test
    @DisplayName("факт позже верхней границы не считается")
    void factAfterUpperBound_doesNotCount() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2026, 9, 10), LocalDateTime.of(2026, 9, 10, 12, 0),
                ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .isFalse();
    }

    @Test
    @DisplayName("якоря нет: верхняя граница работает, нижней нет")
    void noAnchor_onlyUpperBoundApplies() {
        assertThat(AnchorWindow.countsTowardBalance(
                LocalDate.of(2020, 1, 1), LocalDateTime.of(2020, 1, 1, 12, 0),
                null, null, TODAY))
                .as("без якоря суммируются все факты до верхней границы (ANO-28 фолбэк)")
                .isTrue();
    }

    @Test
    @DisplayName("окно между якорями: обе границы по времени записи, не по дате")
    void betweenAnchors_bothBoundsUseEntryTime() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDateTime fromEntered = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDate to = LocalDate.of(2026, 9, 5);
        LocalDateTime toEntered = LocalDateTime.of(2026, 9, 5, 12, 0);

        assertThat(AnchorWindow.fallsBetweenAnchors(
                from, LocalDateTime.of(2026, 9, 1, 18, 0), from, fromEntered, to, toEntered))
                .as("день первого якоря, записано ПОСЛЕ него — уже не внутри его числа")
                .isTrue();

        assertThat(AnchorWindow.fallsBetweenAnchors(
                from, LocalDateTime.of(2026, 9, 1, 9, 0), from, fromEntered, to, toEntered))
                .as("день первого якоря, записано ДО него — его число это уже содержит")
                .isFalse();

        assertThat(AnchorWindow.fallsBetweenAnchors(
                to, LocalDateTime.of(2026, 9, 5, 9, 0), from, fromEntered, to, toEntered))
                .as("день второго якоря, записано ДО сверки — попало в его число, интервал его содержит")
                .isTrue();

        assertThat(AnchorWindow.fallsBetweenAnchors(
                to, LocalDateTime.of(2026, 9, 5, 18, 0), from, fromEntered, to, toEntered))
                .as("день второго якоря, записано ПОСЛЕ сверки — в его число не попало, "
                        + "и посчитанный остаток не имеет права его содержать")
                .isFalse();

        assertThat(AnchorWindow.fallsBetweenAnchors(
                LocalDate.of(2026, 9, 3), LocalDateTime.of(2026, 9, 3, 12, 0),
                from, fromEntered, to, toEntered))
                .as("между якорями по дате — считается без оглядки на время")
                .isTrue();
    }

    @Test
    @DisplayName("время записи неизвестно (синтетика, старые тесты) — день якоря решается как было")
    void unknownEntryTime_fallsBackToOldRule() {
        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, null, ANCHOR_DAY, ANCHOR_ENTERED, TODAY))
                .as("нечем решить — остаёмся на консервативном исключении, а не задваиваем")
                .isFalse();

        assertThat(AnchorWindow.countsTowardBalance(
                ANCHOR_DAY, LocalDateTime.of(2026, 9, 5, 18, 0), ANCHOR_DAY, null, TODAY))
                .as("якорь без времени ввода — та же ветка")
                .isFalse();
    }
}

package ru.selfin.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-155: правило «непогашенный остаток плана» обязано существовать в одном экземпляре.
 *
 * <p>Его нужно двоим: {@code PocketEngine} удерживает остаток в траектории,
 * {@code PredictionService} вычитает ровно его же из нормы. Раньше эти два места держали
 * предикат «слово в слово» — и однажды разъехались: замер по категории «Авто» дал 16 000
 * вместо 12 000. Дисциплина «копируем дословно» держится на внимательности, а сторож — нет.
 *
 * <p>Разъехавшись, копии дадут экран, где кармашек держит остаток, а прогноз вычитает полную
 * сумму, — и никакой тест поведения этого не поймает, потому что каждая половина по-своему
 * права. Ровно та форма дефекта, из-за которой ANO-82 чинилась в трёх местах подряд.
 *
 * <p>Сторож читает исходники, а не поведение: правилу запрещена вторая реализация, а не
 * предписано равенство двух.
 */
class PlanRemainderSingleSourceTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String RULE_HOLDER = "PlanRemainder.java";

    @Test
    @DisplayName("ANO-155: из плановой суммы не вычитают нигде, кроме PlanRemainder")
    void remainderArithmetic_livesInOnePlace() throws IOException {
        assertThat(filesSubtractingFromPlanned())
                .as("остаток обязан считаться в PlanRemainder — вторая копия однажды уже разъехалась")
                .isEmpty();
    }

    @Test
    @DisplayName("ANO-155: все потребители зовут общее правило")
    void everyConsumerCallsTheRule() throws IOException {
        assertThat(read("ru/selfin/backend/service/PocketEngine.java"))
                .as("движок обязан удерживать остаток, а не полную сумму")
                .contains("PlanRemainder.of(");
        assertThat(read("ru/selfin/backend/service/PredictionService.java"))
                .as("норма обязана вычитать ровно то, что удерживает движок")
                .contains("PlanRemainder.of(");
        assertThat(read("ru/selfin/backend/service/AnalyticsService.java"))
                .as("мостик стартового баланса обязан вычитать остаток, иначе план и его "
                        + "факт уходят из баланса дважды (ревью #45)")
                .contains("PlanRemainder.of(");
    }

    @Test
    @DisplayName("ревью #45: факты по родителю суммируют тоже в одном месте")
    void settlementGrouping_livesInOnePlace() throws IOException {
        // Третий потребитель появился уже на ревью — а три копии шестистрочного цикла
        // расходятся ровно так же, как разошлись две копии предиката.
        assertThat(filesMatching(Pattern.compile("merge\\([^;]*arentEventId\\(\\)")))
                .as("группировка фактов по плану обязана жить в PlanRemainder")
                .isEmpty();
    }

    /** Файлы, где из плановой суммы что-то вычитают в обход общего правила. */
    private List<String> filesSubtractingFromPlanned() throws IOException {
        return filesMatching(Pattern.compile("lannedAmount\\(\\)[^;]*\\.subtract\\("));
    }

    /** Файлы main вне держателя правила, где встречается запрещённый образец. */
    private List<String> filesMatching(Pattern forbidden) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(RULE_HOLDER))
                    .filter(p -> {
                        try {
                            return forbidden.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(p.toString(), e);
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
    }

    private String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}

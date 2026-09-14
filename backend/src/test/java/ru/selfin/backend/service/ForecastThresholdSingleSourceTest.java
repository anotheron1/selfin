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
 * ANO-80: порог «сколько месяцев наблюдения нужно, чтобы верить медиане» и окно истории
 * обязаны существовать в ОДНОМ экземпляре каждый.
 *
 * <p>До этой задачи копий было по две: {@code MIN_HISTORY_MONTHS} в сборке входа кармашка и
 * {@code MIN_HISTORY_FOR_FAN} в конусе fan chart; {@code HISTORY_WINDOW_MONTHS} и
 * {@code PREDICTION_WINDOW_MONTHS} — та же пара для окна. Прогноз текущего месяца стал бы
 * третьей копией каждой.
 *
 * <p>Разъехавшись, они дадут экран, где прогноз в кармашке есть, а конуса на том же экране
 * нет, — и никакой тест поведения этого не поймает, потому что каждая половина по-своему
 * права. Ровно та форма дефекта, из-за которой ANO-82 чинилась в трёх местах подряд.
 *
 * <p>Сторож читает исходники, а не поведение: величина, которой запрещено расходиться,
 * защищается запретом на вторую копию, а не проверкой равенства двух копий.
 */
class ForecastThresholdSingleSourceTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    @DisplayName("ANO-80: порог месяцев наблюдения объявлен ровно один раз")
    void minHistoryMonths_declaredOnce() throws IOException {
        assertThat(declarationsOf("MIN_HISTORY"))
                .as("порог обязан жить в PredictionService и больше нигде")
                .hasSize(1);
    }

    @Test
    @DisplayName("ANO-80: окно истории объявлено ровно один раз")
    void historyWindow_declaredOnce() throws IOException {
        assertThat(declarationsOf("HISTORY_WINDOW_MONTHS|PREDICTION_WINDOW_MONTHS"))
                .as("окно обязано жить в PredictionService и больше нигде")
                .hasSize(1);
    }

    private List<String> declarationsOf(String names) throws IOException {
        Pattern decl = Pattern.compile("static\\s+final\\s+int\\s+(" + names + ")\\w*\\s*=");
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        try {
                            return decl.matcher(Files.readString(p)).results()
                                    .map(r -> p.getFileName() + ": " + r.group());
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }
}

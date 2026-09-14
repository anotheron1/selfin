package ru.selfin.backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANO-39. «Сегодня» в слоях {@code service} и {@code controller} берётся ТОЛЬКО из
 * внедрённого {@code Clock}.
 *
 * <p>Счётчик прямых вызовов рос 31 → 36 → 38, и каждое новое место добавляла очередная
 * починка: запрета не было, {@code Clock} в большинстве сервисов не инжектился, и человек
 * писал то, что работает. Миграция без запрета через месяц дала бы 43.
 *
 * <p>Пока «сегодня» невозможно подменить, календарно-зависимую логику нельзя проверить
 * детерминированно — только «повезло с датой прогона». Задача заведена именно по такому
 * случаю: два теста {@code FundPlannerServiceTest} падали бы в последний день ЛЮБОГО месяца.
 *
 * <p><b>Список исключений отсутствует, и это условие смысла.</b> Сторож с исключениями
 * превращается в счётчик долга: каждый следующий вызов попадает в список «пока так», и
 * запрета не остаётся. Если сюда захотелось добавить исключение — значит место надо
 * мигрировать, а не разрешать.
 *
 * <p>Сущности ({@code model}) и DTO под запрет не подпадают: там штамп «когда запись
 * создана» — факт о записи, а не решение продукта, и в JPA-сущность {@code Clock} не
 * внедряется технически.
 *
 * <p><b>Комментарии не разбираются.</b> Поэтому в javadoc и комментариях пишите {@code now()}
 * без префикса класса — иначе пример в тексте уронит сборку. Плата невелика, а альтернатива
 * — либо парсер Java в тесте, либо дыра «в комментарии можно», через которую вызов однажды
 * и вернётся.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-13-clock-injection-design.md}.
 */
class ClockInjectionGuardTest {

    /**
     * Вызов без аргумента. С аргументом — {@code now(clock)} — не ловится.
     *
     * <p>Перечислены ВСЕ типы {@code java.time}, у которых есть {@code now()}, а не только
     * те, что встретились при миграции. Первая редакция сторожа знала лишь
     * {@code LocalDate} и {@code LocalDateTime} — и пропустила пять {@code YearMonth.now()}
     * в четырёх сервисах. Сторож, который ищет не всё, даёт ложное чувство законченности.
     */
    private static final Pattern DIRECT_NOW = Pattern.compile(
            "\\b(LocalDate|LocalDateTime|LocalTime|Instant|YearMonth|Year|MonthDay"
                    + "|ZonedDateTime|OffsetDateTime|OffsetTime)\\.now\\(\\s*\\)");

    private static final List<String> GUARDED_PACKAGES =
            List.of("src/main/java/ru/selfin/backend/service",
                    "src/main/java/ru/selfin/backend/controller");

    @Test
    @DisplayName("ANO-39: в service и controller нет прямых вызовов now()")
    void noDirectNowCalls() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String pkg : GUARDED_PACKAGES) {
            Path root = Path.of(pkg);
            assertThat(root)
                    .as("путь к исходникам съехал — сторож молчал бы, ничего не проверяя")
                    .exists();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (DIRECT_NOW.matcher(lines.get(i)).find()) {
                            offenders.add(file.getFileName() + ":" + (i + 1)
                                    + "  " + lines.get(i).strip());
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("«сегодня» берётся из внедрённого Clock — иначе логику нельзя проверить "
                        + "детерминированно. Не добавляйте сюда исключение: мигрируйте место.")
                .isEmpty();
    }
}

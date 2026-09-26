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
 * ANO-194, ворота 5. Каждый эталон — {@code *RegressionTest} — объясняет, почему его числа верны.
 *
 * <p>Эталон {@code PocketMigrationRegressionTest} записал сжатие прогноза (ANO-140) как замысел,
 * и правка дефекта сначала выглядела поломкой. Числа были посчитаны в столбик, но столбик
 * проверяет арифметику, а не то, что считать надо именно так. Поэтому в javadoc класса эталона
 * обязателен абзац с меткой {@value #LABEL} и ссылкой на решение, из которого числа следуют:
 * задача {@code ANO-…} или спека {@code docs/superpowers/specs/…}.
 *
 * <p>Сторож проверяет наличие метки и ссылки, а не качество объяснения — оно на авторе и ревью.
 *
 * <p>Спека: {@code docs/superpowers/specs/2026-09-26-regression-explains-design.md}.
 */
class RegressionTestExplainsNumbersGuardTest {

    static final String LABEL = "Почему числа верны";

    /** Ссылка на решение: номер задачи или путь к спеке. */
    private static final Pattern DECISION = Pattern.compile("ANO-\\d+|docs/superpowers/specs/");

    @Test
    @DisplayName("ANO-194: каждый *RegressionTest объясняет, почему его числа верны, со ссылкой на решение")
    void everyRegressionTestExplainsItsNumbers() throws IOException {
        Path root = Path.of("src/test/java");
        List<Path> etalons;
        try (Stream<Path> files = Files.walk(root)) {
            etalons = files.filter(p -> p.getFileName().toString().endsWith("RegressionTest.java")).toList();
        }
        assertThat(etalons)
                .as("эталонов меньше двух — путь к тестам съехал, и сторож молчал бы, ничего не проверяя")
                .hasSizeGreaterThanOrEqualTo(2);

        List<String> offenders = new ArrayList<>();
        for (Path file : etalons) {
            String name = file.getFileName().toString().replace(".java", "");
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String javadoc = classJavadoc(source, name);
            int label = javadoc.indexOf(LABEL);
            if (label < 0) {
                offenders.add(name + ": в javadoc класса нет абзаца «" + LABEL + "»");
                continue;
            }
            int next = javadoc.indexOf("<p>", label);
            String paragraph = javadoc.substring(label, next < 0 ? javadoc.length() : next);
            if (!DECISION.matcher(paragraph).find()) {
                offenders.add(name + ": в абзаце «" + LABEL + "» нет ссылки на решение — ANO-… или docs/superpowers/specs/…");
            }
        }

        assertThat(offenders)
                .as("эталон объясняет, из какого решения следуют его числа, а не «так было» (ANO-194)")
                .isEmpty();
    }

    /** Javadoc перед объявлением класса; нет его — пустая строка. */
    private static String classJavadoc(String source, String className) {
        int declaration = source.indexOf("class " + className);
        if (declaration < 0) {
            return "";
        }
        String head = source.substring(0, declaration);
        int start = head.lastIndexOf("/**");
        int end = start < 0 ? -1 : head.indexOf("*/", start);
        return start < 0 || end < 0 ? "" : head.substring(start, end);
    }
}

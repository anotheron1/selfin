package ru.selfin.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.repository.CategoryRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия ANO-80: прогноз незапланированных выключен по умолчанию.
 *
 * <p>V15 включала прогноз семи засеянным категориям, и новый пользователь получал его
 * активным, ничего не выбирая. Одна первая трата 5-го числа раздувалась в шесть раз
 * (сумма × дней в месяце / номер дня), и первое число продукта пугало.
 *
 * <p>V21 это снимает. Тест проверяет результат миграций, а не намерение: если V21 убрать
 * или отменить её действие, `forecastEnabled` вернётся у семи категорий и тест упадёт.
 */
@SpringBootTest
@Testcontainers
class ForecastDisabledByDefaultIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired CategoryRepository categoryRepository;

    @Test
    void afterMigrations_noCategoryHasForecastEnabled() {
        List<Category> enabled = categoryRepository.findAllByForecastEnabledTrueAndDeletedFalse();

        assertThat(enabled)
                .as("прогноз не включается сам; пользователь включает его осознанно")
                .isEmpty();
    }

    @Test
    void seededCategoriesExist_soTheEmptinessAboveIsNotVacuous() {
        // Без этой проверки предыдущий тест прошёл бы и на пустой таблице категорий,
        // то есть перестал бы что-либо доказывать.
        assertThat(categoryRepository.findAllByDeletedFalse())
                .as("V2 засевает категории — иначе проверка выше ничего не значит")
                .isNotEmpty();
    }
}

package ru.selfin.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-103 / ANO-85. Нарушение ограничения базы обязано доходить до клиента как 409,
 * а не как «Internal server error».
 *
 * <p>Проверяется сам обработчик, а не конкретная ручка: до этой правки в
 * {@link GlobalExceptionHandler} не было ветки под {@code DataIntegrityViolationException},
 * и ЛЮБОЕ из двух десятков ограничений схемы — включая частичный уникальный индекс
 * {@code uq_events_rule_date_active} из ANO-91 — превращалось в пятисотку. Привязывать
 * тест к одной ручке значило бы проверять ручку, а не правило.
 *
 * <p>Мутация: убрать {@code handleDataIntegrity} из обработчика — исключение уходит в
 * {@code handleGeneric(Exception)}, ответ становится 500, тест краснеет.
 */
class GlobalExceptionHandlerIntegrityTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            // Ровно то, что Spring поднимает над нарушением check-ограничения PostgreSQL.
            throw new DataIntegrityViolationException(
                    "could not execute statement; constraint [chk_event_converted_only_fixed]");
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("DataIntegrityViolationException → 409, а не 500")
    void integrityViolation_becomes409() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("наружу не уходят имена таблиц и ограничений")
    void integrityViolation_doesNotLeakSchemaDetails() throws Exception {
        // Сообщение PostgreSQL называет таблицу, колонку и имя ограничения. Пересказывать
        // его клиенту — рассказывать о внутреннем устройстве; подробности идут в лог.
        mvc.perform(get("/boom"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("chk_event_converted_only_fixed"))));
    }
}

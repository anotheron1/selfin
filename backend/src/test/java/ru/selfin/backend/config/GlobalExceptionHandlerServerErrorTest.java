package ru.selfin.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-85, ревью Codex на PR #76. Ошибка клиента — 4xx, но дефект сервера остаётся 500 и не
 * рассказывает о внутреннем устройстве.
 *
 * <p>Два случая, где правка ANO-85 это нарушала:
 * <ul>
 *   <li>нет конвертера для типа параметра — {@code MethodArgumentConversionNotSupportedException}
 *       наследует {@code TypeMismatchException}, и обработчик кривого значения отвечал 400,
 *       перекладывая на клиента поломку сборки;</li>
 *   <li>стандартная ошибка Spring со статусом 5xx — например, переменная пути, которой нет в
 *       шаблоне маршрута, — уходила клиенту со своим текстом, где названо имя переменной.</li>
 * </ul>
 */
class GlobalExceptionHandlerServerErrorTest {

    /** Конвертера из строки нет: ни конструктора от строки, ни {@code valueOf}. */
    record NoConverter(int a, int b) {}

    @RestController
    static class Controller {
        @GetMapping("/no-converter")
        String noConverter(@RequestParam NoConverter value) {
            return "ok";
        }

        /** Переменная объявлена в методе, но её нет в шаблоне маршрута — дефект маппинга. */
        @GetMapping("/missing-path-variable")
        String missingPathVariable(@PathVariable("secretVariableName") String id) {
            return id;
        }

        @GetMapping("/typed")
        String typed(@RequestParam int n) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new Controller())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("нет конвертера для типа параметра — 500: это поломка сервера, а не кривое значение клиента")
    void missingConverter_isServerError() throws Exception {
        mvc.perform(get("/no-converter").param("value", "1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    @DisplayName("стандартная ошибка Spring со статусом 5xx — статус её, а текст родовой")
    void knownServerError_keepsStatusButHidesDetail() throws Exception {
        mvc.perform(get("/missing-path-variable"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.message").value(not(containsString("secretVariableName"))));
    }

    @Test
    @DisplayName("кривое значение от клиента — по-прежнему 400 с именем параметра")
    void malformedClientValue_staysBadRequest() throws Exception {
        mvc.perform(get("/typed").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for n: abc"));
    }
}

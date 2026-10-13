package ru.selfin.backend.testsupport;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * IT — от имени вошедшего человека (ANO-195): {@code MockMvc} добавляет к каждому запросу вход через
 * Keycloak и CSRF-токен, как их несёт браузер после входа. Предмет этих тестов — деньги, а не вход;
 * вход проверяет {@code SignInIT} своим {@code MockMvc}, без этой подстановки.
 *
 * <p>Обычный {@code @Configuration}, а не {@code @TestConfiguration}: его подхватывает сканирование
 * {@code @SpringBootApplication}, и каждый IT получает вход без правки.
 */
@Configuration
public class SignedInMockMvc {

    @Bean
    MockMvcBuilderCustomizer signedIn() {
        return builder -> builder.defaultRequest(get("/").with(oidcLogin()).with(csrf()));
    }
}

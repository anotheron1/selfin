package ru.selfin.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Jackson 2 ({@code com.fasterxml.jackson}) ObjectMapper для сервисов, сериализующих JSON
     * вручную (например {@link ru.selfin.backend.service.UserSettingsService}).
     *
     * <p>Spring Boot 4 авто-конфигурирует ObjectMapper версии Jackson 3
     * ({@code tools.jackson.databind.ObjectMapper}) для HTTP message-конвертеров, поэтому
     * Jackson-2 ObjectMapper как бин в контексте отсутствует. Этот бин закрывает потребность
     * сервисов, которые уже завязаны на Jackson 2 API ({@code readValue}/{@code writeValueAsString}).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}

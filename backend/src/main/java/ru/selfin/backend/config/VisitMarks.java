package ru.selfin.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.selfin.backend.controller.WritesData;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.VisitService;

/**
 * Отметки дня визита (ANO-102): ответ на запрос вошедшего — «открывал», удачный (2xx) ответ ручки с
 * {@link WritesData} — ещё и «записывал». После ответа: статус уже окончательный, а владелец ещё стоит —
 * {@link OwnerFilter} обнимает весь запрос. Без владельца — согласие до входа, коммит сборки — отмечать некого.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-19-break-hint-design.md
 */
@Component
@RequiredArgsConstructor
class VisitMarks implements HandlerInterceptor, WebMvcConfigurer {

    private final VisitService visits;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (OwnerContext.get() == null) return;
        visits.mark(ex == null && response.getStatus() / 100 == 2
                && handler instanceof HandlerMethod method && writes(method));
    }

    private static boolean writes(HandlerMethod method) {
        WritesData mark = method.getMethodAnnotation(WritesData.class);
        return mark != null && mark.value();
    }
}

package ru.selfin.backend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import ru.selfin.backend.controller.WritesData;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.VisitService;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Отметки дня визита (ANO-102) — ветви перехватчика по одной: владелец, статус, исключение, разметка ручки.
 * Что перехватчик стоит в цепочке и пишет в базу — {@code VisitDaysIT}.
 */
class VisitMarksTest {

    private final VisitService visits = mock(VisitService.class);
    private final VisitMarks marks = new VisitMarks(visits);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @SuppressWarnings("unused")
    static class Handlers {
        @WritesData void writes() { }
        @WritesData(false) void sandbox() { }
        void read() { }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getDeclaredMethod(name));
    }

    private void after(int status, String handler, Exception ex) throws Exception {
        response.setStatus(status);
        marks.afterCompletion(request, response, handler(handler), ex);
    }

    @AfterEach
    void clearOwner() {
        OwnerContext.set(null);
    }

    @Test
    @DisplayName("без владельца — согласие до входа, коммит сборки — отмечать некого")
    void noOwner_noMark() throws Exception {
        after(201, "writes", null);

        verifyNoInteractions(visits);
    }

    @Test
    @DisplayName("удачный ответ пишущей ручки — «записывал»")
    void successfulWrite_marksWrote() throws Exception {
        OwnerContext.set(UUID.randomUUID());
        after(201, "writes", null);

        verify(visits).mark(true);
    }

    @Test
    @DisplayName("отказ пишущей ручки — только «открывал»: данных не записано")
    void failedWrite_marksOpened() throws Exception {
        OwnerContext.set(UUID.randomUUID());
        after(400, "writes", null);

        verify(visits).mark(false);
    }

    @Test
    @DisplayName("исключение мимо обработчика при статусе 200 — только «открывал»")
    void unresolvedException_marksOpened() throws Exception {
        OwnerContext.set(UUID.randomUUID());
        after(200, "writes", new IllegalStateException("мимо обработчика"));

        verify(visits).mark(false);
    }

    @Test
    @DisplayName("примерка — @WritesData(false) — только «открывал»")
    void sandbox_marksOpened() throws Exception {
        OwnerContext.set(UUID.randomUUID());
        after(200, "sandbox", null);

        verify(visits).mark(false);
    }

    @Test
    @DisplayName("чтение — только «открывал»")
    void read_marksOpened() throws Exception {
        OwnerContext.set(UUID.randomUUID());
        after(200, "read", null);

        verify(visits).mark(false);
    }
}

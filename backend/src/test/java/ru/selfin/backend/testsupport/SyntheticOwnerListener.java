package ru.selfin.backend.testsupport;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import ru.selfin.backend.service.OwnerContext;
import ru.selfin.backend.service.OwnerService;

/**
 * Поток теста — от имени синтетического владельца (ANO-144): IT ходят в репозитории и сервисы напрямую, без
 * запроса, а без владельца пул не пустил бы их ни к одной строке. Пользователь {@code MockMvc} по умолчанию
 * связан с тем же владельцем — {@code selfin.owner-sub=user} в {@code config/application.properties} тестов.
 *
 * <p>Подключён через {@code META-INF/spring.factories} — ко всем тестам контекста.
 */
public class SyntheticOwnerListener implements TestExecutionListener {

    @Override
    public void beforeTestMethod(TestContext testContext) {
        OwnerContext.set(OwnerService.SYNTHETIC_OWNER);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        OwnerContext.set(null);
    }
}

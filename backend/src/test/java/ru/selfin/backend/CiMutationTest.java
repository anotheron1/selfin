package ru.selfin.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** МУТАЦИЯ ДЛЯ ПРОВЕРКИ CI (ANO-26). Не вливать: черновой PR закрывается без слияния. */
class CiMutationTest {

    @Test
    void plantedBackendBreakage() {
        assertEquals(1, 2, "подсаженная поломка: проверка «Бэк» обязана покраснеть");
    }
}

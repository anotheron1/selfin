package ru.selfin.backend.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VersionController} — коммит, из которого собран бэк (ANO-194, ворота 4;
 * спека 2026-09-26-build-version-design.md). Что образ действительно получает коммит при сборке,
 * проверяет проба в CI на настоящем образе — tools/ci-migrations.sh.
 */
class VersionControllerTest {

    @Test
    @DisplayName("отдаёт коммит, с которым собран")
    void returnsBuildCommit() {
        assertThat(new VersionController("0c2b997").version()).containsEntry("commit", "0c2b997");
    }
}

package ru.selfin.backend.dto;

import java.util.List;

/**
 * Подсказки первого дня (ключ «hints» в user_settings, спека среза 2, п. 12): {@code off} — выключатель в «Настройках»
 * и «Скрыть все подсказки»; {@code closed} — имена подсказок, закрытых крестиком.
 */
public record HintsSettingsDto(boolean off, List<String> closed) {}

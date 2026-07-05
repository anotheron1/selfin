package ru.selfin.backend.dto.pocket;

import java.math.BigDecimal;

/** Настройки кармашка (ключ "pocket" в user_settings, спека §7). */
public record PocketSettingsDto(BigDecimal bufferAmount) {}

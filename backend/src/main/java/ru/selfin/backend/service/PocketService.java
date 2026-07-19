package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.selfin.backend.dto.pocket.PocketResultDto;
import ru.selfin.backend.dto.pocket.PocketScope;

import java.time.LocalDate;

/**
 * Тонкая обвязка кармашка: parse скоупа (→ 400) + сборка входа {@link PocketInputAssembler}
 * + чистый {@link PocketEngine}. Вся выборка и резолюция горизонта — в ассемблере
 * (общий код с POST /pocket/sandbox, спека sandbox §3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PocketService {

    private final PocketInputAssembler assembler;

    public PocketResultDto getPocket(String rawScope, LocalDate asOfDate) {
        PocketScope scope;
        try {
            scope = PocketScope.parse(rawScope);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return PocketEngine.calculate(assembler.build(scope, asOfDate).input());
    }
}

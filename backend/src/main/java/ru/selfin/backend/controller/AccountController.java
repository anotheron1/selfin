package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.account.AccountCreateDto;
import ru.selfin.backend.dto.account.AccountDto;
import ru.selfin.backend.service.AccountService;

import java.util.List;
import java.util.UUID;

/**
 * REST-интерфейс счетов (спека §5.3). Экран живёт в Настройках: счета — продвинутый слой
 * лестницы раскрытия, в ежедневном цикле их нет вовсе (§5.1).
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService service;

    @GetMapping
    public List<AccountDto> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public AccountDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountDto create(@Valid @RequestBody AccountCreateDto dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public AccountDto update(@PathVariable UUID id, @Valid @RequestBody AccountCreateDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /**
     * Отдельная ручка, а не поле в PUT: смена счёта-приёмника меняет ДВА счёта сразу —
     * старый обязан потерять флаг в той же транзакции, иначе частичный уникальный индекс
     * отвергнет промежуточное состояние.
     */
    @PatchMapping("/{id}/default")
    public AccountDto makeDefault(@PathVariable UUID id) {
        return service.makeDefault(id);
    }
}

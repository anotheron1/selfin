package ru.selfin.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.selfin.backend.dto.RecurringRuleCreateDto;
import ru.selfin.backend.dto.RecurringRuleDto;
import ru.selfin.backend.service.RecurringRuleService;

@RestController
@RequestMapping("/api/v1/recurring-rules")
@RequiredArgsConstructor
public class RecurringRuleController {

    private final RecurringRuleService ruleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringRuleDto create(@Valid @RequestBody RecurringRuleCreateDto dto) {
        return ruleService.createRule(dto);
    }
}

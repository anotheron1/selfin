package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialEventService {

        private final FinancialEventRepository eventRepository;
        private final CategoryRepository categoryRepository;

        public List<FinancialEventDto> findByPeriod(LocalDate start, LocalDate end) {
                return eventRepository.findAllByDeletedFalseAndDateBetweenOrderByDateAsc(start, end)
                                .stream().map(this::toDto).toList();
        }

        /**
         * Идемпотентное создание события.
         * Если событие с данным idempotencyKey уже существует — возвращаем его без
         * повторного создания.
         */
        @Transactional
        public FinancialEventDto createIdempotent(UUID idempotencyKey, FinancialEventCreateDto dto) {
                return eventRepository.findByIdempotencyKey(idempotencyKey)
                                .map(this::toDto)
                                .orElseGet(() -> {
                                        Category category = categoryRepository.findById(dto.categoryId())
                                                        .filter(c -> !c.isDeleted())
                                                        .orElseThrow(() -> new RuntimeException(
                                                                        "Category not found: " + dto.categoryId()));
                                        FinancialEvent event = FinancialEvent.builder()
                                                        .idempotencyKey(idempotencyKey)
                                                        .date(dto.date())
                                                        .category(category)
                                                        .type(dto.type())
                                                        .plannedAmount(dto.plannedAmount())
                                                        .factAmount(dto.factAmount())
                                                        .mandatory(Boolean.TRUE.equals(dto.mandatory()))
                                                        .description(dto.description())
                                                        .rawInput(dto.rawInput())
                                                        .build();
                                        return toDto(eventRepository.save(event));
                                });
        }

        @Transactional
        public FinancialEventDto update(UUID id, FinancialEventCreateDto dto) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new RuntimeException("Event not found: " + id));
                Category category = categoryRepository.findById(dto.categoryId())
                                .filter(c -> !c.isDeleted())
                                .orElseThrow(() -> new RuntimeException("Category not found: " + dto.categoryId()));

                // Сохраняем старый факт ДО перезаписи — для корректного аудит-лога
                BigDecimal oldFact = event.getFactAmount();

                event.setDate(dto.date());
                event.setCategory(category);
                event.setType(dto.type());
                event.setPlannedAmount(dto.plannedAmount());
                event.setFactAmount(dto.factAmount());
                event.setMandatory(Boolean.TRUE.equals(dto.mandatory()));
                event.setDescription(dto.description());
                event.setRawInput(dto.rawInput());
                // Авто-статус: если передан factAmount → EXECUTED, иначе оставляем PLANNED
                if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED) {
                        event.setStatus(EventStatus.EXECUTED);
                } else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED) {
                        event.setStatus(EventStatus.PLANNED);
                }

                // Аудит-лог изменения фактической суммы
                if (dto.factAmount() != null) {
                        BigDecimal delta = dto.factAmount()
                                        .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
                        log.info("fact_update event_id={} category={} planned={} fact_old={} fact_new={} delta={}",
                                        id, category.getName(),
                                        dto.plannedAmount(), oldFact, dto.factAmount(), delta);
                }
                return toDto(eventRepository.save(event));
        }

        @Transactional
        public void softDelete(UUID id) {
                FinancialEvent event = eventRepository.findById(id)
                                .orElseThrow(() -> new RuntimeException("Event not found: " + id));
                event.setDeleted(true);
                eventRepository.save(event);
        }

        public FinancialEventDto toDto(FinancialEvent e) {
                return new FinancialEventDto(
                                e.getId(), e.getDate(),
                                e.getCategory().getId(), e.getCategory().getName(),
                                e.getType(), e.getPlannedAmount(), e.getFactAmount(),
                                e.getStatus(), e.isMandatory(), e.getDescription(),
                                e.getRawInput(), e.getCreatedAt());
        }
}

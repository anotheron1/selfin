package ru.selfin.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.dto.FinancialEventCreateDto;
import ru.selfin.backend.dto.FinancialEventDto;
import ru.selfin.backend.dto.FinancialEventUpdateFactDto;
import ru.selfin.backend.exception.ResourceNotFoundException;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.FinancialEvent;
import ru.selfin.backend.model.enums.EventStatus;
import ru.selfin.backend.repository.CategoryRepository;
import ru.selfin.backend.repository.FinancialEventRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Сервис управления финансовыми событиями (доходы, расходы, переводы в фонды).
 * Реализует план-факт модель: каждое событие хранит плановую сумму ({@code plannedAmount})
 * и фактическую ({@code factAmount}). Статус управляется автоматически по наличию факта.
 *
 * <p>Все операции мутации защищены идемпотентностью через клиентский {@code Idempotency-Key}
 * (для создания) или soft-delete вместо физического удаления.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialEventService {

        private final FinancialEventRepository eventRepository;
        private final CategoryRepository categoryRepository;

        /**
         * Возвращает все не удалённые события за период {@code [start, end]} включительно,
         * отсортированные по дате по возрастанию.
         *
         * @param start начало периода (включительно)
         * @param end   конец периода (включительно)
         * @return список DTO событий, пустой список если событий нет
         */
        public List<FinancialEventDto> findByPeriod(LocalDate start, LocalDate end) {
                return eventRepository.findAllByDeletedFalseAndDateBetweenOrderByDateAsc(start, end)
                                .stream().map(this::toDto).toList();
        }

        /**
         * Идемпотентное создание события.
         * Если событие с данным {@code idempotencyKey} уже существует — возвращает его без
         * повторного создания в БД. Это защищает от дублирования при сетевых ретраях.
         *
         * @param idempotencyKey UUID, сгенерированный клиентом; должен быть уникален на одно намерение
         * @param dto            данные нового события
         * @return созданное или найденное по ключу событие
         * @throws ResourceNotFoundException если указанная категория не найдена или удалена
         */
        @Transactional
        public FinancialEventDto createIdempotent(UUID idempotencyKey, FinancialEventCreateDto dto) {
                return eventRepository.findByIdempotencyKey(idempotencyKey)
                                .map(this::toDto)
                                .orElseGet(() -> {
                                        Category category = categoryRepository.findById(dto.categoryId())
                                                        .filter(c -> !c.isDeleted())
                                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                                        "Category", dto.categoryId()));
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

        /**
         * Полное обновление события: все поля заменяются значениями из {@code dto}.
         * Статус пересчитывается автоматически: наличие {@code factAmount} → {@code EXECUTED},
         * отсутствие → {@code PLANNED}. Изменение факта пишется в аудит-лог.
         *
         * @param id  идентификатор существующего события
         * @param dto новые данные для всех полей события
         * @return обновлённое событие
         * @throws ResourceNotFoundException если событие или категория не найдены / удалены
         */
        @Transactional
        public FinancialEventDto update(UUID id, FinancialEventCreateDto dto) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
                Category category = categoryRepository.findById(dto.categoryId())
                                .filter(c -> !c.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("Category", dto.categoryId()));

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

        /**
         * Частичное обновление: только фактическая сумма и комментарий.
         * Используется {@code PATCH /events/{id}/fact} из UI-экрана "Бюджет".
         * Не требует пересылки всего тела события — меняет только то, что вводит пользователь.
         * Статус пересчитывается так же, как в {@link #update}.
         *
         * @param id  идентификатор существующего события
         * @param dto фактическая сумма (может быть {@code null} для снятия отметки) и комментарий
         * @return обновлённое событие
         * @throws ResourceNotFoundException если событие не найдено или удалено
         */
        @Transactional
        public FinancialEventDto updateFact(UUID id, FinancialEventUpdateFactDto dto) {
                FinancialEvent event = eventRepository.findById(id)
                                .filter(e -> !e.isDeleted())
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));

                BigDecimal oldFact = event.getFactAmount();
                event.setFactAmount(dto.factAmount());
                if (dto.description() != null) {
                        event.setDescription(dto.description());
                }

                // Авто-статус по наличию фактической суммы
                if (dto.factAmount() != null && event.getStatus() == EventStatus.PLANNED) {
                        event.setStatus(EventStatus.EXECUTED);
                } else if (dto.factAmount() == null && event.getStatus() == EventStatus.EXECUTED) {
                        event.setStatus(EventStatus.PLANNED);
                }

                // Аудит-лог
                BigDecimal delta = (dto.factAmount() != null ? dto.factAmount() : BigDecimal.ZERO)
                                .subtract(oldFact != null ? oldFact : BigDecimal.ZERO);
                log.info("fact_patch event_id={} category={} fact_old={} fact_new={} delta={}",
                                id, event.getCategory().getName(), oldFact, dto.factAmount(), delta);

                return toDto(eventRepository.save(event));
        }

        /**
         * Помечает событие как удалённое (soft delete).
         * Событие не удаляется физически и не возвращается в запросах по периоду,
         * но сохраняется в БД для аудита.
         *
         * @param id идентификатор события
         * @throws ResourceNotFoundException если событие не найдено
         */
        @Transactional
        public void softDelete(UUID id) {
                FinancialEvent event = eventRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("FinancialEvent", id));
                event.setDeleted(true);
                eventRepository.save(event);
        }

        /**
         * Конвертирует entity в DTO для передачи клиенту.
         * Включает денормализованные поля категории ({@code categoryId}, {@code categoryName})
         * для удобства рендеринга без дополнительных запросов.
         *
         * @param e entity финансового события
         * @return DTO с полными данными события
         */
        public FinancialEventDto toDto(FinancialEvent e) {
                return new FinancialEventDto(
                                e.getId(), e.getDate(),
                                e.getCategory().getId(), e.getCategory().getName(),
                                e.getType(), e.getPlannedAmount(), e.getFactAmount(),
                                e.getStatus(), e.isMandatory(), e.getDescription(),
                                e.getRawInput(), e.getCreatedAt());
        }
}

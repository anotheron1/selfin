package ru.selfin.backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.selfin.backend.model.Account;
import ru.selfin.backend.model.Category;
import ru.selfin.backend.model.enums.AccountKind;
import ru.selfin.backend.model.enums.CategoryType;
import ru.selfin.backend.model.enums.Priority;
import ru.selfin.backend.repository.AccountRepository;
import ru.selfin.backend.repository.CategoryRepository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static ru.selfin.backend.model.enums.CategoryType.EXPENSE;
import static ru.selfin.backend.model.enums.CategoryType.INCOME;
import static ru.selfin.backend.model.enums.Priority.HIGH;
import static ru.selfin.backend.model.enums.Priority.MEDIUM;

/**
 * Регистрация нового человека (ANO-151): строка {@code users} и стартовый набор — одной транзакцией. Второй
 * первый запрос того же человека ждёт на уникальности {@code external_id}, пока набор не ляжет, и пустого
 * приложения не видит; набор не лёг — нет и строки, следующий запрос заведёт человека заново.
 *
 * <p>Владелец потока — уже новый человек: вызывающий ставит его до транзакции. Соединение пул выдаёт с ним
 * (RLS пропустит вставку), сессия Hibernate берёт его арендатором ({@code @TenantId} поставит {@code user_id}).
 * Транзакция новая: в чужой сессия уже с чужим арендатором.
 *
 * <p>Спека: docs/superpowers/specs/2026-10-15-starter-set-design.md
 */
@Service
@RequiredArgsConstructor
public class Registration {

    /**
     * Ядро категорий v4 — раздел 4.6 {@code 2026-09-18-journal-categories-requirements.md}. Бронь — где сумма и
     * дата известны заранее, остальное — ожидание: решение владельца 15.10. Прогноз выключен, как у всех (V21).
     */
    static final List<Seed> CORE = List.of(
            new Seed("Жильё и быт", EXPENSE, HIGH),
            new Seed("Связь и подписки", EXPENSE, HIGH),
            new Seed("Образование", EXPENSE, HIGH),
            new Seed("Обязательные платежи", EXPENSE, HIGH),
            new Seed("Транспорт", EXPENSE, MEDIUM),
            new Seed("Продукты", EXPENSE, MEDIUM),
            new Seed("Кафе и доставка", EXPENSE, MEDIUM),
            new Seed("Здоровье", EXPENSE, MEDIUM),
            new Seed("Забота о себе", EXPENSE, MEDIUM),
            new Seed("Покупки", EXPENSE, MEDIUM),
            new Seed("Досуг и поездки", EXPENSE, MEDIUM),
            new Seed("Подарки и помощь", EXPENSE, MEDIUM),
            new Seed("Прочее", EXPENSE, MEDIUM),
            // Зарплата — бронь: журнал раскладывает по характеру, и доход-ожидание ушёл бы из недель в карточки.
            new Seed("Зарплата", INCOME, HIGH),
            new Seed("Подработка", INCOME, MEDIUM),
            new Seed("Прочий доход", INCOME, MEDIUM));

    private final EntityManager entityManager;
    private final CategoryRepository categories;
    private final AccountRepository accounts;

    /**
     * Заводит человека со стартовым набором и его согласием (ANO-196).
     *
     * @return {@code false} — строку с этим {@code sub} уже завёл параллельный запрос; набор у неё свой
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean register(UUID id, String sub, Consent consent) {
        int created = entityManager.createNativeQuery("""
                        INSERT INTO users (id, external_id, consent_version, consent_at) VALUES (?1, ?2, ?3, ?4)
                        ON CONFLICT (external_id) DO NOTHING""")
                .setParameter(1, id).setParameter(2, sub)
                .setParameter(3, consent.version()).setParameter(4, consent.at().atOffset(ZoneOffset.UTC))
                .executeUpdate();
        if (created == 0) return false;
        categories.saveAll(CORE.stream().map(Seed::category).toList());
        // Как сид V20: счёт-приёмник безадресных операций — ввод остатка без выбора счёта идёт сюда.
        accounts.save(Account.builder().name("Основная карта").kind(AccountKind.DEBIT).trackBalance(true)
                .defaultAccount(true).sortOrder(0).build());
        return true;
    }

    record Seed(String name, CategoryType type, Priority priority) {
        Category category() {
            return Category.builder().name(name).type(type).priority(priority).build();
        }
    }
}

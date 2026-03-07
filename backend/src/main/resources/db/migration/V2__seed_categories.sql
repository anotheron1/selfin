-- Flyway V2: Начальные категории для нового пользователя
-- Без этих данных FAB (быстрый ввод) не работает — нечего выбрать в списке

INSERT INTO categories (id, name, type, is_mandatory, is_deleted) VALUES
    -- Доходы
    (gen_random_uuid(), 'Зарплата',         'INCOME',  FALSE, FALSE),
    (gen_random_uuid(), 'Фриланс',          'INCOME',  FALSE, FALSE),
    (gen_random_uuid(), 'Прочий доход',     'INCOME',  FALSE, FALSE),

    -- Обязательные расходы
    (gen_random_uuid(), 'Аренда / ЖКХ',    'EXPENSE', TRUE,  FALSE),
    (gen_random_uuid(), 'Кредит / Долг',   'EXPENSE', TRUE,  FALSE),
    (gen_random_uuid(), 'Интернет / Связь','EXPENSE', TRUE,  FALSE),
    (gen_random_uuid(), 'Здоровье',        'EXPENSE', TRUE,  FALSE),

    -- Переменные расходы
    (gen_random_uuid(), 'Еда / Продукты',  'EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Кафе / Рестораны','EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Транспорт',       'EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Одежда',          'EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Развлечения',     'EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Подписки',        'EXPENSE', FALSE, FALSE),
    (gen_random_uuid(), 'Прочее',          'EXPENSE', FALSE, FALSE);

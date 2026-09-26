# Ошибка клиента — не 500 (ANO-85)

Спека и план одним документом (план спринта §7). Бэк; очередь владельца 25.09, пункт 5.

## Что сломано

`GlobalExceptionHandler` разбирал семь типов исключений, остальное уходило в `@ExceptionHandler(Exception.class)` → 500 «Internal server error». Туда попадали и ошибки клиента — и 500 переставал быть сигналом поломки: забытый параметр и настоящее падение выглядели одинаково.

Замер на стенде 26.09, через API (исключение — из лога):

| запрос | было | исключение |
|---|---|---|
| `GET /analytics/multi-month` без дат | 500 | `MissingServletRequestParameterException` |
| `GET /analytics/multi-month?startDate=abc` | 500 | `MethodArgumentTypeMismatchException` |
| `POST /events`, `Idempotency-Key: not-a-uuid` | 500 | `MethodArgumentTypeMismatchException` |
| `POST /wishlist/items/undefined/convert` | 500 | `MethodArgumentTypeMismatchException` |
| `GET /analytics/nonexistent` | 500 | `NoResourceFoundException` |
| `DELETE /analytics/dashboard`, `GET /events/{id}` | 500 | `HttpRequestMethodNotSupportedException` |
| `POST /funds`, `Content-Type: text/plain` | 500 | `HttpMediaTypeNotSupportedException` |

## Решение

* **Стандартные ошибки Spring MVC несут свой статус** (интерфейс `org.springframework.web.ErrorResponse`): пропущенный параметр 400, нет пути 404, не тот метод 405, не тот тип тела 415. Общий обработчик отдаёт этот статус и текст ошибки, а не 500. Одна ветка на весь класс, а не по обработчику на тип: следующая такая ошибка тоже не станет пятисоткой.
* **Кривое значение — своя ветка, 400:** `MethodArgumentTypeMismatchException` своего статуса не несёт. Сюда же ключ идемпотентности не-UUID и UUID «undefined» в пути.
* В 500 остаётся только то, чего никто не ждал.

## Не закрывает

«Заодно» из задачи — ключ идемпотентности у фактов. `POST /events/facts` заголовок `Idempotency-Key` не читает вовсе, поэтому «принимает любую строку»: факт не идемпотентен. Это не про 500 — отдельная задача ANO-192.

## Как проверяется

| что | тест | мутация |
|---|---|---|
| семь запросов со стенда — 400/404/405/415, тело согласно со статусом и не «Internal server error» | `ErrorStatusesIT`, через настоящие ручки | обработчик кривых значений снят; ветка стандартных ошибок снята; статус стандартной ошибки не свой, а всегда 400 |

---
# Выполнено

26.09.2026, ветка `fix/ano-85-error-statuses`.

* `ErrorStatusesIT` — семь тестов, все красные до правки (500), зелёные после.
* Три мутации, каждая отдельно, все красные, откат сверен.
* Стенд, тот же замер после правки: 400 «Required parameter 'startDate' is not present.»; 400 «Invalid value for startDate: abc»; 400 «Invalid value for Idempotency-Key: not-a-uuid»; 400 «Invalid value for itemId: undefined»; 404 «No static resource …»; 405 «Method 'DELETE' is not supported.»; 415 «Content-Type 'text/plain…' is not supported.». Контроль: `GET /pocket` — 200.

## Правила продукта — сверка

Канон `2026-09-01-product-rules.md`, 26.09. Правка — ответы API, не экран, поэтому по существу касается одно правило:

| правило | как соблюдено |
|---|---|
| 5 «ни один экран не сообщает, что завёл неправильно» | 400/404/405/415 — ответы на ошибки программы-клиента: фронт шлёт верные параметры и эти ответы человеку не показывает. Тексты ответов — для разработчика и логов, по-английски, как и прежний «Internal server error» |
| 12 без упрёков, 13 слова пользователей | экранных текстов не меняли |
| остальные | не касаются |

Дефектов сверка не нашла.

## Ревью Codex (#76) — два P2, оба подтвердились

* **400 ловил и поломку сервера.** Ветка стояла на `TypeMismatchException`, а от него идёт и `MethodArgumentConversionNotSupportedException` — у сервера нет конвертера для типа параметра. Воспроизведено: параметр такого типа давал 400. Ветка теперь на `MethodArgumentTypeMismatchException` — кривое значение от клиента; нет конвертера — 500.
* **5xx рассказывал о себе.** Стандартная ошибка Spring со статусом 500 уходила клиенту со своим текстом: «Required path variable 'secretVariableName' is not present.» — имя переменной из дефекта маппинга. Теперь при 5xx статус её, текст — «Internal server error», подробности — в лог.

`GlobalExceptionHandlerServerErrorTest` — три случая через MockMvc; два красные до правки. Мутации: вернуть общий `TypeMismatchException`, вернуть текст Spring для 5xx, снять ветку кривого значения — все красные.

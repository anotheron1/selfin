# Ревью MCP-сервера Selfin

## Сводка — пять строк

1. В текущем виде в `main` вливать нельзя: две MCP-сессии одного пользователя могут одновременно записать одинаковый план или факт.
2. Остался уже известный по #246 блокер: сервер молча отбрасывает неизвестные аргументы инструментов и может выполнить не ту финансовую операцию.
3. Базовый Streamable HTTP и OAuth-контур по MCP 2025-11-25 реализованы в целом правильно: Origin, session id, metadata, JWT и token exchange проверены кодом и живым HTTP smoke-тестом.
4. Локально прошли MCP `57/57` и `RecurringEventControllerIT` `12/12`; CI головы `4ba92c1` зелёный.
5. Безопасный порядок интеграции: #247, затем исправленный #246, затем перебазированный и исправленный #248; с текущей веткой дизайна текстового конфликта не найдено.

Проверял diff `origin/main...HEAD` (`4f84aa3...4ba92c1`), README, PR #246/#247/#248 и их комментарии. Нормативная база — MCP 2025-11-25, которую заявляет PR; более новая sessionless-модель 2026 года не является заявленной возможностью этого endpoint.

## 1. Соответствие спецификации MCP

### Находка 1 — отмена формы превращается в окончательный отказ

- **Файл и строки:** `mcp/src/main/java/ru/selfin/mcp/SelfinMcp.java:327-337`, `PlanTools.java:176-185`, `FactTools.java:69-80`.
- **Что не так:** `confirm()` возвращает `false` и для `decline`, и для `cancel`; затем черновик навсегда закрывается как `declined`.
- **Сценарий:** пользователь закрыл форму клавишей Escape или окно клиента временно пропало (`action=cancel`) → повтор того же `draftId` уже не показывает форму и возвращает `declined`, хотя пользователь не отказывался.
- **Серьёзность:** **до выкатки на сервер**.
- **Как проверил:** трассировка указанных веток. MCP различает `accept`, `decline`, `cancel` и рекомендует после cancel разрешать повтор: [Elicitation, Response Actions](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation#response-actions).
- **Минимальная правка:** вернуть из `confirm` три состояния; закрывать черновик только при `decline`, а при `cancel` оставить его подготовленным.

### Находка 2 — сервер молча принимает неизвестные аргументы инструмента

- **Файл и строки:** `mcp/src/main/java/ru/selfin/mcp/SelfinMcp.java:340-350`.
- **Что не так:** общий `McpJsonDefaults` конвертирует карту в record без строгой проверки неизвестных полей. Это уже отмечено в незакрытом ревью #246 и в текущей голове не исправлено.
- **Сценарий:** клиент передаёт в `prepare_fact` `{"amount":"1000","onCredit":true}` или `{"type":"INCOME"}` → сервер отбрасывает неподдерживаемое поле и готовит обычный расход; после подтверждения состояние Selfin меняется не так, как ожидал человек.
- **Серьёзность:** **блокирует вливание**.
- **Как проверил:** код `arguments()` и актуальный комментарий/красный gate PR #246; MCP требует валидировать все входы, а ошибки валидации возвращать как tool execution error: [Tools, Error Handling](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling).
- **Минимальная правка:** отдельный строгий mapper для аргументов инструментов с отказом на неизвестных полях, включая вложенные `rows` и `monthly`.

### Остальной результат по протоколу

- Инициализацию, согласование capabilities и `notifications/initialized` выполняет Java SDK; сервер объявляет `tools`, structured content дублируется JSON-текстом (`SelfinMcp.java:290-301, 353-360`), что соответствует [Tools / Structured Content](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#structured-content).
- Form elicitation не отправляется клиенту без `elicitation.form`; пустой объект `elicitation:{}` принимается как совместимый form mode, что соответствует [Elicitation / Capabilities](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation#capabilities).
- Tool-аннотации есть у всех инструментов (`SelfinMcp.java:318-324`): чтение отмечено `readOnly`, записи — не read-only, операции объявлены идемпотентными. Явного неверного annotation-сценария не найдено.
- Бизнес-ошибки, ошибки API и валидации возвращаются через `isError=true` (`SelfinMcp.java:353-385`), а неизвестный инструмент и структурно неверный JSON остаются протокольными ошибками SDK. Это соответствует [двум типам ошибок tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#error-handling).
- Streamable HTTP: единый `/mcp`, POST/GET/DELETE и `MCP-Protocol-Version` делегированы `HttpServletStreamableServerTransportProvider`; собственный фильтр связывает state с `MCP-Session-Id` и возвращает 404 для неизвестной/чужой сессии (`RemoteMcp.java:143-166`). Код ответа на GET/DELETE и проверку версии отдельно тесты проекта не покрывают; это пробел тестов, но подтверждённого нарушения SDK не найдено. Нормативные требования: [Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
- `Origin` проверяется до аутентификации (`RemoteMcp.java:120-124`); локальный HTTP по умолчанию привязан к `127.0.0.1` (`RemoteMcp.java:82-83`), а `0.0.0.0` задаётся только внутри Compose-сети. Живой smoke-тест дал 403 для `Origin: https://evil.example`.

## 2. Авторизация

### Находка 3 — первоначальный challenge требует право записи, а step-up не работает

- **Файл и строки:** `mcp/src/main/java/ru/selfin/mcp/RemoteMcp.java:72-74, 139-141, 191-195`; `ProvisionKeycloak.java:79-80`.
- **Что не так:** первый 401 объявляет сразу `selfin-mcp:read selfin-mcp:write`, хотя read — default scope, write — optional. Если подключение всё же создано без write, вызов `save_*` даёт успешный HTTP с tool error, а не 403 `insufficient_scope` с требуемым scope.
- **Сценарий:** пользователь хочет только читать → MCP-клиент по authoritative challenge просит и запись. Либо пользователь выдал только read и позже вызывает `save_plan` → клиент не получает стандартный 403 challenge и не может автоматически запросить write.
- **Серьёзность:** **до выкатки на сервер**.
- **Как проверил:** живой GET `/mcp` вернул `401` и `scope="selfin-mcp:read selfin-mcp:write"`; трассировка write guard. Спецификация требует начинать с минимальных scopes и для runtime-недостатка рекомендует 403 с `error="insufficient_scope"`: [Scope Selection и Runtime Errors](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#scope-selection-strategy).
- **Минимальная правка:** первоначально запрашивать только read; для write-вызова возвращать транспортный 403 challenge с `scope="selfin-mcp:read selfin-mcp:write"`.

### Находка 4 — отсутствуют ограничение частоты и аудит MCP-запросов

- **Файл и строки:** `mcp/src/main/java/ru/selfin/mcp/RemoteMcp.java:38-44, 115-188`; `PlanTools.java:52-56`; `FactTools.java:40-43`.
- **Что не так:** есть только общий предел 256 сессий, но нет per-user rate limit и лимита черновиков в сессии; закрытые drafts из map не удаляются до истечения двух часов. В обработчиках auth/session/tool нет audit log; runtime-образ показал только сообщения запуска Tomcat.
- **Сценарий:** один валидный аккаунт создаёт 256 сессий → остальные получают 503; либо за две часа создаёт большое число черновиков → растёт heap. После злоупотребления или спорной записи по логам MCP нельзя установить `sub`, client/session, tool и результат.
- **Серьёзность:** **до выкатки на сервер**.
- **Как проверил:** поиск `Logger|log.` по `mcp/src/main/java` не нашёл журналирования; живой `docker logs`; просмотр maps и `MAX_SESSIONS`. MCP требует rate limit tool invocations: [Tools, Security Considerations](https://modelcontextprotocol.io/specification/2025-11-25/server/tools#security-considerations).
- **Минимальная правка:** небольшой per-sub лимит сессий/запросов и ограничение/удаление завершённых drafts; структурированный audit без bearer/secret и без финансового payload целиком.

### Остальной результат по OAuth

- Protected Resource Metadata есть по `/.well-known/oauth-protected-resource/mcp`, содержит точный `resource`, authorization server, scopes и bearer method (`RemoteMcp.java:87-98`). 401 содержит `resource_metadata`; smoke-тест подтвердил оба ответа. Это соответствует [MCP Authorization / discovery](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization#protected-resource-metadata-discovery-requirements) и RFC 9728.
- MCP JWT проверяется по RS256/JWKS, issuer, `typ=Bearer`, `sub`, `exp`, `nbf`, аудиториям точного MCP URL и `selfin-mcp`, а также scope (`KeycloakAccess.java:51-71, 122-130`). Invalid/expired token получает 401.
- Исходный bearer не уходит в backend. Он передаётся только Keycloak token endpoint как `subject_token`; backend получает новый токен audience=`selfin`. Ответ token exchange снова проверяется по подписи/issuer/сроку, точной audience и тому же `sub` (`KeycloakAccess.java:77-117`). Подмену пользователя в этом пути не нашёл.
- Public OAuth client создаётся с exact redirect URI, authorization code flow, PKCE S256, consent и без implicit/direct grants (`ProvisionKeycloak.java:42-80`). Exchange client confidential, секрет приходит из `.env`/container env и не печатается.
- В URL токены не попадают; явных утечек bearer/secret в коде логов не найдено. Отдельный недостаток аудита описан выше.

## 3. Изоляция и подделка

### Находка 5 — две сессии одного человека обходят блокировку двойной записи

- **Файл и строки:** `mcp/src/main/java/ru/selfin/mcp/RemoteMcp.java:219-238`; `PlanTools.java:52-56, 170-232`; `FactTools.java:40-43, 64-89`; `backend/src/main/java/ru/selfin/backend/service/FinancialEventService.java:154-208`.
- **Что не так:** `PlanTools.State`, `FactTools.State` и lock `writes` создаются на каждую MCP-сессию. Идемпотентность backend защищает только одинаковый key, а каждый draft генерирует свой UUID.
- **Сценарий:** один пользователь открывает две MCP-сессии, в обеих до записи готовит одинаковые 1 500 ₽ на 12 декабря и одновременно подтверждает → обе сессии проходят `stillCurrent`, отправляют разные `Idempotency-Key`, backend законно создаёт две записи. То же возможно для факта. Это не чтение чужого draft, а двойная запись владельца из двух его сессий.
- **Серьёзность:** **блокирует вливание**.
- **Как проверил:** трассировка lifetime state от `RemoteMcp.Session` до двух независимых locks; backend дедуплицирует только по key. Тест `PlanToolsTest.serializesFreshnessCheckAndFirstWriteOfOverlappingDrafts` доказывает защиту лишь для двух drafts одного `State` (`PlanToolsTest.java:240-260`); межсессионного теста нет.
- **Минимальная правка:** сериализовать freshness-check + первую запись по стабильному user key (`sub`) между всеми сессиями процесса либо перенести атомарную защиту от содержательного дубля в backend; добавить тест с двумя `State` одного пользователя.

### Остальной результат по изоляции

- Другой человек или другая login/client-сессия не может использовать session id: сравниваются `sub`, `azp`, `sid` и наличие write scope, при несовпадении возвращается 404 (`RemoteMcp.java:152-162, 219-239`). Draft хранится только внутри соответствующего Session.
- Две сессии одного человека не видят drafts друг друга — это правильно для конфиденциальности, но создаёт гонку из находки 5.
- Два подтверждения одного draft сериализованы `synchronized(draft)` и повтор возвращает прежний/идемпотентный результат. Два overlapping drafts одной сессии сериализованы `writes`; тест это подтверждает.
- После перезапуска процесса все session ids и drafts теряются. Повтор со старым session id даёт 404; незавершённую операцию нужно сверить в журнале и подготовить заново. Это fail-closed и не раскрывает чужие данные, но незавершённое подтверждение не переживает deploy.

## 4. Развёртывание

### Вывод

Подтверждённых блокеров Compose/nginx/Dockerfile не найдено.

- Профиль `mcp` поднимает отдельные `mcp-keycloak` и `mcp`; без `COMPOSE_PROFILES=mcp` они не входят в обычный запуск (`deploy/docker-compose.yml:64-99`, `.env.example:32-38`).
- Когда MCP выключен, nginx всё равно перехватывает только точные `/mcp` и metadata path; имя upstream разрешается на запрос через уже существующий Docker resolver (`frontend/nginx.conf:21-30, 51-59`). Обычные SPA/API/auth маршруты не меняются.
- Nginx сохраняет Authorization и `Mcp-*` headers по умолчанию, явно сохраняет Host, включает HTTP/1.1, отключает buffering и ограничивает body тем же 1 MiB, что transport. Keepalive transport — 15 секунд, поэтому глобальный `proxy_read_timeout 60s` не должен обрывать ожидающую elicitation SSE-сессию.
- `deploy/update.sh:9-15` собирает MCP только при активном профиле; обычное обновление продолжает тянуть frontend/backend/DB/Keycloak и не требует MCP.
- Docker image успешно собран: `linux/amd64`, 307 789 895 байт (около 294 MiB), runtime Temurin JRE `21.0.12`, пользователь `10001:10001`, не root (`mcp/Dockerfile:1-12`). Размер не блокирует, но для одного jar образ тяжёлый — **мелочь**; при необходимости позже заменить runtime base на более компактный JRE.

## 5. Сборка и тесты

### Локально

- `JAVA_HOME=C:\Users\Kirill\.jdks\jbr-21.0.8; .\backend\mvnw.cmd -f mcp/pom.xml verify` — **57 тестов, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Из `backend/`: `.\mvnw.cmd verify -Dit.test=RecurringEventControllerIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` — **12 тестов, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Первый PowerShell-запуск без кавычек разобрал `-Dit.test` как lifecycle phase; корректный повтор с аргументами-строками прошёл.
- `docker build` — успешно. Runtime smoke: metadata — 200, `/mcp` без bearer — 401 с RFC 9728 challenge, чужой Origin — 403.
- `git diff --check origin/main...HEAD` — ошибок нет.

### CI и PR

- #248, head `4ba92c1`: workflow `CI` и gate ответов Codex — **все jobs success**; Maven MCP входит в backend job.
- #247, head `993bc90`: все функциональные checks и gate — **success**, merge state `CLEAN`.
- #246, head `c36fd37`: backend/frontend/tools/spec/migrations/image checks — **success**, но последний `Codex — все замечания отвечены` — **failure** из-за незакрытых замечаний. Merge state `UNSTABLE`; это процессный блокер в дополнение к находке 2.
- Тесты не покрывают HTTP lifecycle целиком, межсессионную гонку, restart state и OAuth token exchange через настоящий Keycloak. PR #248 сообщает ручную проверку OAuth/двух профилей, но это не автоматический regression test.

## 6. Вливание в `main` и параллельный дизайн

### Порядок

1. **#247** — сначала: backend получает `GET /api/v1/events/indefinite-plan-rules`; отдельно этот read-only route ничего не ломает.
2. **#246** — после исправления неизвестных аргументов и закрытия красного PR-gate. Если влить #246 без #247, `prepare_plan` всегда вызывает `indefinitePlanRules()` (`PlanTools.java:114-121`) и на текущем backend получит 404; планирование/запись MCP сломаются, хотя простое чтение части tools останется.
3. **#248** — после rebase/retarget на обновлённый `main` и исправления находок 1, 3, 4, 5. Сейчас его base — `codex/selfin-mcp-http-base`, поэтому напрямую в `main` его вливать нельзя как самостоятельный PR.

Если влить только #247 — появится безвредный read-only API. Если добавить #246 — заработает локальный stdio MCP. Только #248 добавляет публичный Streamable HTTP/OAuth/Compose.

### Параллельная веха «Дизайн»

- На GitHub сейчас открыты только #246/#247/#248; PR с milestone `Дизайн` и сам GitHub milestone не найдены.
- Для фактически идущей локальной ветки дизайна `claude/slice4-pr6-credit-goals` diff меняет много `frontend/src/**`, но не `frontend/nginx.conf`, `deploy/**` или `mcp/**`. Единственный общий файл с MCP-base — `backend/src/test/java/ru/selfin/backend/TwoPeopleIsolationIT.java`; `git merge-tree` пометил файл как изменённый с обеих сторон, но конфликтных маркеров не создал — добавления автосовместимы.
- После фактического merge дизайна всё равно нужен rebase и полный CI: ветка дизайна меняет настройку тестового пользователя в `TwoPeopleIsolationIT`, поэтому семантическую совместимость нельзя считать подтверждённой одним merge-tree.

## Вердикт

**Можно ли влить в `main` сейчас, не мешая вехе «Дизайн»: нет, потому что межсессионная гонка допускает двойную финансовую запись, неизвестные поля инструментов могут изменить смысл подтверждаемой операции, а #246 имеет красный обязательный gate.**

После исправления находок 2 и 5, закрытия gate #246 и порядка `#247 → #246 → rebase/fix #248` — да; обнаруженного текстового конфликта с текущей веткой дизайна нет.

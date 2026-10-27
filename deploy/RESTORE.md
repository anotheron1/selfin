# Восстановление из копии

Шаг 5 плана спринта 6, спека `docs/superpowers/specs/2026-10-18-backups-design.md`; режимы копий — ANO-243, спека `2026-10-25-backups-local-mode-design.md`. Команды — из корня репозитория на твоём компьютере; `<сервер>` — вход по SSH, на сервере клон лежит в `/opt/selfin`.

Режим — `SELFIN_BACKUP_MODE` в `.env` сервера:

* **`local`** — на запуске (решение владельца 24.10): копии лежат на самом сервере открытым текстом, до 7 дней. Умрёт сервер — умрут и они; копии вне сервера — ANO-239.
* **`external`** — включает ANO-239: копии зашифрованы открытым ключом и лежат ещё и во внешнем хранилище, 28 дней. Ключ на сервер не попадает: расшифровка — на твоём компьютере, на сервер идёт поток.

**Выкатку — на время восстановления остановить** (ANO-246), до шага 4: таймер, и дождаться прохода, если он уже идёт. Остановленный таймер прошедший проход не гасит: тот доиграет — дождётся новой версии и, может быть, вернёт прежнюю через `update.sh` прямо посреди восстановления (ревью Codex на #202). Проход не обрывать: оборванные `git checkout` или `docker compose` оставят недоделанное.

```bash
ssh <сервер> 'systemctl stop selfin-release.timer; while systemctl is-active -q selfin-release.service; do sleep 5; done; echo "выкатка не идёт"'
```

Проход идёт до 12 минут: обновление, 5 минут ожидания, возврат. Дольше — завис, тогда `systemctl stop selfin-release.service`. После шага 5 — `systemctl start selfin-release.timer`. Включённый таймер переведёт сервер на `release`, если та впереди восстановленной версии: миграции пройдут по восстановленным данным, как при обычном обновлении. Коммит из `.release-failed` он не тронет.

Нужно: твой журнал удалений (`~/selfin-deletions.jsonl`), туннель к консоли Keycloak (`ssh -L 8180:127.0.0.1:8180 <сервер>`); для `external` — ещё секретный ключ копий (`selfin-backup.key`) и `age` (`winget install --id FiloSottile.age -e`). Без журнала проверка не сойдётся: удаление после копии есть только в нём. Если ты никого не удалял — создай пустой: `touch ~/selfin-deletions.jsonl`.

## 1. Какую копию

```bash
ssh <сервер> 'cd /opt/selfin/deploy && docker compose exec -T backup ls /backups'          # на сервере: до 7 дней
ssh <сервер> 'cd /opt/selfin/deploy && docker compose exec -T backup sh -c "rclone lsf store:\$SELFIN_BACKUP_BUCKET"'   # external: в хранилище — 28
```

Каталог копии — время в UTC с секундами, например `2026-10-19T003000Z`. Бери самую свежую. Для `external` — **не старше 28 дней**: журнал удалений помнит удаления 29 дней, и человек, удалённый после более старой копии, вернулся бы из неё (ANO-234). Копиям `local` меньше 7 дней — они все годятся.

## 2. Забрать и прочитать манифест

`local` — файлы на сервере открытым текстом, себе нужен только манифест:

```bash
STAMP=2026-10-19T003000Z
ssh <сервер> "cd /opt/selfin/deploy && docker compose exec -T backup cat /backups/$STAMP/manifest.txt" > manifest.txt
head -1 manifest.txt
```

`external` — все три файла себе и расшифровать манифест:

```bash
STAMP=2026-10-19T003000Z
for f in selfin.sql keycloak.sql manifest.txt; do
  ssh <сервер> "cd /opt/selfin/deploy && docker compose exec -T backup cat /backups/$STAMP/$f.age" > $f.age
done
age -d -i selfin-backup.key manifest.txt.age > manifest.txt
head -1 manifest.txt
```

Сервера нет — для `external` те же файлы из хранилища: `rclone copy <хранилище>:<корзина>/$STAMP .` на своём компьютере. У `local` вне сервера копий нет.

Первая строка — образ бэка при копии. Восстанавливай тем же: `SELFIN_TAG=sha-<коммит>` в `.env` сервера — иначе миграции поменяют таблицы, и проверка разойдётся с копией.

## 3. Новый сервер — сначала один запуск

Если сервер новый, сначала `docker compose up -d` и дождаться, пока поднимется бэк: миграции заведут роль приложения, а права на неё — в копии.

## 4. Восстановить обе базы

`local` — копия уже на сервере, восстановление прямо там:

```bash
ssh <сервер> 'cd /opt/selfin/deploy && docker compose stop backend keycloak'
ssh <сервер> "cd /opt/selfin/deploy && docker compose exec -T backup sh -c 'restore selfin < /backups/$STAMP/selfin.sql && restore keycloak < /backups/$STAMP/keycloak.sql'"
ssh <сервер> 'cd /opt/selfin/deploy && docker compose start keycloak backend'
```

`external` — расшифровка у тебя, на сервер идёт поток:

```bash
ssh <сервер> 'cd /opt/selfin/deploy && docker compose stop backend keycloak'
age -d -i selfin-backup.key selfin.sql.age   | ssh <сервер> 'cd /opt/selfin/deploy && docker compose exec -T backup restore selfin'
age -d -i selfin-backup.key keycloak.sql.age | ssh <сервер> 'cd /opt/selfin/deploy && docker compose exec -T backup restore keycloak'
ssh <сервер> 'cd /opt/selfin/deploy && docker compose start keycloak backend'
```

## 5. Проверить и повторить удаления

```bash
export SELFIN_PSQL="ssh <сервер> docker exec -i selfin-server-db-1 psql -U admin -d selfin"
export SELFIN_KC_ADMIN=http://127.0.0.1:8180/auth SELFIN_KC=https://<адрес>/auth SELFIN_API=https://<адрес>/api/v1
KC_ADMIN_PASSWORD=<пароль администратора Keycloak> node tools/restore-check.mjs manifest.txt ~/selfin-deletions.jsonl
```

Проверка сверяет таблицы с манифестом, ищет у каждого человека учётную запись реалма и повторяет удаления — из журнала копии и из твоего. «Итог: восстановление сходится» — готово.

## 6. Убрать за собой

```bash
rm manifest.txt                                                # local
rm selfin.sql.age keycloak.sql.age manifest.txt.age manifest.txt   # external
```

Копии `external` у себя — вторым экземпляром и не дольше 28 дней: тексты обещают удаление данных в течение 30 дней, а журнал удалений помнит удаления 29. Копий `local` у себя не держи вовсе: тексты обещают, что копии живут 7 дней и только на сервере.

## Выкатка не встала

Кнопка «Выкатить на сервер» красная (ANO-246, спека `docs/superpowers/specs/2026-10-27-release-button-design.md`). Причина — в журнале сервера:

```bash
ssh <сервер> 'journalctl -u selfin-release -n 50 --no-pager; cat /opt/selfin/deploy/.release-failed'
```

* **«вернулся на …»** — миграций между версиями не было, сервер сам вернулся на прежнюю; сайт работает. Не вставший коммит — в `.release-failed`, его сервер больше не пробует. Починка — новым коммитом в `main` и той же кнопкой.
* **«есть миграции — назад не иду»** — сервер остался на новой версии, и она не отвечает. Возврат — из копии, снятой перед обновлением: она самая свежая, первая строка её манифеста — образ бэка до обновления. Шаги 1–6 выше; версия — та, что в манифесте: `git -C /opt/selfin checkout --detach <коммит>`, `SELFIN_TAG=sha-<7 знаков>` в `.env`, `./update.sh`. Выкатку — остановить и дождаться прохода, как сказано вверху.
* **«что работало до неё, неизвестно»** — сервер не отвечал и до выкатки: смотреть `docker compose ps` и журналы контейнеров.
* Сбой был не в версии (диск, сеть) и коммит надо попробовать снова: `rm /opt/selfin/deploy/.release-failed`, потом `systemctl start selfin-release`.

## Журнал удалений у тебя — раз в сутки

Файл `~/selfin-deletions.jsonl` забывает удаления старше 29 дней — так обещают тексты. Сам он чистится только при удалении, повторе и проверке, поэтому раз в сутки его чистит задача Планировщика Windows. Завести её один раз — в PowerShell из клона репозитория. Если удалений ещё не было, сначала заведи пустой журнал: без файла задача падает.

```powershell
if (-not (Test-Path "$HOME\selfin-deletions.jsonl")) { New-Item -ItemType File "$HOME\selfin-deletions.jsonl" }
$action = New-ScheduledTaskAction -Execute 'node' -Argument 'tools\delete-person.mjs --prune' -WorkingDirectory (Get-Location)
Register-ScheduledTask -TaskName 'Selfin — журнал удалений' -Action $action -Trigger (New-ScheduledTaskTrigger -Daily -At 10:00) -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable)
```

`-StartWhenAvailable` — «запустить при пропуске»: компьютер был выключен — задача отработает при включении. Команде не нужны ни сервер, ни Keycloak.

Пропал журнал — задача падает с сообщением «журнала удалений нет». **Пустой на его место не заводи:** проверка восстановления ловит отсутствие журнала, а пустой приняла бы — и удалённые после копии вернулись бы. Пока сервер жив, те же удаления лежат в `ops.deletions` на сервере: журнал собирается по ним.

# Восстановление из копии

Шаг 5 плана спринта 6, спека `docs/superpowers/specs/2026-10-18-backups-design.md`. Ключ на сервер не попадает: расшифровка — на твоём компьютере, на сервер идёт поток. Команды — из корня репозитория на твоём компьютере; `<сервер>` — вход по SSH, на сервере клон лежит в `~/selfin`.

Нужно: секретный ключ копий (`selfin-backup.key`), твой журнал удалений (`~/selfin-deletions.jsonl`), туннель к консоли Keycloak (`ssh -L 8180:127.0.0.1:8180 <сервер>`). Без журнала проверка не сойдётся: удаление после копии есть только в нём. Если ты никого не удалял — создай пустой: `touch ~/selfin-deletions.jsonl`.

## 1. Какую копию

```bash
ssh <сервер> 'cd selfin/deploy && docker compose exec -T backup ls /backups'          # на сервере — 7 дней
ssh <сервер> 'cd selfin/deploy && docker compose exec -T backup sh -c "rclone lsf store:\$SELFIN_BACKUP_BUCKET"'   # в хранилище — 28
```

Каталог копии — время в UTC, например `2026-10-19T0030Z`. Бери самую свежую и **не старше 28 дней**: журнал удалений помнит удаления 29 дней, и человек, удалённый после более старой копии, вернулся бы из неё (ANO-234).

## 2. Забрать и прочитать манифест

```bash
STAMP=2026-10-19T0030Z
for f in selfin.sql keycloak.sql manifest.txt; do
  ssh <сервер> "cd selfin/deploy && docker compose exec -T backup cat /backups/$STAMP/$f.age" > $f.age
done
age -d -i selfin-backup.key manifest.txt.age > manifest.txt
head -1 manifest.txt
```

Сервера нет — те же файлы из хранилища: `rclone copy <хранилище>:<корзина>/$STAMP .` на своём компьютере.

Первая строка — образ бэка при копии. Восстанавливай тем же: `SELFIN_TAG=sha-<коммит>` в `.env` сервера — иначе миграции поменяют таблицы, и проверка разойдётся с копией.

## 3. Новый сервер — сначала один запуск

Если сервер новый, сначала `docker compose up -d` и дождаться, пока поднимется бэк: миграции заведут роль приложения, а права на неё — в копии.

## 4. Восстановить обе базы

```bash
ssh <сервер> 'cd selfin/deploy && docker compose stop backend keycloak'
age -d -i selfin-backup.key selfin.sql.age   | ssh <сервер> 'cd selfin/deploy && docker compose exec -T backup restore selfin'
age -d -i selfin-backup.key keycloak.sql.age | ssh <сервер> 'cd selfin/deploy && docker compose exec -T backup restore keycloak'
ssh <сервер> 'cd selfin/deploy && docker compose start keycloak backend'
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
rm selfin.sql.age keycloak.sql.age manifest.txt.age manifest.txt
```

Копии у себя — вторым экземпляром и не дольше 28 дней: тексты обещают удаление данных в течение 30 дней, а журнал удалений помнит удаления 29.

## Журнал удалений у тебя — раз в сутки

Файл `~/selfin-deletions.jsonl` забывает удаления старше 29 дней — так обещают тексты. Сам он чистится только при удалении, повторе и проверке, поэтому раз в сутки его чистит задача Планировщика Windows. Завести её один раз — в PowerShell из клона репозитория:

```powershell
$action = New-ScheduledTaskAction -Execute 'node' -Argument 'tools\delete-person.mjs --prune' -WorkingDirectory (Get-Location)
Register-ScheduledTask -TaskName 'Selfin — журнал удалений' -Action $action -Trigger (New-ScheduledTaskTrigger -Daily -At 10:00) -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable)
```

`-StartWhenAvailable` — «запустить при пропуске»: компьютер был выключен — задача отработает при включении. Команде не нужны ни сервер, ни Keycloak.

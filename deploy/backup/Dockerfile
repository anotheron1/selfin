# Контейнер копий (шаг 5 плана спринта 6): pg_dump обеих баз, шифрование age открытым ключом владельца, выгрузка
# rclone во внешнее хранилище, расписание — crond. Собирается на сервере из клона репозитория.
# Спека: docs/superpowers/specs/2026-10-18-backups-design.md
FROM postgres:15-alpine
RUN apk add --no-cache age rclone curl
COPY backup restore /usr/local/bin/
COPY manifest.sql /usr/local/share/selfin/manifest.sql
COPY crontab /etc/crontabs/root
RUN chmod +x /usr/local/bin/backup /usr/local/bin/restore
CMD ["crond", "-f", "-l", "6"]

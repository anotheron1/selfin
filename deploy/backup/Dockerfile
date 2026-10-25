# Контейнер копий (шаг 5 плана спринта 6): pg_dump обеих баз, расписание — crond. В режиме external — ещё шифрование
# age открытым ключом владельца и выгрузка rclone во внешнее хранилище (ANO-239); в local они не вызываются (ANO-243).
# Собирается на сервере из клона репозитория.
# Спеки: docs/superpowers/specs/2026-10-18-backups-design.md, 2026-10-25-backups-local-mode-design.md
FROM postgres:15-alpine
RUN apk add --no-cache age rclone curl
# Хранилище rclone настраивается переменными окружения; файла настроек нет и не нужно.
ENV RCLONE_CONFIG=/dev/null
COPY backup restore /usr/local/bin/
COPY manifest.sql /usr/local/share/selfin/manifest.sql
COPY crontab /etc/crontabs/root
RUN chmod +x /usr/local/bin/backup /usr/local/bin/restore
CMD ["crond", "-f", "-l", "6"]

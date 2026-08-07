#!/usr/bin/env bash
# Резервная копия базы. Запускается на сервере, от пользователя familytodo.
#
# sqlite3 ".backup", а не cp: при включённом WAL часть свежих страниц лежит в
# файле -wal, и простое копирование даёт снимок, который может не открыться —
# причём молча, до первой попытки восстановиться. Команда .backup проходит через
# API, дожидается согласованного состояния и не мешает работающему боту.
#
# Останавливать службу не нужно: писатель в SQLite один, .backup встаёт в ту же
# очередь.
set -euo pipefail

DB="${DB_PATH:-/var/lib/family-todo/family-todo.db}"
DEST="${BACKUP_DIR:-/var/lib/family-todo/backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"

if [ ! -f "$DB" ]; then
    echo "базы нет: $DB" >&2
    exit 1
fi

mkdir -p "$DEST"
snapshot="$DEST/family-todo-$(date -u +%Y%m%dT%H%M%SZ).db"

sqlite3 "$DB" ".backup '$snapshot'"

# Проверяем снимок сразу: копия, которая не открывается, обнаруженная в момент
# восстановления, — это отсутствующая копия.
if [ "$(sqlite3 "$snapshot" 'pragma integrity_check;')" != "ok" ]; then
    echo "снимок повреждён: $snapshot" >&2
    rm -f "$snapshot" "$snapshot-wal" "$snapshot-shm"
    exit 1
fi

# Режим WAL записан в заголовке файла, поэтому проверка выше открыла снимок в WAL
# и создала рядом пустые -wal и -shm. Под маску очистки они не попадают и копились
# бы бесконечно — по паре на каждый запуск.
rm -f "$snapshot-wal" "$snapshot-shm"

gzip -f "$snapshot"
find "$DEST" -name 'family-todo-*.db.gz' -mtime "+$KEEP_DAYS" -delete

echo "снимок готов: $snapshot.gz"

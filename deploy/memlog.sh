#!/usr/bin/env bash
# Одна строка в сутки о состоянии службы. Запускается на сервере таймером.
#
# Нужен не мониторинг, а история: снимок памяти отвечает «сколько сейчас», но
# не отвечает «растёт ли». Отличить медленную утечку от обычного прогрева можно
# только по ряду точек, а ставить ради этого систему сбора метрик на машину,
# где живёт чужой VPN, несоразмерно.
#
# Формат — TSV, чтобы читалось глазами и разбиралось чем угодно:
#   дата  память_сейчас  пик  RSS_кб  рестартов  аптайм_процесса
set -euo pipefail

UNIT="${UNIT:-family-todo}"
LOG="${MEMLOG:-/var/lib/family-todo/memory.log}"

value() {
    systemctl show "$UNIT" -p "$1" --value
}

pid=$(value MainPID)
if [ -z "$pid" ] || [ "$pid" = "0" ]; then
    printf '%s\tслужба не запущена\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$LOG"
    exit 0
fi

# RSS и аптайм берём у процесса: systemd знает про cgroup целиком, а нам нужна JVM
read -r rss etime < <(ps -o rss=,etime= -p "$pid" | tr -s ' ' | sed 's/^ //')

printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(value MemoryCurrent)" \
    "$(value MemoryPeak)" \
    "$rss" \
    "$(value NRestarts)" \
    "$etime" \
    >> "$LOG"

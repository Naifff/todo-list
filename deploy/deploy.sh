#!/usr/bin/env bash
# Сборка локально, на сервер уезжает только jar.
#
# На сервере нет ни Gradle, ни JDK — только JRE. Это не экономия: сборка на
# машине с чужим VPN означала бы компилятор, кеши и сеть Gradle рядом с тем,
# что нельзя ронять.
#
# Ничего, кроме своей службы, скрипт не трогает: ни iptables, ни sysctl, ни
# сетевых интерфейсов, ни пакетов. Docker на этой машине не ставится никогда.
#
#   ./deploy/deploy.sh root@your-server
set -euo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "нужен адрес: $0 user@host" >&2
    exit 1
fi

cd "$(dirname "$0")/.."

echo "== сборка"
./gradlew clean check bootJar

JAR="build/libs/family-todo-0.0.1-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "нет артефакта: $JAR" >&2; exit 1; }

echo "== выгрузка $(du -h "$JAR" | cut -f1)"
# сначала во временный файл: если связь оборвётся на середине, служба
# перезапустится с целым старым jar, а не с обрезанным новым
scp "$JAR" "$TARGET:/tmp/family-todo.jar.new"

echo "== установка и перезапуск"
ssh "$TARGET" bash -euo pipefail <<'REMOTE'
install -o familytodo -g familytodo -m 644 /tmp/family-todo.jar.new /opt/family-todo/app.jar
rm -f /tmp/family-todo.jar.new
systemctl restart family-todo
REMOTE

echo "== проверка"
# Признак живости — строка в журнале: порт мы не открываем, health-эндпоинта нет.
sleep 5
ssh "$TARGET" 'systemctl is-active family-todo && journalctl -u family-todo -n 20 --no-pager'

echo
echo "готово. В журнале должна быть строка 'long polling started'."

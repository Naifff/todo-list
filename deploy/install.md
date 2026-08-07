# Установка на VPS

Однократная подготовка сервера. Дальше выкладка — `./deploy/deploy.sh user@host`.

## ⚠️ Чего на этой машине делать нельзя

На сервере работает **чужой VPN**, и он важнее нашего бота.

- **не ставить Docker.** Установка правит политику цепочки `FORWARD` в iptables,
  на которой держится маршрутизация VPN. Это единственная причина, по которой в
  проекте нет ни Docker, ни Testcontainers, ни PostgreSQL
- **не трогать** `iptables`, `nftables`, `sysctl`, сетевые интерфейсы, маршруты
- **не перезагружать** хост без предупреждения владельца VPN
- не менять глобальные настройки systemd и journald сверх квоты из этого файла

Бот не открывает ни одного порта: long polling — только исходящие соединения.
Ничего проксировать, пробрасывать и открывать в файрволе не нужно.

## 1. Java

Нужна только JRE 25 — сборка идёт на рабочей машине. СУБД ставить не надо,
база это файл.

```bash
apt-get update && apt-get install -y openjdk-25-jre-headless sqlite3
java -version
```

`sqlite3` нужен для `backup.sh` и для разбора инцидентов на месте.

Если пакета `openjdk-25-jre-headless` в репозитории нет — поставить любую JRE 25
из tar.gz в `/opt/jdk-25` и поправить путь в `ExecStart` юнита. Версию ниже 25
брать нельзя: сборка идёт под toolchain 25.

## 2. Пользователь и каталоги

Служба работает **не от root**.

```bash
useradd --system --home-dir /var/lib/family-todo --create-home --shell /usr/sbin/nologin familytodo

install -d -o familytodo -g familytodo -m 750 /var/lib/family-todo
install -d -o root -g root -m 755 /opt/family-todo
install -d -o root -g root -m 750 /etc/family-todo
```

`/var/lib/family-todo` должен быть доступен на запись **целиком**, а не только
файл базы: WAL создаёт рядом `family-todo.db-wal` и `family-todo.db-shm`.

## 3. Секреты

```bash
cat > /etc/family-todo/env <<'EOF'
BOT_TOKEN=<токен от BotFather>
BOT_USERNAME=FamilyTODO_bot
DB_PATH=/var/lib/family-todo/family-todo.db
EOF

chown root:familytodo /etc/family-todo/env
chmod 640 /etc/family-todo/env
```

Токен не передаётся аргументом командной строки: аргументы процесса видны в `ps`
любому пользователю машины. Если токен утёк — **отзывать через BotFather**,
удаления файла или переписывания истории git недостаточно.

Пустые значения приложение не примет: контекст не соберётся, и в журнале будет
`BOT_TOKEN is not set`. Это сделано намеренно — иначе бот стартует, получает от
Telegram 404 и выглядит как перебои сети.

## 4. Юнит

```bash
cp deploy/family-todo.service /etc/systemd/system/
systemctl daemon-reload
systemd-analyze verify family-todo.service
systemctl enable family-todo
```

`MemoryMax=512M` и `CPUQuota=50%` — жёсткие лимиты, а не пожелания: превышение
памяти означает OOM-kill. Это защита соседнего VPN, снимать их нельзя.

Первый запуск — после выкладки jar (шаг 6).

## 5. Квота журнала

Без ограничения журнал растёт до 10% раздела. На маленьком VPS это заметно.

```bash
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/family-todo.conf <<'EOF'
[Journal]
SystemMaxUse=200M
MaxRetentionSec=1month
EOF

systemctl restart systemd-journald
```

Это единственная общесистемная правка во всей установке.

## 6. Выкладка

С рабочей машины:

```bash
./deploy/deploy.sh root@your-server
```

Скрипт собирает `check` + `bootJar`, копирует **только jar** и перезапускает
службу. Признак успеха — строка `long polling started` в журнале:
health-эндпоинта нет, потому что порт не открывается.

```bash
journalctl -u family-todo -f
```

## 7. Резервные копии

```bash
cp deploy/backup.sh /usr/local/bin/family-todo-backup
chmod 755 /usr/local/bin/family-todo-backup

cat > /etc/systemd/system/family-todo-backup.service <<'EOF'
[Service]
Type=oneshot
User=familytodo
ExecStart=/usr/local/bin/family-todo-backup
EOF

cat > /etc/systemd/system/family-todo-backup.timer <<'EOF'
[Timer]
OnCalendar=daily
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now family-todo-backup.timer
systemctl start family-todo-backup.service   # проверить сразу, а не через сутки
```

Копия делается через `sqlite3 .backup`, а не `cp`: при включённом WAL часть
свежих страниц лежит в отдельном файле, и обычная копия может не открыться —
молча, до первой попытки восстановиться. Скрипт сразу проверяет снимок
`pragma integrity_check` и удаляет негодный. Хранение — 7 дней.

Восстановление:

```bash
systemctl stop family-todo
gunzip -c /var/lib/family-todo/backups/family-todo-<метка>.db.gz \
    > /var/lib/family-todo/family-todo.db
chown familytodo:familytodo /var/lib/family-todo/family-todo.db
rm -f /var/lib/family-todo/family-todo.db-wal /var/lib/family-todo/family-todo.db-shm
systemctl start family-todo
```

## Проверка после установки

```bash
systemctl is-active family-todo
journalctl -u family-todo -n 50 --no-pager | grep -E 'Flyway|long polling started'
systemctl show family-todo -p MemoryMax -p CPUQuotaPerSecUSec
ss -lntp | grep java || echo 'портов не слушает — так и должно быть'
```

Строки Flyway обязаны быть в журнале. Однажды миграции не выполнялись вовсе, и
заметить это удалось только по живому запуску: тесты были зелёными.

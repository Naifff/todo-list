-- Схема под SQLite.
--
-- Моменты времени хранятся целым числом миллисекунд от эпохи, а не текстом
-- ISO-8601. Причина измеренная, не вкусовая: Instant.toString() опускает
-- доли секунды, когда они нулевые, и '2026-08-07T16:00:00.123Z' оказывается
-- лексикографически МЕНЬШЕ '2026-08-07T16:00:00Z' — точка (0x2E) меньше 'Z'
-- (0x5A). На таком хранении сравнение due_at <= :now в запросе напоминаний
-- молча врёт. Целое сравнивается правильно всегда.
--
-- Календарные значения (дата, время суток, таймзона) — наоборот текстом:
-- это не моменты, а обозначения, и они читаемы глазом при разборе инцидента.

create table family (
    id               integer primary key,
    name             text    not null,
    timezone         text    not null,  -- ZoneId, например Europe/Moscow
    digest_time      text    not null,  -- HH:MM по календарю семьи
    last_digest_date text    not null,  -- YYYY-MM-DD, тоже локальная
    created_at       integer not null
);

create table member (
    id               integer primary key,
    family_id        integer not null references family (id),
    telegram_user_id integer not null unique,  -- один человек = одна семья
    private_chat_id  integer not null,         -- куда бот пишет напоминания
    display_name     text    not null,
    role             text    not null check (role in ('PARENT', 'CHILD')),
    status           text    not null check (status in ('ACTIVE', 'REMOVED')),
    blocked_bot      integer not null default 0 check (blocked_bot in (0, 1)),
    created_at       integer not null
);

create table invite (
    id         integer primary key,
    family_id  integer not null references family (id),
    code       text    not null unique,
    role       text    not null check (role in ('PARENT', 'CHILD')),
    created_by integer not null references member (id),
    expires_at integer not null,
    used_by    integer references member (id),
    used_at    integer
);

-- family_id продублирован намеренно, хотя выводится через assignee_id:
-- он позволяет сделать границу семьи обязательным параметром каждого
-- запроса, а не условием, которое можно забыть дописать.
create table task (
    id             integer primary key,
    family_id      integer not null references family (id),
    title          text    not null check (length(title) between 1 and 200),
    creator_id     integer not null references member (id),
    assignee_id    integer not null references member (id),
    status         text    not null check (status in ('OPEN', 'DONE', 'DECLINED')),
    due_at         integer,
    decline_reason text,
    created_at     integer not null,
    closed_at      integer,
    reminded_at    integer
);

create index idx_task_family_status on task (family_id, status);
create index idx_task_assignee_status on task (assignee_id, status);
create index idx_task_due on task (due_at) where status = 'OPEN' and due_at is not null;

-- Выдача идентификаторов.
--
-- Не autoincrement потому, что доменные сущности неизменяемы по id: он нужен
-- до того, как объект собран, а не после того, как строка вставлена. Иначе
-- Task.create() пришлось бы звать с фиктивным id и подменять его после
-- сохранения — то есть заводить состояние «объект есть, но ещё неправильный».
create table id_sequence (
    name       text    primary key,
    next_value integer not null
);

insert into id_sequence (name, next_value)
values ('family', 0),
       ('member', 0),
       ('invite', 0),
       ('task', 0);

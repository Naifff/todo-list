-- Повторяющиеся дела.
--
-- Вхождения материализуются обычными строками task, а не вычисляются на лету:
-- у каждого своя судьба (одно сделано, другое отклонено с причиной, третьему
-- сменили исполнителя), и виртуальному вхождению статус приписать некуда.

create table task_series (
    id            integer primary key,
    family_id     integer not null references family (id),
    title         text    not null,
    creator_id    integer not null references member (id),
    assignee_id   integer not null references member (id),
    assignee_role text    not null,
    -- номера дней ISO через запятую, по возрастанию: 1,2,3,4,5
    recurrence    text    not null,
    start_time    text    not null,             -- HH:MM по календарю семьи
    duration_min  integer,                      -- null: у дела только срок, без интервала
    location      text,
    starts_on     text    not null,             -- YYYY-MM-DD
    ends_on       text,                         -- null: без даты окончания
    -- остановка, а не удаление строки: закрытые вхождения ссылаются сюда,
    -- и история семьи не должна исчезать вместе с правилом
    stopped_at    integer,
    created_at    integer not null
);

alter table task add column series_id integer references task_series (id);

-- Локальная дата вхождения по календарю семьи на момент материализации.
-- Хранится, а не вычисляется из starts_at: смена таймзоны семьи не должна
-- задним числом переносить уже созданные дела в соседний день.
alter table task add column occurrence_on text;

-- Идемпотентность джобы держится этим индексом, а не аккуратностью кода:
-- повторный прогон получит нарушение ограничения, а не второй экземпляр дела.
create unique index idx_task_occurrence
    on task (series_id, occurrence_on)
    where series_id is not null;

create index idx_series_active
    on task_series (family_id)
    where stopped_at is null;

-- Идентификаторы выдаёт таблица, а не autoincrement: доменные сущности
-- неизменяемы по id, он нужен до сборки объекта, а не после вставки строки.
insert into id_sequence (name, next_value) values ('task_series', 0);

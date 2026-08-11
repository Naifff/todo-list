-- Несколько исполнителей у одного дела.
--
-- Повод: запись ребёнка к врачу нужна обоим родителям. Заведённая дважды, она даёт
-- два напоминания, два блока в календаре и «сделано» у одного при висящем деле
-- у другого.
--
-- Отказ перестаёт быть свойством задачи и становится свойством назначения. Причина
-- тому не техническая: «сделано» — факт о мире (к врачу сходили, и второму держать
-- это в голове больше не нужно), а отказ — ответ на просьбу, и отвечает на неё
-- каждый адресат за себя. Значит причин у одного дела теперь столько же, сколько
-- отказавшихся, и колонка task.decline_reason уходит: две правды об одном и том же
-- расходятся всегда, вопрос только когда.
--
-- ⚠️ ПОРЯДОК ДЕЙСТВИЙ ЗДЕСЬ НЕ ПРОИЗВОЛЕН. При foreign_keys=true (а он у нас в URL)
-- DROP TABLE выполняет неявный DELETE FROM. Если бы task_assignee уже ссылалась на
-- task, снос старой таблицы падал бы по внешнему ключу. Отключить прагму внутри
-- миграции нельзя: SQLite молча игнорирует смену foreign_keys внутри транзакции, а
-- Flyway выполняет миграцию именно в ней — то есть «отключение» выглядело бы
-- сработавшим и ничего не делало. Поэтому исполнители сначала снимаются во временную
-- таблицу БЕЗ внешних ключей, и только после перестройки task заводится настоящая.

-- 1. Снять исполнителей и отказы, пока старые колонки ещё существуют.
create table task_assignee_carry (
    task_id        integer primary key,
    member_id      integer not null,
    declined_at    integer,
    decline_reason text
);

insert into task_assignee_carry (task_id, member_id, declined_at, decline_reason)
select id,
       assignee_id,
       case when status = 'DECLINED' then closed_at end,
       case when status = 'DECLINED' then decline_reason end
from task;

-- 2. Перестроить task без assignee_id и decline_reason.
--
-- Перестройкой, а не ALTER TABLE DROP COLUMN: последний появился только в SQLite
-- 3.35 и отказывается работать с колонками, упомянутыми в индексах. Пересоздание
-- ведёт себя одинаково на любой версии.
create table task_new (
    id             integer primary key,
    family_id      integer not null references family (id),
    title          text    not null check (length(title) between 1 and 200),
    creator_id     integer not null references member (id),
    status         text    not null check (status in ('OPEN', 'DONE', 'DECLINED')),
    due_at         integer,
    created_at     integer not null,
    closed_at      integer,
    reminded_at    integer,
    starts_at      integer,
    ends_at        integer,
    location       text,
    series_id      integer references task_series (id),
    occurrence_on  text
);

insert into task_new (id, family_id, title, creator_id, status, due_at, created_at,
                      closed_at, reminded_at, starts_at, ends_at, location,
                      series_id, occurrence_on)
select id, family_id, title, creator_id, status, due_at, created_at,
       closed_at, reminded_at, starts_at, ends_at, location,
       series_id, occurrence_on
from task;

drop table task;
alter table task_new rename to task;

-- Индексы уехали вместе со старой таблицей и восстанавливаются здесь все, кроме
-- idx_task_assignee_status: колонки, по которой он строился, больше нет.
create index idx_task_family_status on task (family_id, status);
create index idx_task_due on task (due_at) where status = 'OPEN' and due_at is not null;
create index idx_task_family_starts on task (family_id, starts_at) where starts_at is not null;
create unique index idx_task_occurrence on task (series_id, occurrence_on) where series_id is not null;

-- 3. Настоящая таблица назначений.
--
-- on delete cascade потому, что удаление дела у нас — стирание строки, а не статус
-- DELETED: назначения обязаны уйти вместе с ним, иначе останутся сироты, на которые
-- никто не смотрит и которые ничего не значат.
create table task_assignee (
    task_id        integer not null references task (id) on delete cascade,
    member_id      integer not null references member (id),
    declined_at    integer,
    decline_reason text,
    primary key (task_id, member_id)
);

insert into task_assignee (task_id, member_id, declined_at, decline_reason)
select task_id, member_id, declined_at, decline_reason from task_assignee_carry;

drop table task_assignee_carry;

-- Взамен удалённого idx_task_assignee_status: выборка «что поручено мне» теперь
-- начинается отсюда, а не со сканирования task.
create index idx_task_assignee_member on task_assignee (member_id);

-- ⚠️ Строка в id_sequence этой таблице НЕ нужна, хотя правило в CLAUDE.md требует её
-- для каждой новой. Здесь исключение и оно осознанное: у назначения нет собственного
-- идентификатора, ключ составной (task_id, member_id). Отмечено явно, чтобы правило
-- не прочитали буквально и не завели строку, которая никогда не пригодится.

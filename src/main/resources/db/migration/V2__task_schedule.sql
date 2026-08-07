-- Интервал и место.
--
-- due_at остаётся сроком — «сделать к». starts_at/ends_at это занятое время —
-- «делать с и по». Разные вещи: «вынести мусор к 19:00» и «отвезти детей
-- 08:00–08:40». Слить их значило бы сломать напоминания, которые работают
-- по сроку, а не по началу.
--
-- Все три необязательные: у большинства семейных просьб нет ни интервала,
-- ни места.

alter table task add column starts_at integer;
alter table task add column ends_at integer;
alter table task add column location text;

-- Индекс под выборку расписания на горизонт (задача 27): даты в списке идут
-- по началу, а у дел без интервала — по сроку.
create index idx_task_family_starts on task (family_id, starts_at)
    where starts_at is not null;

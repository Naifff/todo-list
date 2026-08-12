-- Несколько исполнителей у повторяющегося дела.
--
-- В V7 то же самое сделали для обычных задач, но серии тогда осознанно оставили с одним
-- исполнителем: «повторяющееся дело на двоих не просили». Попросили — тренировка, на
-- которую возят по очереди, и семейный ужин это ровно те дела, что и повторяются, и
-- касаются всех.
--
-- ⚠️ ЗДЕСЬ НЕЛЬЗЯ ПЕРЕСТРАИВАТЬ ТАБЛИЦУ, в отличие от V7. На task_series ССЫЛАЮТСЯ строки
-- task: каждое материализованное вхождение хранит task.series_id. При foreign_keys=true
-- DROP TABLE родителя выполняет неявный DELETE FROM и падает, пока на него смотрит хоть
-- одно вхождение. В V7 всё было наоборот — там перестройка была единственным способом.
--
-- Поэтому колонки убираются ALTER TABLE ... DROP COLUMN. Он допустим ровно потому, что
-- assignee_id и assignee_role не участвуют ни в одном индексе: idx_series_active построен
-- по family_id. В V7 этот путь был закрыт — там колонка входила в idx_task_assignee_status.

create table task_series_assignee (
    series_id integer not null references task_series (id) on delete cascade,
    member_id integer not null references member (id),
    primary key (series_id, member_id)
);

insert into task_series_assignee (series_id, member_id)
select id, assignee_id from task_series;

-- assignee_role был снимком роли на момент создания. Он больше не нужен: роль приезжает
-- джойном с member, как и у task_assignee, и копия в серии протухала бы после повышения
-- ребёнка до родителя.
alter table task_series drop column assignee_role;
alter table task_series drop column assignee_id;

create index idx_series_assignee_member on task_series_assignee (member_id);

-- ⚠️ Строка в id_sequence не нужна: ключ составной, своего идентификатора у назначения нет.
-- То же исключение и по той же причине, что у task_assignee в V7.

-- Списки покупок.
--
-- Позиция это не задача: у неё нет исполнителя, срока и отказа. Заведи мы её
-- строкой в task, сорок «молоко» немедленно засорили бы /my, дайджест и
-- календарь — три экрана, ради которых всё и делалось.
--
-- Два списка — признак в колонке, а не две таблицы: правила, права и экраны у
-- них совпадают полностью, различается только куда позиция попадёт.

create table shopping_item (
    id         integer primary key,
    family_id  integer not null references family (id),
    list_kind  text    not null,             -- FOOD | HOUSEHOLD
    title      text    not null,
    added_by   integer not null references member (id),
    added_at   integer not null,
    -- «куплено» это факт с автором и временем, а не флаг: обе колонки
    -- заполняются вместе либо обе пусты
    bought_by  integer references member (id),
    bought_at  integer
);

-- Под единственную выборку экрана: позиции одного списка одной семьи,
-- некупленные выше купленных.
create index idx_shopping_family
    on shopping_item (family_id, list_kind, bought_at);

-- ⚠️ Новая таблица требует своей строки здесь. На V4 её забыли, и проявилось это
-- не при старте, а при первой живой попытке что-нибудь создать.
insert into id_sequence (name, next_value) values ('shopping_item', 0);

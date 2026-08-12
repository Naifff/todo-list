package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class MigrationIT extends AbstractSqliteIT {

    @Test
    void createsEveryTable() {
        List<String> tables =
                jdbc.sql("select name from sqlite_master where type = 'table' order by name")
                        .query(String.class)
                        .list();

        assertThat(tables)
                .contains(
                        "family",
                        "member",
                        "invite",
                        "task",
                        "task_assignee",
                        "task_series",
                        "task_series_assignee",
                        "id_sequence");
    }

    @Test
    void createsEveryIndex() {
        List<String> indexes =
                jdbc.sql("select name from sqlite_master where type = 'index' and name like 'idx_%'")
                        .query(String.class)
                        .list();

        assertThat(indexes)
                .containsExactlyInAnyOrder(
                        "idx_task_family_status",
                        "idx_task_assignee_member",
                        "idx_task_due",
                        "idx_task_family_starts",
                        "idx_task_occurrence",
                        "idx_series_active",
                        "idx_series_assignee_member",
                        "idx_shopping_family");
    }

    /** ⚠️ Цвет раздаётся миграцией всем, кто уже в семье: без него календарь рисовать нечем. */
    @Test
    void everyExistingMemberGetsAColour() {
        jdbc.sql(
                        """
                        insert into family (id, name, timezone, digest_time, last_digest_date, created_at)
                        values (1, 'Ивановы', 'Europe/Moscow', '08:00', '2026-08-08', 0)
                        """)
                .update();
        jdbc.sql(
                        """
                        insert into member
                          (id, family_id, telegram_user_id, private_chat_id,
                           display_name, role, status, color, created_at)
                        values (1, 1, 100, 100, 'Мама', 'PARENT', 'ACTIVE', 'BLUE', 0)
                        """)
                .update();

        assertThat(jdbc.sql("select count(*) from member where color is null").query(Integer.class).single())
                .isZero();
    }

    /** Частичный индекс — иначе он растёт вместе с архивом закрытых задач, а нужен только по открытым. */
    @Test
    void dueIndexIsPartial() {
        String sql =
                jdbc.sql("select sql from sqlite_master where name = 'idx_task_due'")
                        .query(String.class)
                        .single();

        assertThat(sql).contains("where status = 'OPEN'").contains("due_at is not null");
    }

    /** V2 добавила интервал и место — все три необязательные. */
    @Test
    void taskCarriesScheduleColumns() {
        List<String> columns =
                jdbc.sql("select name from pragma_table_info('task')").query(String.class).list();

        assertThat(columns).contains("starts_at", "ends_at", "location");
    }

    @Test
    void seedsIdSequences() {
        List<String> names =
                jdbc.sql("select name from id_sequence order by name").query(String.class).list();

        assertThat(names)
                .containsExactly(
                        "family", "invite", "member", "shopping_item", "task", "task_series");
    }

    /**
     * Главная проверка этого класса: в SQLite внешние ключи по умолчанию выключены. Если прагма не
     * доехала из URL, тест зелёный не будет — а без него схема выглядела бы корректной, ничего не
     * проверяя.
     */
    @Test
    void enforcesForeignKeys() {
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                                                insert into member
                                                  (id, family_id, telegram_user_id, private_chat_id,
                                                   display_name, role, status, created_at)
                                                values (1, 999, 1, 1, 'Никто', 'PARENT', 'ACTIVE', 0)
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueTelegramUserId() {
        insertFamily();
        insertMember(1, 100000001L);

        assertThatThrownBy(() -> insertMember(2, 100000001L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownStatusValue() {
        insertFamily();
        insertMember(1, 100000001L);

        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                                                insert into task
                                                  (id, family_id, title, creator_id,
                                                   status, created_at)
                                                values (1, 1, 'Вынести мусор', 1, 'ЧТО-ТО', 0)
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTitleLongerThanTwoHundred() {
        insertFamily();
        insertMember(1, 100000001L);

        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                                                insert into task
                                                  (id, family_id, title, creator_id,
                                                   status, created_at)
                                                values (1, 1, ?, 1, 'OPEN', 0)
                                                """)
                                        .param("я".repeat(201))
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Момент времени — целое: сравнение должно работать и на долях секунды. */
    @Test
    void storesInstantsAsComparableIntegers() {
        insertFamily();
        insertMember(1, 100000001L);
        insertTask(1, 1786060800000L);
        insertTask(2, 1786060800123L);

        List<Long> ordered =
                jdbc.sql("select id from task order by due_at").query(Long.class).list();

        assertThat(ordered).containsExactly(1L, 2L);
    }

    /**
     * Удаление дела у нас — стирание строки, а не статус {@code DELETED}. Назначения обязаны уйти
     * вместе с ним: осиротевшая строка ни на что не влияет и потому не будет замечена никогда.
     *
     * <p>Держит это {@code on delete cascade}, а он в SQLite работает только при включённой прагме
     * внешних ключей — то есть тест заодно проверяет, что прагма доехала и до этого пути.
     */
    @Test
    void deletingATaskTakesItsAssignmentsWithIt() {
        insertFamily();
        insertMember(1, 100000001L);
        insertTask(1, 1786060800000L);
        jdbc.sql("insert into task_assignee (task_id, member_id) values (1, 1)").update();

        jdbc.sql("delete from task where id = 1").update();

        assertThat(jdbc.sql("select count(*) from task_assignee").query(Integer.class).single())
                .isZero();
    }

    private void insertFamily() {
        jdbc.sql(
                        """
                        insert into family (id, name, timezone, digest_time, last_digest_date, created_at)
                        values (1, 'Ивановы', 'Europe/Moscow', '08:00', '2026-08-07', 0)
                        """)
                .update();
    }

    private void insertMember(long id, long telegramUserId) {
        jdbc.sql(
                        """
                        insert into member
                          (id, family_id, telegram_user_id, private_chat_id,
                           display_name, role, status, created_at)
                        values (?, 1, ?, ?, 'Мама', 'PARENT', 'ACTIVE', 0)
                        """)
                .params(id, telegramUserId, telegramUserId)
                .update();
    }

    private void insertTask(long id, long dueAt) {
        jdbc.sql(
                        """
                        insert into task
                          (id, family_id, title, creator_id, status, due_at, created_at)
                        values (?, 1, 'Вынести мусор', 1, 'OPEN', ?, 0)
                        """)
                .params(id, dueAt)
                .update();
    }
}

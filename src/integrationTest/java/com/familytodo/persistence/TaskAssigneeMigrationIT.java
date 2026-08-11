package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.config.PersistenceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Перенос исполнителей из колонки {@code task.assignee_id} в таблицу {@code task_assignee}.
 *
 * <p>⚠️ Этот класс намеренно <b>не</b> наследует {@link AbstractSqliteIT}. Там база создаётся с нуля
 * и все миграции прогоняются подряд, поэтому к моменту {@code V7} в {@code task} не бывает ни одной
 * строки — перенос данных выполняется вхолостую, и проверять в нём нечего. Единственный способ
 * проверить его по-настоящему: домигрировать до предыдущей версии, положить данные руками и
 * догнать до последней.
 *
 * <p>Это ровно тот класс ошибок, которым посвящён {@code MigrationIT}: схема выглядит правильной, а
 * гарантии за ней нет.
 */
class TaskAssigneeMigrationIT {

    private Path directory;
    private DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void migrateToPreviousVersion() throws IOException {
        directory = Files.createTempDirectory("family-todo-migration-it");
        Path file = directory.resolve("family-todo.db");

        dataSource = new DriverManagerDataSource(AbstractSqliteIT.jdbcUrl(file));
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
        jdbc = PersistenceConfig.sqliteJdbcClient(dataSource);
    }

    @AfterEach
    void dropDatabase() throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(TaskAssigneeMigrationIT::deleteQuietly);
        }
    }

    @Test
    void anOpenTaskKeepsItsAssigneeAsTheOnlyAssignment() {
        givenFamilyWithTwoMembers();
        insertOpenTask(1, "Отвезти к врачу", 10);

        migrateToLatest();

        assertThat(assigneesOf(1)).containsExactly(10L);
        assertThat(jdbc.sql("select count(*) from task_assignee where declined_at is not null")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    /** Отказ был свойством задачи, а становится свойством назначения — вместе с причиной. */
    @Test
    void aDeclinedTaskCarriesItsReasonOntoTheAssignment() {
        givenFamilyWithTwoMembers();
        insertDeclinedTask(2, "Забрать посылку", 11, "я на работе до восьми", 1786060800000L);

        migrateToLatest();

        var assignment =
                jdbc.sql(
                                """
                                select member_id, declined_at, decline_reason
                                from task_assignee where task_id = 2
                                """)
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getLong("member_id"),
                                            rs.getLong("declined_at"),
                                            rs.getString("decline_reason")
                                        })
                        .single();

        assertThat(assignment[0]).isEqualTo(11L);
        assertThat(assignment[1]).isEqualTo(1786060800000L);
        assertThat(assignment[2]).isEqualTo("я на работе до восьми");
    }

    /**
     * Перестройка таблицы — самая опасная часть миграции: {@code task} пересоздаётся целиком, и
     * потерять при этом можно что угодно, от заголовка до привязки к серии.
     */
    @Test
    void rebuildingTheTaskTableLosesNothingElse() {
        givenFamilyWithTwoMembers();
        jdbc.sql(
                        """
                        insert into task
                          (id, family_id, title, creator_id, assignee_id, status, due_at,
                           created_at, closed_at, reminded_at, starts_at, ends_at, location,
                           series_id, occurrence_on)
                        values (3, 1, 'Тренировка', 10, 11, 'OPEN', 1786060800000, 5, null, 7,
                                1786057200000, 1786060800000, 'школа', null, '2026-08-11')
                        """)
                .update();

        migrateToLatest();

        var row =
                jdbc.sql(
                                """
                                select title, creator_id, status, due_at, created_at, reminded_at,
                                       starts_at, ends_at, location, occurrence_on
                                from task where id = 3
                                """)
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getString("title"),
                                            rs.getLong("creator_id"),
                                            rs.getString("status"),
                                            rs.getLong("due_at"),
                                            rs.getLong("created_at"),
                                            rs.getLong("reminded_at"),
                                            rs.getLong("starts_at"),
                                            rs.getLong("ends_at"),
                                            rs.getString("location"),
                                            rs.getString("occurrence_on")
                                        })
                        .single();

        assertThat(row)
                .containsExactly(
                        "Тренировка",
                        10L,
                        "OPEN",
                        1786060800000L,
                        5L,
                        7L,
                        1786057200000L,
                        1786060800000L,
                        "школа",
                        "2026-08-11");
    }

    /** Колонки уходят целиком: две правды об одном и том же расходятся всегда. */
    @Test
    void theTaskTableNoLongerCarriesAssigneeOrDeclineReason() {
        migrateToLatest();

        List<String> columns =
                jdbc.sql("select name from pragma_table_info('task')").query(String.class).list();

        assertThat(columns).doesNotContain("assignee_id", "decline_reason");
    }

    /**
     * Внешние ключи должны пережить перестройку. ⚠️ Проверка не формальная: {@code task}
     * пересоздаётся, и потерянное {@code references} не проявится ничем до первой чужой строки.
     */
    @Test
    void anAssignmentMayNotPointAtAMemberThatDoesNotExist() {
        givenFamilyWithTwoMembers();
        insertOpenTask(1, "Отвезти к врачу", 10);

        migrateToLatest();

        assertThat(
                        jdbc.sql("select count(*) from pragma_foreign_key_list('task_assignee')")
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    private List<Long> assigneesOf(long taskId) {
        return jdbc.sql("select member_id from task_assignee where task_id = ? order by member_id")
                .param(taskId)
                .query(Long.class)
                .list();
    }

    private void givenFamilyWithTwoMembers() {
        jdbc.sql(
                        """
                        insert into family
                          (id, name, timezone, digest_time, last_digest_date, created_at)
                        values (1, 'Ивановы', 'Europe/Moscow', '08:00', '2026-08-11', 0)
                        """)
                .update();
        insertMember(10, "Мама", "PARENT");
        insertMember(11, "Папа", "PARENT");
    }

    private void insertMember(long id, String name, String role) {
        jdbc.sql(
                        """
                        insert into member
                          (id, family_id, telegram_user_id, private_chat_id,
                           display_name, role, status, color, created_at)
                        values (?, 1, ?, ?, ?, ?, 'ACTIVE', 'BLUE', 0)
                        """)
                .params(id, id, id, name, role)
                .update();
    }

    private void insertOpenTask(long id, String title, long assigneeId) {
        jdbc.sql(
                        """
                        insert into task
                          (id, family_id, title, creator_id, assignee_id, status, created_at)
                        values (?, 1, ?, 10, ?, 'OPEN', 0)
                        """)
                .params(id, title, assigneeId)
                .update();
    }

    private void insertDeclinedTask(
            long id, String title, long assigneeId, String reason, long closedAt) {
        jdbc.sql(
                        """
                        insert into task
                          (id, family_id, title, creator_id, assignee_id, status,
                           decline_reason, created_at, closed_at)
                        values (?, 1, ?, 10, ?, 'DECLINED', ?, 0, ?)
                        """)
                .params(id, title, assigneeId, reason, closedAt)
                .update();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // временный каталог, чистится ОС
        }
    }
}

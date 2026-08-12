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
 * Перенос исполнителя серии из колонки в таблицу {@code task_series_assignee}.
 *
 * <p>⚠️ Как и {@link TaskAssigneeMigrationIT}, наследовать {@link AbstractSqliteIT} тут нельзя:
 * там база создаётся с нуля, и к моменту {@code V8} в {@code task_series} нет ни одной строки —
 * перенос выполнялся бы вхолостую.
 *
 * <p>⚠️ Отдельно проверяется то, что в {@code V7} потребовало перестройки таблицы, а здесь —
 * наоборот, запрещает её: на {@code task_series} <b>ссылаются</b> строки {@code task}
 * ({@code task.series_id}). При {@code foreign_keys=true} снос родительской таблицы падает по
 * внешнему ключу, пока на неё смотрит хоть одно вхождение. Поэтому колонка убирается
 * {@code drop column} — он допустим, потому что она не участвует ни в одном индексе.
 */
class SeriesAssigneeMigrationIT {

    private Path directory;
    private DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void migrateToPreviousVersion() throws IOException {
        directory = Files.createTempDirectory("family-todo-series-migration-it");
        Path file = directory.resolve("family-todo.db");

        dataSource = new DriverManagerDataSource(AbstractSqliteIT.jdbcUrl(file));
        Flyway.configure()
                .dataSource(dataSource)
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();
        jdbc = PersistenceConfig.sqliteJdbcClient(dataSource);
    }

    @AfterEach
    void dropDatabase() throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(SeriesAssigneeMigrationIT::deleteQuietly);
        }
    }

    @Test
    void theSeriesKeepsItsAssigneeAsTheOnlyOne() {
        givenFamilyWithTwoMembers();
        insertSeries(1, "Тренировка", 11);

        migrateToLatest();

        assertThat(
                        jdbc.sql(
                                        """
                                        select member_id from task_series_assignee
                                        where series_id = 1
                                        """)
                                .query(Long.class)
                                .list())
                .containsExactly(11L);
    }

    /**
     * Самая опасная часть: вхождения серии — обычные строки {@code task} со ссылкой на неё.
     * Миграция не должна ни потерять их, ни отвязать.
     */
    @Test
    void materialisedOccurrencesSurviveAndStayLinked() {
        givenFamilyWithTwoMembers();
        insertSeries(1, "Тренировка", 11);
        jdbc.sql(
                        """
                        insert into task
                          (id, family_id, title, creator_id, status, created_at,
                           starts_at, ends_at, series_id, occurrence_on)
                        values (100, 1, 'Тренировка', 10, 'OPEN', 0,
                                1786060800000, 1786064400000, 1, '2026-08-17')
                        """)
                .update();
        jdbc.sql("insert into task_assignee (task_id, member_id) values (100, 11)").update();

        migrateToLatest();

        assertThat(jdbc.sql("select series_id from task where id = 100").query(Long.class).single())
                .isEqualTo(1L);
        assertThat(
                        jdbc.sql("select occurrence_on from task where id = 100")
                                .query(String.class)
                                .single())
                .isEqualTo("2026-08-17");
    }

    @Test
    void theSeriesTableNoLongerCarriesAnAssigneeColumn() {
        migrateToLatest();

        List<String> columns =
                jdbc.sql("select name from pragma_table_info('task_series')")
                        .query(String.class)
                        .list();

        assertThat(columns).doesNotContain("assignee_id", "assignee_role");
    }

    /** Правило серии живёт дальше вхождений: снос серии не должен уносить историю. */
    @Test
    void theRuleItselfKeepsEveryOtherField() {
        givenFamilyWithTwoMembers();
        insertSeries(1, "Тренировка", 11);

        migrateToLatest();

        var row =
                jdbc.sql(
                                """
                                select title, creator_id, recurrence, start_time,
                                       duration_min, location, starts_on
                                from task_series where id = 1
                                """)
                        .query(
                                (rs, n) ->
                                        new Object[] {
                                            rs.getString("title"),
                                            rs.getLong("creator_id"),
                                            rs.getString("recurrence"),
                                            rs.getString("start_time"),
                                            rs.getInt("duration_min"),
                                            rs.getString("location"),
                                            rs.getString("starts_on")
                                        })
                        .single();

        assertThat(row)
                .containsExactly("Тренировка", 10L, "1,3,5", "18:00", 90, "бассейн", "2026-08-17");
    }

    @Test
    void anAssignmentMayNotPointAtAMemberThatDoesNotExist() {
        migrateToLatest();

        assertThat(
                        jdbc.sql(
                                        "select count(*) from pragma_foreign_key_list('task_series_assignee')")
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    private void givenFamilyWithTwoMembers() {
        jdbc.sql(
                        """
                        insert into family
                          (id, name, timezone, digest_time, last_digest_date, created_at)
                        values (1, 'Ивановы', 'Europe/Moscow', '08:00', '2026-08-12', 0)
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

    private void insertSeries(long id, String title, long assigneeId) {
        jdbc.sql(
                        """
                        insert into task_series
                          (id, family_id, title, creator_id, assignee_id, assignee_role,
                           recurrence, start_time, duration_min, location, starts_on, created_at)
                        values (?, 1, ?, 10, ?, 'PARENT', '1,3,5', '18:00', 90, 'бассейн',
                                '2026-08-17', 0)
                        """)
                .params(id, title, assigneeId)
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

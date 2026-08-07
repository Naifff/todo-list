package com.familytodo.persistence;

import com.familytodo.config.PersistenceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Свежая база на каждый тест: временный файл, миграции Flyway, затем удаление.
 *
 * <p>URL собирается той же строкой, что и в продовом {@code application.yml} — прагмы SQLite
 * действуют на соединение, а не на базу, поэтому проверять их надо ровно в том виде, в каком они
 * попадут в прод. Главная из них — {@code foreign_keys}: в SQLite внешние ключи по умолчанию
 * <b>выключены</b>, и без явного включения ограничения в схеме были бы декорацией.
 */
public abstract class AbstractSqliteIT {

    private Path directory;
    protected DataSource dataSource;
    protected JdbcClient jdbc;

    @BeforeEach
    void createDatabase() throws IOException {
        directory = Files.createTempDirectory("family-todo-it");
        Path file = directory.resolve("family-todo.db");

        // через DriverManager, а не прямой ссылкой на класс драйвера: sqlite-jdbc
        // остаётся runtimeOnly и не протекает в compile-classpath приложения
        dataSource = new DriverManagerDataSource(jdbcUrl(file));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = PersistenceConfig.sqliteJdbcClient(dataSource);
    }

    @AfterEach
    void dropDatabase() throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(AbstractSqliteIT::deleteQuietly);
        }
    }

    protected static String jdbcUrl(Path file) {
        return "jdbc:sqlite:"
                + file
                + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // временный каталог, чистится ОС
        }
    }
}

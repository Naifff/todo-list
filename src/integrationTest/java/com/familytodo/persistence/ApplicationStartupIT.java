package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Приложение мигрирует базу при старте.
 *
 * <p>Этот тест закрывает дыру, которую все остальные не видели: {@link AbstractSqliteIT} вызывает
 * Flyway <b>программно</b>, поэтому проверял схему, но не то, что её накатывает само приложение.
 * Реально миграции не запускались вовсе — Spring Boot 4 разнёс автоконфигурацию по модулям, и
 * одного {@code flyway-core} на classpath недостаточно, нужен {@code spring-boot-starter-flyway}.
 * Тридцать пять зелёных интеграционных тестов при этом ничего не замечали.
 *
 * <p>База в памяти: пул из одного соединения держит её живой на всё время теста.
 */
@SpringBootTest(
        properties = {
            "telegram.bot.token=startup-test",
            "telegram.bot.username=startup_test_bot",
            "telegram.bot.polling.enabled=false",
            "spring.datasource.url=jdbc:sqlite::memory:?foreign_keys=true"
        })
class ApplicationStartupIT {

    @Autowired private JdbcClient jdbc;

    @Test
    void migrationsRunOnStartup() {
        List<String> tables =
                jdbc.sql("select name from sqlite_master where type = 'table' order by name")
                        .query(String.class)
                        .list();

        assertThat(tables).contains("family", "member", "invite", "task", "id_sequence");
    }

    /** Таблица истории Flyway — доказательство, что схему накатил именно он, а не что-то ещё. */
    @Test
    void flywayRecordsItsHistory() {
        Long applied =
                jdbc.sql("select count(*) from flyway_schema_history where success = 1")
                        .query(Long.class)
                        .single();

        assertThat(applied).isPositive();
    }
}

package com.familytodo.config;

import com.familytodo.adapter.persistence.SqliteExceptionTranslator;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class PersistenceConfig {

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return sqliteJdbcClient(dataSource);
    }

    /**
     * Единственный способ собрать {@link JdbcClient} в проекте — им же пользуются интеграционные
     * тесты. Иначе транслятор ошибок можно поменять в проде и не заметить, что тесты продолжают
     * проверять старое поведение.
     */
    public static JdbcClient sqliteJdbcClient(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(new SqliteExceptionTranslator());
        return JdbcClient.create(template);
    }
}

package com.familytodo.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Выдача идентификаторов из таблицы {@code id_sequence}.
 *
 * <p>Не {@code autoincrement} потому, что доменные сущности неизменяемы по id: он нужен до того,
 * как объект собран, а не после того, как строка вставлена.
 *
 * <p>Один оператор {@code UPDATE ... RETURNING} атомарен сам по себе, так что отдельная блокировка
 * не нужна — тем более что писатель в SQLite всё равно один.
 */
@Component
public class JdbcIdSequence {

    private static final String NEXT =
            """
            update id_sequence
            set next_value = next_value + 1
            where name = ?
            returning next_value
            """;

    private final JdbcClient jdbc;

    public JdbcIdSequence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long next(String name) {
        return jdbc.sql(NEXT).param(name).query(Long.class).single();
    }
}

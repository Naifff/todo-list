package com.familytodo.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Моменты времени в SQLite — целое число миллисекунд от эпохи.
 *
 * <p>Не текст ISO-8601: {@code Instant.toString()} опускает доли секунды, когда они нулевые, и
 * {@code ...T16:00:00.123Z} оказывается лексикографически <b>меньше</b> {@code ...T16:00:00Z} —
 * точка (0x2E) меньше {@code Z} (0x5A). На текстовом хранении условие {@code due_at <= :now} в
 * запросе напоминаний молча возвращало бы не те строки.
 */
final class Instants {

    private Instants() {}

    /**
     * {@code getLong} на NULL возвращает 0, то есть эпоху, — поэтому сразу за ним {@code wasNull}.
     *
     * <p>Типизированный {@code getObject(column, Long.class)} здесь не подходит: sqlite-jdbc
     * отвечает на него «Bad value for type Long». Пара {@code getLong} + {@code wasNull}
     * поддерживается любым драйвером.
     */
    static Instant read(ResultSet rs, String column) throws SQLException {
        long millis = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    static Long write(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}

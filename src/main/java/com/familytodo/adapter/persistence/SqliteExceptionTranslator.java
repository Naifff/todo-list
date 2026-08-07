package com.familytodo.adapter.persistence;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator;

/**
 * Разбор ошибок SQLite по коду результата.
 *
 * <p>Нужен потому, что sqlite-jdbc <b>не выставляет {@code SQLState}</b> — он всегда {@code null}.
 * Штатные трансляторы Spring опираются либо на {@code SQLState}, либо на коды конкретных СУБД, и
 * без этого класса любое нарушение ограничения приходит как {@code UncategorizedSQLException},
 * неотличимое от обрыва соединения.
 *
 * <p>Код 19 ({@code SQLITE_CONSTRAINT}) один на все виды ограничений: уникальность, внешний ключ и
 * {@code CHECK} различаются лишь расширенным кодом в тексте сообщения. Разбирать текст мы не
 * будем — вызывающему достаточно знать, что данные противоречат схеме.
 */
public final class SqliteExceptionTranslator extends AbstractFallbackSQLExceptionTranslator {

    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_CONSTRAINT = 19;

    @Override
    protected DataAccessException doTranslate(String task, String sql, SQLException ex) {
        return switch (errorCode(ex)) {
            case SQLITE_CONSTRAINT ->
                    new DataIntegrityViolationException(buildMessage(task, sql, ex), ex);
            // писатель в SQLite один; busy_timeout переживает короткие пересечения,
            // а сюда доходит только то, что его исчерпало
            case SQLITE_BUSY, SQLITE_LOCKED ->
                    new CannotAcquireLockException(buildMessage(task, sql, ex), ex);
            default -> null;
        };
    }

    /** Код может оказаться на вложенном исключении, если драйвер обернул исходное. */
    private static int errorCode(SQLException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() != 0) {
                return sqlException.getErrorCode();
            }
        }
        return 0;
    }
}

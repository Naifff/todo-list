package com.familytodo.adapter.persistence;

import com.familytodo.domain.Assignment;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

/**
 * Строка → заготовка доменной задачи.
 *
 * <p>Отдельной persistence-сущности нет: {@link Task#restore} и есть точка сборки из хранилища. Но
 * собрать задачу из одной строки больше нельзя — исполнителей у неё несколько, и они лежат в другой
 * таблице.
 *
 * <p>⚠️ Джойном исполнители не тянутся <b>намеренно</b>: он размножил бы строку задачи по числу
 * назначенных, и любая выборка начала бы отвечать «сколько пар», а не «сколько дел». Назначения
 * приезжают вторым запросом по списку идентификаторов, см. {@code JdbcTaskRepository}.
 */
public final class TaskRowMapper implements RowMapper<TaskRowMapper.TaskRow> {

    public static final TaskRowMapper INSTANCE = new TaskRowMapper();

    /** Задача без исполнителей: собрать её в домен можно только вместе с ними. */
    public record TaskRow(
            long id,
            long familyId,
            String title,
            long creatorId,
            TaskStatus status,
            Instant dueAt,
            Instant createdAt,
            Instant closedAt,
            Instant startsAt,
            Instant endsAt,
            String location,
            Long seriesId,
            java.time.LocalDate occurrenceOn) {

        public Task toTask(List<Assignment> assignments) {
            return Task.restore(
                    id,
                    familyId,
                    title,
                    creatorId,
                    assignments,
                    status,
                    dueAt,
                    createdAt,
                    closedAt,
                    startsAt,
                    endsAt,
                    location,
                    seriesId,
                    occurrenceOn);
        }
    }

    /** {@code getLong} отдаёт 0 вместо null, поэтому серия читается через проверку {@code wasNull}. */
    private static Long seriesId(ResultSet rs) throws SQLException {
        long value = rs.getLong("series_id");
        return rs.wasNull() ? null : value;
    }

    private static java.time.LocalDate date(String stored) {
        return stored == null ? null : java.time.LocalDate.parse(stored);
    }

    @Override
    public TaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getString("title"),
                rs.getLong("creator_id"),
                TaskStatus.valueOf(rs.getString("status")),
                Instants.read(rs, "due_at"),
                Instants.read(rs, "created_at"),
                Instants.read(rs, "closed_at"),
                Instants.read(rs, "starts_at"),
                Instants.read(rs, "ends_at"),
                rs.getString("location"),
                seriesId(rs),
                date(rs.getString("occurrence_on")));
    }
}

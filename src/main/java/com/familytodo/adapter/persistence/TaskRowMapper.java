package com.familytodo.adapter.persistence;

import com.familytodo.domain.Assignee;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/**
 * Строка → доменная задача.
 *
 * <p>Отдельной persistence-сущности нет: {@link Task#restore} и есть точка сборки из хранилища.
 *
 * <p>Роль исполнителя приезжает джойном с {@code member}, а не колонкой в {@code task}. Роль
 * меняется, и её копия в задаче протухала бы: правило «родитель правит задачу, только если
 * исполнитель — ребёнок» проверялось бы по вчерашним данным.
 */
public final class TaskRowMapper implements RowMapper<Task> {

    public static final TaskRowMapper INSTANCE = new TaskRowMapper();

    @Override
    public Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Task.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getString("title"),
                rs.getLong("creator_id"),
                new Assignee(
                        rs.getLong("assignee_id"), Role.valueOf(rs.getString("assignee_role"))),
                TaskStatus.valueOf(rs.getString("status")),
                Instants.read(rs, "due_at"),
                rs.getString("decline_reason"),
                Instants.read(rs, "created_at"),
                Instants.read(rs, "closed_at"));
    }
}

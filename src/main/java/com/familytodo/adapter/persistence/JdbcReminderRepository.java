package com.familytodo.adapter.persistence;

import com.familytodo.application.port.ReminderRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReminderRepository implements ReminderRepository {

    /**
     * Нижней границы «не старше двух часов» здесь нет, хотя в плане она была в SQL. Порог считается
     * от момента напоминания, а он сдвигается тихими часами семьи — то есть зависит от её таймзоны.
     * Отбирать по {@code due_at} в SQL значило бы выкинуть отложенное на утро дело раньше, чем это
     * утро наступит.
     */
    /**
     * ⚠️ Момент напоминания — {@code coalesce(starts_at, due_at)}, а не только срок.
     *
     * <p>До этого у события напоминания не было вовсе: у него заполнен {@code starts_at}, а {@code
     * due_at} пуст, и условие {@code due_at is not null} отсекало его молча. «Др Ралины» в календаре
     * стоял, а не напоминал о себе никак — и заметить это можно было только тем, что уведомление не
     * пришло.
     */
    private static final String FIND_DUE =
            """
            select t.id, t.family_id, coalesce(t.starts_at, t.due_at) as due_at, f.timezone
            from task t
            join family f on f.id = t.family_id
            where t.status = 'OPEN'
              and coalesce(t.starts_at, t.due_at) is not null
              and coalesce(t.starts_at, t.due_at) <= ?
              and t.reminded_at is null
            order by coalesce(t.starts_at, t.due_at)
            limit ?
            """;

    private final JdbcClient jdbc;

    public JdbcReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DueReminder> findDue(Instant now, int limit) {
        return jdbc.sql(FIND_DUE)
                .params(now.toEpochMilli(), limit)
                .query(
                        (rs, rowNum) ->
                                new DueReminder(
                                        rs.getLong("id"),
                                        rs.getLong("family_id"),
                                        Instant.ofEpochMilli(rs.getLong("due_at")),
                                        ZoneId.of(rs.getString("timezone"))))
                .list();
    }

    @Override
    public void markReminded(Collection<Long> taskIds, Instant now) {
        if (taskIds.isEmpty()) {
            return;
        }
        String placeholders = "?,".repeat(taskIds.size() - 1) + "?";
        List<Object> params = new java.util.ArrayList<>();
        params.add(now.toEpochMilli());
        params.addAll(taskIds);

        jdbc.sql("update task set reminded_at = ? where id in (" + placeholders + ")")
                .params(params)
                .update();
    }
}

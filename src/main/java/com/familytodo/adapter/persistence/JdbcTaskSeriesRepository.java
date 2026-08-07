package com.familytodo.adapter.persistence;

import com.familytodo.application.port.TaskSeriesRepository;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskSeries;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Серии в SQLite.
 *
 * <p>Роль исполнителя берётся джойном из {@code member}, как и у задач: копия в строке серии
 * протухла бы в тот день, когда ребёнок становится родителем.
 */
@Repository
public class JdbcTaskSeriesRepository implements TaskSeriesRepository {

    private static final String SEQUENCE = "task_series";

    /** Как и у семьи: {@code HH:mm} строго, иначе ненулевые секунды поехали бы в формат колонки. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SELECT =
            """
            select s.id, s.family_id, s.title, s.creator_id, s.assignee_id,
                   s.recurrence, s.start_time, s.duration_min, s.location,
                   s.starts_on, s.ends_on, s.stopped_at, s.created_at,
                   m.role as assignee_role
            from task_series s
            join member m on m.id = s.assignee_id
            """;

    private static final String UPSERT =
            """
            insert into task_series (id, family_id, title, creator_id, assignee_id, assignee_role,
                                     recurrence, start_time, duration_min, location,
                                     starts_on, ends_on, stopped_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                title        = excluded.title,
                assignee_id  = excluded.assignee_id,
                recurrence   = excluded.recurrence,
                start_time   = excluded.start_time,
                duration_min = excluded.duration_min,
                location     = excluded.location,
                starts_on    = excluded.starts_on,
                ends_on      = excluded.ends_on,
                stopped_at   = excluded.stopped_at
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcTaskSeriesRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public TaskSeries save(TaskSeries series) {
        jdbc.sql(UPSERT)
                .params(
                        series.id(),
                        series.familyId(),
                        series.title(),
                        series.creatorId(),
                        series.assignee().memberId(),
                        series.assignee().role().name(),
                        series.recurrence().stored(),
                        series.startTime().format(TIME),
                        series.duration() == null ? null : series.duration().toMinutes(),
                        series.location(),
                        series.startsOn().toString(),
                        series.endsOn() == null ? null : series.endsOn().toString(),
                        Instants.write(series.stoppedAt()),
                        Instants.write(series.createdAt()))
                .update();
        return series;
    }

    @Override
    public Optional<TaskSeries> findById(long familyId, long seriesId) {
        return jdbc.sql(SELECT + " where s.family_id = ? and s.id = ?")
                .params(familyId, seriesId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<TaskSeries> findActive(long familyId) {
        return jdbc.sql(SELECT + " where s.family_id = ? and s.stopped_at is null order by s.id")
                .param(familyId)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<TaskSeries> findActive() {
        return jdbc.sql(SELECT + " where s.stopped_at is null order by s.id").query(MAPPER).list();
    }

    private static final RowMapper<TaskSeries> MAPPER = JdbcTaskSeriesRepository::mapRow;

    private static TaskSeries mapRow(ResultSet rs, int rowNum) throws SQLException {
        long minutes = rs.getLong("duration_min");
        Duration duration = rs.wasNull() ? null : Duration.ofMinutes(minutes);
        String endsOn = rs.getString("ends_on");

        return TaskSeries.restore(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getString("title"),
                rs.getLong("creator_id"),
                new Assignee(rs.getLong("assignee_id"), Role.valueOf(rs.getString("assignee_role"))),
                Recurrence.parse(rs.getString("recurrence")),
                LocalTime.parse(rs.getString("start_time")),
                duration,
                rs.getString("location"),
                LocalDate.parse(rs.getString("starts_on")),
                endsOn == null ? null : LocalDate.parse(endsOn),
                Instants.read(rs, "stopped_at"),
                Instants.read(rs, "created_at"));
    }
}

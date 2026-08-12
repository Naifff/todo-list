package com.familytodo.adapter.persistence;

import com.familytodo.application.port.TaskSeriesRepository;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskSeries;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Серии в SQLite.
 *
 * <p>Исполнители лежат в {@code task_series_assignee} и приезжают <b>вторым запросом</b>, как и у
 * задач: джойн размножил бы строку правила по числу назначенных, и «сколько серий» превратилось бы
 * в «сколько пар».
 *
 * <p>Роль исполнителя берётся джойном из {@code member}: копия в строке серии протухла бы в тот
 * день, когда ребёнок становится родителем.
 */
@Repository
public class JdbcTaskSeriesRepository implements TaskSeriesRepository {

    private static final String SEQUENCE = "task_series";

    /** Как и у семьи: {@code HH:mm} строго, иначе ненулевые секунды поехали бы в формат колонки. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String SELECT =
            """
            select s.id, s.family_id, s.title, s.creator_id,
                   s.recurrence, s.start_time, s.duration_min, s.location,
                   s.starts_on, s.ends_on, s.stopped_at, s.created_at
            from task_series s
            """;

    private static final String UPSERT =
            """
            insert into task_series (id, family_id, title, creator_id,
                                     recurrence, start_time, duration_min, location,
                                     starts_on, ends_on, stopped_at, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                title        = excluded.title,
                recurrence   = excluded.recurrence,
                start_time   = excluded.start_time,
                duration_min = excluded.duration_min,
                location     = excluded.location,
                starts_on    = excluded.starts_on,
                ends_on      = excluded.ends_on,
                stopped_at   = excluded.stopped_at
            """;

    private static final String SELECT_ASSIGNEES =
            """
            select sa.series_id, sa.member_id, m.role
            from task_series_assignee sa
            join member m on m.id = sa.member_id
            join task_series s on s.id = sa.series_id
            where s.family_id = ? and sa.series_id in (%s)
            order by sa.series_id, sa.rowid
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
                        series.recurrence().stored(),
                        series.startTime().format(TIME),
                        series.duration() == null ? null : series.duration().toMinutes(),
                        series.location(),
                        series.startsOn().toString(),
                        series.endsOn() == null ? null : series.endsOn().toString(),
                        Instants.write(series.stoppedAt()),
                        Instants.write(series.createdAt()))
                .update();
        saveAssignees(series);
        return series;
    }

    /**
     * Сначала вписываем текущих, потом убираем лишних — тот же порядок и по той же причине, что у
     * задач: обратный оставил бы правило без единого исполнителя между двумя запросами, а собрать
     * такое из хранилища нельзя вовсе.
     */
    private void saveAssignees(TaskSeries series) {
        for (Assignee assignee : series.assignees()) {
            jdbc.sql(
                            """
                            insert into task_series_assignee (series_id, member_id) values (?, ?)
                            on conflict (series_id, member_id) do nothing
                            """)
                    .params(series.id(), assignee.memberId())
                    .update();
        }

        List<Object> params = new ArrayList<>();
        params.add(series.id());
        series.assignees().forEach(assignee -> params.add(assignee.memberId()));
        jdbc.sql(
                        "delete from task_series_assignee where series_id = ? and member_id not in ("
                                + placeholders(series.assignees().size())
                                + ")")
                .params(params)
                .update();
    }

    /**
     * Видимость ребёнка: правило показывается, только если он исполнитель или автор.
     *
     * <p>Условие в SQL, а не отсевом после выборки — по той же причине, что и у задач: отсеянная
     * строка уже прочитана, и дальше всё держится на том, что её никому не отдадут.
     */
    private static final String VISIBLE_TO_CHILD =
            """
             and (exists (select 1 from task_series_assignee sa
                          where sa.series_id = s.id and sa.member_id = ?)
                  or s.creator_id = ?)
            """;

    @Override
    public Optional<TaskSeries> findById(long familyId, Long visibleToMemberId, long seriesId) {
        List<Object> params = new ArrayList<>(List.of(familyId, seriesId));
        String sql = SELECT + " where s.family_id = ? and s.id = ?";
        if (visibleToMemberId != null) {
            sql += VISIBLE_TO_CHILD;
            params.add(visibleToMemberId);
            params.add(visibleToMemberId);
        }

        return assemble(
                        familyId,
                        jdbc.sql(sql)
                                .params(params)
                                .query(JdbcTaskSeriesRepository::mapRow)
                                .list())
                .stream()
                .findFirst();
    }

    @Override
    public List<TaskSeries> findActive(long familyId, Long visibleToMemberId) {
        List<Object> params = new ArrayList<>(List.of(familyId));
        String sql = SELECT + " where s.family_id = ? and s.stopped_at is null";
        if (visibleToMemberId != null) {
            sql += VISIBLE_TO_CHILD;
            params.add(visibleToMemberId);
            params.add(visibleToMemberId);
        }

        return assemble(
                familyId,
                jdbc.sql(sql + " order by s.id")
                        .params(params)
                        .query(JdbcTaskSeriesRepository::mapRow)
                        .list());
    }

    /**
     * Все активные серии всех семей — единственный метод без {@code familyId}.
     *
     * <p>Он для джобы материализации: у неё нет «смотрящего», она обходит семьи подряд. Поэтому и
     * исполнители здесь тянутся по семье каждой серии, а не одним запросом на всех.
     */
    @Override
    public List<TaskSeries> findActive() {
        List<Row> rows =
                jdbc.sql(SELECT + " where s.stopped_at is null order by s.id")
                        .query(JdbcTaskSeriesRepository::mapRow)
                        .list();

        Map<Long, List<Row>> byFamily = new LinkedHashMap<>();
        rows.forEach(row -> byFamily.computeIfAbsent(row.familyId(), key -> new ArrayList<>()).add(row));

        List<TaskSeries> all = new ArrayList<>();
        byFamily.forEach((familyId, familyRows) -> all.addAll(assemble(familyId, familyRows)));
        return all;
    }

    private List<TaskSeries> assemble(long familyId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        params.add(familyId);
        rows.forEach(row -> params.add(row.id()));

        Map<Long, List<Assignee>> bySeries = new LinkedHashMap<>();
        jdbc.sql(SELECT_ASSIGNEES.formatted(placeholders(rows.size())))
                .params(params)
                .query(
                        (rs, n) ->
                                Map.entry(
                                        rs.getLong("series_id"),
                                        new Assignee(
                                                rs.getLong("member_id"),
                                                Role.valueOf(rs.getString("role")))))
                .list()
                .forEach(
                        entry ->
                                bySeries.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                                        .add(entry.getValue()));

        // Серия без исполнителей — нарушенный инвариант, а не пустой список: домен такое не
        // примет и бросит. Правило, которое некому выполнять, лучше увидеть отказом выборки
        return rows.stream()
                .map(row -> row.toSeries(bySeries.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private static String placeholders(int count) {
        return "?,".repeat(count - 1) + "?";
    }

    /** Правило без исполнителей: собрать его в домен можно только вместе с ними. */
    private record Row(
            long id,
            long familyId,
            String title,
            long creatorId,
            Recurrence recurrence,
            LocalTime startTime,
            Duration duration,
            String location,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant stoppedAt,
            Instant createdAt) {

        TaskSeries toSeries(List<Assignee> assignees) {
            return TaskSeries.restore(
                    id,
                    familyId,
                    title,
                    creatorId,
                    assignees,
                    recurrence,
                    startTime,
                    duration,
                    location,
                    startsOn,
                    endsOn,
                    stoppedAt,
                    createdAt);
        }
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        long minutes = rs.getLong("duration_min");
        Duration duration = rs.wasNull() ? null : Duration.ofMinutes(minutes);
        String endsOn = rs.getString("ends_on");

        return new Row(
                rs.getLong("id"),
                rs.getLong("family_id"),
                rs.getString("title"),
                rs.getLong("creator_id"),
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

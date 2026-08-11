package com.familytodo.adapter.persistence;

import com.familytodo.adapter.persistence.TaskRowMapper.TaskRow;
import com.familytodo.application.TaskQuery;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Assignment;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Задачи в SQLite.
 *
 * <p>Каждый запрос начинается с {@code t.family_id = ?} — граница семьи не условие, которое можно
 * забыть дописать, а первая строка любого {@code where} в этом классе.
 *
 * <p>Исполнители лежат в {@code task_assignee} и приезжают <b>вторым запросом</b>, а не джойном:
 * джойн размножил бы строку задачи по числу назначенных, и выборка отвечала бы «сколько пар» вместо
 * «сколько дел».
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private static final String SEQUENCE = "task";

    private static final String SELECT =
            """
            select t.id, t.family_id, t.title, t.creator_id, t.status, t.due_at,
                   t.created_at, t.closed_at, t.starts_at, t.ends_at, t.location
            from task t
            """;

    private static final String UPSERT =
            """
            insert into task (id, family_id, title, creator_id, status, due_at,
                              created_at, closed_at, starts_at, ends_at, location)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                title     = excluded.title,
                status    = excluded.status,
                due_at    = excluded.due_at,
                closed_at = excluded.closed_at,
                starts_at = excluded.starts_at,
                ends_at   = excluded.ends_at,
                location  = excluded.location
            """;

    /**
     * Вставка вхождения серии. {@code do nothing} по паре (серия, дата) — вторая линия обороны:
     * джоба и так спрашивает, каких дат не хватает, но между запросом и вставкой мог пройти другой
     * прогон. Уникальный индекс делает дубль невозможным, а не маловероятным.
     */
    private static final String INSERT_OCCURRENCE =
            """
            insert into task (id, family_id, title, creator_id, status, due_at,
                              created_at, closed_at, starts_at, ends_at, location,
                              series_id, occurrence_on)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (series_id, occurrence_on) where series_id is not null do nothing
            """;

    private static final String UPSERT_ASSIGNMENT =
            """
            insert into task_assignee (task_id, member_id, declined_at, decline_reason)
            values (?, ?, ?, ?)
            on conflict (task_id, member_id) do update set
                declined_at    = excluded.declined_at,
                decline_reason = excluded.decline_reason
            """;

    /** Роль исполнителя приезжает из {@code member}: её копия в назначении протухала бы. */
    private static final String SELECT_ASSIGNMENTS =
            """
            select ta.task_id, ta.member_id, m.role, ta.declined_at, ta.decline_reason
            from task_assignee ta
            join member m on m.id = ta.member_id
            join task t on t.id = ta.task_id
            where t.family_id = ? and ta.task_id in (%s)
            order by ta.task_id, ta.rowid
            """;

    private final JdbcClient jdbc;
    private final JdbcIdSequence sequence;

    public JdbcTaskRepository(JdbcClient jdbc, JdbcIdSequence sequence) {
        this.jdbc = jdbc;
        this.sequence = sequence;
    }

    @Override
    public long nextId() {
        return sequence.next(SEQUENCE);
    }

    @Override
    public Task save(Task task) {
        jdbc.sql(UPSERT)
                .params(
                        task.id(),
                        task.familyId(),
                        task.title(),
                        task.creatorId(),
                        task.status().name(),
                        Instants.write(task.dueAt()),
                        Instants.write(task.createdAt()),
                        Instants.write(task.closedAt()),
                        Instants.write(task.startsAt()),
                        Instants.write(task.endsAt()),
                        task.location())
                .update();
        saveAssignments(task);
        return task;
    }

    /**
     * Сначала вписываем текущих исполнителей, и только потом убираем лишних.
     *
     * <p>⚠️ Порядок важен. Обратный — «стереть всех и вставить заново» — короче, но между двумя
     * операциями задача остаётся без единого исполнителя. Транзакции здесь нет, а сборка задачи из
     * хранилища такое состояние не принимает вовсе: обрыв ровно в этой точке сделал бы дело
     * незагружаемым.
     */
    private void saveAssignments(Task task) {
        for (Assignment assignment : task.assignments()) {
            jdbc.sql(UPSERT_ASSIGNMENT)
                    .params(
                            task.id(),
                            assignment.memberId(),
                            Instants.write(assignment.declinedAt()),
                            assignment.declineReason())
                    .update();
        }

        List<Object> params = new ArrayList<>();
        params.add(task.id());
        task.assignments().forEach(assignment -> params.add(assignment.memberId()));
        jdbc.sql(
                        "delete from task_assignee where task_id = ? and member_id not in ("
                                + placeholders(task.assignments().size())
                                + ")")
                .params(params)
                .update();
    }

    @Override
    public void saveOccurrence(Task task, long seriesId, LocalDate occurrenceOn) {
        int inserted =
                jdbc.sql(INSERT_OCCURRENCE)
                        .params(
                                task.id(),
                                task.familyId(),
                                task.title(),
                                task.creatorId(),
                                task.status().name(),
                                Instants.write(task.dueAt()),
                                Instants.write(task.createdAt()),
                                Instants.write(task.closedAt()),
                                Instants.write(task.startsAt()),
                                Instants.write(task.endsAt()),
                                task.location(),
                                seriesId,
                                occurrenceOn.toString())
                        .update();

        // вхождение уже было — его исполнители тоже, и переписывать их поверх чужой правки нельзя
        if (inserted > 0) {
            saveAssignments(task);
        }
    }

    @Override
    public Set<LocalDate> occurrenceDates(
            long familyId, long seriesId, LocalDate from, LocalDate to) {
        return jdbc.sql(
                        """
                        select occurrence_on from task
                        where family_id = ? and series_id = ?
                          and occurrence_on >= ? and occurrence_on <= ?
                        """)
                .params(familyId, seriesId, from.toString(), to.toString())
                .query(String.class)
                .list()
                .stream()
                .map(LocalDate::parse)
                .collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    public int deleteOpenOccurrencesFrom(long familyId, long seriesId, LocalDate from) {
        return jdbc.sql(
                        """
                        delete from task
                        where family_id = ? and series_id = ?
                          and status = 'OPEN' and occurrence_on >= ?
                        """)
                .params(familyId, seriesId, from.toString())
                .update();
    }

    @Override
    public Optional<Task> findById(long familyId, long taskId) {
        return jdbc.sql(SELECT + " where t.family_id = ? and t.id = ?")
                .params(familyId, taskId)
                .query(TaskRowMapper.INSTANCE)
                .optional()
                .map(row -> assemble(familyId, List.of(row)).getFirst());
    }

    @Override
    public List<Task> find(TaskQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where t.family_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(query.familyId());

        sql.append(" and t.status in (")
                .append(placeholders(query.statuses().size()))
                .append(")");
        for (TaskStatus status : query.statuses()) {
            params.add(status.name());
        }

        // сужение для ребёнка — часть запроса, а не отсев после выборки:
        // иначе чужие строки уже прочитаны, и остаётся надеяться, что их не покажут.
        // Отказавшийся своё дело видеть продолжает: оно всё ещё его.
        if (query.visibleToMemberId() != null) {
            sql.append(" and (exists (select 1 from task_assignee ta")
                    .append(" where ta.task_id = t.id and ta.member_id = ?)")
                    .append(" or t.creator_id = ?)");
            params.add(query.visibleToMemberId());
            params.add(query.visibleToMemberId());
        }

        // ⚠️ «что поручено мне» и «что мне видно» — разные вопросы. Отказавшийся выпадает
        // отсюда, пока дело открыто: он уже ответил, и в /my, дайджесте и напоминаниях ему
        // это дело не висит. В истории оно закрыто и остаётся видимым — «от чего я
        // отказался» законный вопрос.
        if (query.assigneeId() != null) {
            sql.append(" and exists (select 1 from task_assignee ta")
                    .append(" where ta.task_id = t.id and ta.member_id = ?")
                    .append(" and (ta.declined_at is null or t.status <> 'OPEN'))");
            params.add(query.assigneeId());
        }
        if (query.creatorId() != null) {
            sql.append(" and t.creator_id = ?");
            params.add(query.creatorId());
        }

        // момент дела — начало интервала, иначе срок; фильтр в SQL, а не отсевом
        if (query.from() != null) {
            sql.append(" and coalesce(t.starts_at, t.due_at) >= ?");
            params.add(query.from().toEpochMilli());
        }
        if (query.to() != null) {
            sql.append(" and coalesce(t.starts_at, t.due_at) < ?");
            params.add(query.to().toEpochMilli());
        }
        if (query.undatedOnly()) {
            sql.append(" and t.starts_at is null and t.due_at is null");
        }

        // сначала со сроком по возрастанию, бессрочные в конце
        sql.append(" order by coalesce(t.starts_at, t.due_at, 9223372036854775807), t.id");

        List<TaskRow> rows =
                jdbc.sql(sql.toString()).params(params).query(TaskRowMapper.INSTANCE).list();
        return assemble(query.familyId(), rows);
    }

    @Override
    public void delete(long familyId, long taskId) {
        jdbc.sql("delete from task where family_id = ? and id = ?")
                .params(familyId, taskId)
                .update();
    }

    /**
     * Достроить задачи их исполнителями.
     *
     * <p>Один запрос на всю страницу, а не по одному на задачу: список из двадцати дел иначе стоил
     * бы двадцати одного обращения к базе, а писатель в SQLite один.
     */
    private List<Task> assemble(long familyId, List<TaskRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        params.add(familyId);
        rows.forEach(row -> params.add(row.id()));

        Map<Long, List<Assignment>> byTask = new LinkedHashMap<>();
        jdbc.sql(SELECT_ASSIGNMENTS.formatted(placeholders(rows.size())))
                .params(params)
                .query(
                        (rs, n) ->
                                Map.entry(
                                        rs.getLong("task_id"),
                                        new Assignment(
                                                rs.getLong("member_id"),
                                                Role.valueOf(rs.getString("role")),
                                                Instants.read(rs, "declined_at"),
                                                rs.getString("decline_reason"))))
                .list()
                .forEach(
                        entry ->
                                byTask.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                                        .add(entry.getValue()));

        // Задача без исполнителей — нарушенный инвариант, а не пустой список: сборка домена
        // такое не примет и бросит. Это осознанно: дело, которое никому не поручено, лучше
        // увидеть как отказ выборки, чем как строку, на которую никто не смотрит.
        return rows.stream()
                .map(row -> row.toTask(byTask.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private static String placeholders(int count) {
        return "?,".repeat(count - 1) + "?";
    }
}

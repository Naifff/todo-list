package com.familytodo.adapter.persistence;

import com.familytodo.application.TaskQuery;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Задачи в SQLite.
 *
 * <p>Каждый запрос начинается с {@code t.family_id = ?} — граница семьи не условие, которое можно
 * забыть дописать, а первая строка любого {@code where} в этом классе.
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private static final String SEQUENCE = "task";

    /** Роль исполнителя берётся из {@code member} джойном: копия в {@code task} протухала бы. */
    private static final String SELECT =
            """
            select t.id, t.family_id, t.title, t.creator_id, t.assignee_id,
                   t.status, t.due_at, t.decline_reason, t.created_at, t.closed_at,
                   t.starts_at, t.ends_at, t.location,
                   m.role as assignee_role
            from task t
            join member m on m.id = t.assignee_id
            """;

    private static final String UPSERT =
            """
            insert into task (id, family_id, title, creator_id, assignee_id,
                              status, due_at, decline_reason, created_at, closed_at,
                              starts_at, ends_at, location)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                title          = excluded.title,
                assignee_id    = excluded.assignee_id,
                status         = excluded.status,
                due_at         = excluded.due_at,
                decline_reason = excluded.decline_reason,
                closed_at      = excluded.closed_at,
                starts_at      = excluded.starts_at,
                ends_at        = excluded.ends_at,
                location       = excluded.location
            """;

    /**
     * Вставка вхождения серии. {@code do nothing} по паре (серия, дата) — вторая линия обороны:
     * джоба и так спрашивает, каких дат не хватает, но между запросом и вставкой мог пройти другой
     * прогон. Уникальный индекс делает дубль невозможным, а не маловероятным.
     */
    private static final String INSERT_OCCURRENCE =
            """
            insert into task (id, family_id, title, creator_id, assignee_id,
                              status, due_at, decline_reason, created_at, closed_at,
                              starts_at, ends_at, location, series_id, occurrence_on)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (series_id, occurrence_on) where series_id is not null do nothing
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
                        task.assignee().memberId(),
                        task.status().name(),
                        Instants.write(task.dueAt()),
                        task.declineReason(),
                        Instants.write(task.createdAt()),
                        Instants.write(task.closedAt()),
                        Instants.write(task.startsAt()),
                        Instants.write(task.endsAt()),
                        task.location())
                .update();
        return task;
    }

    @Override
    public void saveOccurrence(Task task, long seriesId, LocalDate occurrenceOn) {
        jdbc.sql(INSERT_OCCURRENCE)
                .params(
                        task.id(),
                        task.familyId(),
                        task.title(),
                        task.creatorId(),
                        task.assignee().memberId(),
                        task.status().name(),
                        Instants.write(task.dueAt()),
                        task.declineReason(),
                        Instants.write(task.createdAt()),
                        Instants.write(task.closedAt()),
                        Instants.write(task.startsAt()),
                        Instants.write(task.endsAt()),
                        task.location(),
                        seriesId,
                        occurrenceOn.toString())
                .update();
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
                .optional();
    }

    @Override
    public List<Task> find(TaskQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where t.family_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(query.familyId());

        sql.append(" and t.status in (")
                .append("?,".repeat(query.statuses().size() - 1))
                .append("?)");
        for (TaskStatus status : query.statuses()) {
            params.add(status.name());
        }

        // сужение для ребёнка — часть запроса, а не отсев после выборки:
        // иначе чужие строки уже прочитаны, и остаётся надеяться, что их не покажут
        if (query.visibleToMemberId() != null) {
            sql.append(" and (t.assignee_id = ? or t.creator_id = ?)");
            params.add(query.visibleToMemberId());
            params.add(query.visibleToMemberId());
        }
        if (query.assigneeId() != null) {
            sql.append(" and t.assignee_id = ?");
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
        sql.append(
                " order by coalesce(t.starts_at, t.due_at, 9223372036854775807), t.id");

        return jdbc.sql(sql.toString()).params(params).query(TaskRowMapper.INSTANCE).list();
    }

    @Override
    public void delete(long familyId, long taskId) {
        jdbc.sql("delete from task where family_id = ? and id = ?")
                .params(familyId, taskId)
                .update();
    }
}

package com.familytodo.application.fake;

import com.familytodo.application.TaskQuery;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Фейк хранилища задач.
 *
 * <p>Фильтр по {@code familyId} применяется здесь так же безусловно, как в SQL: если фейк начнёт
 * прощать то, чего не простит база, тесты юзкейсов перестанут что-либо значить.
 */
public final class InMemoryTaskRepository implements TaskRepository {

    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public Task save(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<Task> findById(long familyId, long taskId) {
        return Optional.ofNullable(tasks.get(taskId)).filter(t -> t.familyId() == familyId);
    }

    @Override
    public List<Task> find(TaskQuery query) {
        List<Task> found = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (matches(task, query)) {
                found.add(task);
            }
        }
        found.sort(
                Comparator.<Task, Instant>comparing(
                                InMemoryTaskRepository::moment,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::id));
        return found;
    }

    @Override
    public void delete(long familyId, long taskId) {
        findById(familyId, taskId).ifPresent(task -> tasks.remove(task.id()));
    }

    private boolean matches(Task task, TaskQuery query) {
        if (task.familyId() != query.familyId()) {
            return false;
        }
        if (!query.statuses().contains(task.status())) {
            return false;
        }
        if (query.assigneeId() != null && task.assignee().memberId() != query.assigneeId()) {
            return false;
        }
        if (query.creatorId() != null && task.creatorId() != query.creatorId()) {
            return false;
        }
        if (query.visibleToMemberId() != null) {
            long viewer = query.visibleToMemberId();
            if (task.assignee().memberId() != viewer && task.creatorId() != viewer) {
                return false;
            }
        }

        // те же условия, что в SQL: фейк, который прощает больше базы, обесценивает
        // все тесты юзкейсов разом
        Instant moment = moment(task);
        if (query.undatedOnly()) {
            return moment == null;
        }
        if (query.from() != null && (moment == null || moment.isBefore(query.from()))) {
            return false;
        }
        return query.to() == null || (moment != null && moment.isBefore(query.to()));
    }

    /** Момент дела — начало интервала, иначе срок. Совпадает с coalesce в запросе. */
    private static Instant moment(Task task) {
        return task.startsAt() != null ? task.startsAt() : task.dueAt();
    }
}

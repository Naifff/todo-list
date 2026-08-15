package com.familytodo.application.fake;

import com.familytodo.application.TaskQuery;
import com.familytodo.application.port.TaskRepository;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Фейк хранилища задач.
 *
 * <p>Фильтр по {@code familyId} применяется здесь так же безусловно, как в SQL: если фейк начнёт
 * прощать то, чего не простит база, тесты юзкейсов перестанут что-либо значить.
 */
public final class InMemoryTaskRepository implements TaskRepository {

    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private final Map<Occurrence, Long> occurrences = new LinkedHashMap<>();
    private final Set<Occurrence> skipped = new java.util.LinkedHashSet<>();
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
        // тот же порядок, что в SQL: прошедшее событие в конце, дальше по моменту
        found.sort(
                Comparator.<Task, Integer>comparing(task -> isPastEvent(task, query.eventsFrom()))
                        .thenComparing(
                                InMemoryTaskRepository::moment,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::id));
        return found;
    }

    /**
     * Удаление вхождения записывает пропуск — ровно как {@code JdbcTaskRepository.delete}. Фейк,
     * который прощает больше базы или помнит меньше неё, обесценивает тесты юзкейсов.
     */
    @Override
    public void delete(long familyId, long taskId) {
        findById(familyId, taskId)
                .ifPresent(
                        task -> {
                            if (task.isOccurrence()) {
                                skipped.add(new Occurrence(task.seriesId(), task.occurrenceOn()));
                            }
                            occurrences.values().remove(task.id());
                            tasks.remove(task.id());
                        });
    }

    @Override
    public void saveOccurrence(Task task) {
        Occurrence key = new Occurrence(task.seriesId(), task.occurrenceOn());
        // тот же запрет на дубль, что даёт уникальный индекс в схеме
        if (occurrences.containsKey(key)) {
            return;
        }
        occurrences.put(key, task.id());
        tasks.put(task.id(), task);
    }

    @Override
    public Set<LocalDate> skippedDates(
            long familyId, long seriesId, LocalDate from, LocalDate to) {
        Set<LocalDate> dates = new HashSet<>();
        for (Occurrence key : skipped) {
            if (key.seriesId() == seriesId
                    && !key.date().isBefore(from)
                    && !key.date().isAfter(to)) {
                dates.add(key.date());
            }
        }
        return dates;
    }

    @Override
    public Set<LocalDate> occurrenceDates(
            long familyId, long seriesId, LocalDate from, LocalDate to) {
        Set<LocalDate> dates = new HashSet<>();
        occurrences.forEach(
                (key, taskId) -> {
                    Task task = tasks.get(taskId);
                    if (task != null
                            && task.familyId() == familyId
                            && key.seriesId() == seriesId
                            && !key.date().isBefore(from)
                            && !key.date().isAfter(to)) {
                        dates.add(key.date());
                    }
                });
        return dates;
    }

    @Override
    public int deleteOpenOccurrencesFrom(long familyId, long seriesId, LocalDate from) {
        List<Occurrence> doomed = new ArrayList<>();
        occurrences.forEach(
                (key, taskId) -> {
                    Task task = tasks.get(taskId);
                    if (task != null
                            && task.familyId() == familyId
                            && key.seriesId() == seriesId
                            && task.status() == TaskStatus.OPEN
                            && !key.date().isBefore(from)) {
                        doomed.add(key);
                    }
                });
        doomed.forEach(
                key -> {
                    tasks.remove(occurrences.get(key));
                    occurrences.remove(key);
                });
        return doomed.size();
    }

    private record Occurrence(long seriesId, LocalDate date) {}

    private boolean matches(Task task, TaskQuery query) {
        if (task.familyId() != query.familyId()) {
            return false;
        }
        if (!query.statuses().contains(task.status())) {
            return false;
        }
        // «что поручено мне» — отказавшийся выпадает, пока дело открыто: он уже ответил.
        // Условие повторяет SQL дословно; фейк, который прощает больше базы, обесценивает
        // все тесты юзкейсов разом
        if (query.assigneeId() != null) {
            long assignee = query.assigneeId();
            boolean onIt =
                    task.assignments().stream()
                            .anyMatch(
                                    assignment ->
                                            assignment.memberId() == assignee
                                                    && (!assignment.hasDeclined()
                                                            || task.status() != TaskStatus.OPEN));
            if (!onIt) {
                return false;
            }
        }
        if (query.creatorId() != null && task.creatorId() != query.creatorId()) {
            return false;
        }
        // «что мне видно» — а здесь отказавшийся своё дело видеть продолжает: оно всё ещё его
        if (query.visibleToMemberId() != null) {
            long viewer = query.visibleToMemberId();
            boolean involved =
                    task.creatorId() == viewer
                            || task.assignments().stream()
                                    .anyMatch(assignment -> assignment.memberId() == viewer);
            if (!involved) {
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

    /** Прошедшее событие — то, у которого кончился интервал. У дела со сроком интервала нет. */
    private static int isPastEvent(Task task, Instant eventsFrom) {
        if (eventsFrom == null || task.startsAt() == null) {
            return 0;
        }
        Instant over = task.endsAt() != null ? task.endsAt() : task.startsAt();
        return over.isBefore(eventsFrom) ? 1 : 0;
    }

    /** Момент дела — начало интервала, иначе срок. Совпадает с coalesce в запросе. */
    private static Instant moment(Task task) {
        return task.startsAt() != null ? task.startsAt() : task.dueAt();
    }
}

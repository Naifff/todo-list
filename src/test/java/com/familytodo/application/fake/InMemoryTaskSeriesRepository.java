package com.familytodo.application.fake;

import com.familytodo.application.port.TaskSeriesRepository;
import com.familytodo.domain.TaskSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Фейк хранилища серий. Фильтр по семье безусловный — как и в SQL. */
public final class InMemoryTaskSeriesRepository implements TaskSeriesRepository {

    private final Map<Long, TaskSeries> series = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public TaskSeries save(TaskSeries rule) {
        series.put(rule.id(), rule);
        return rule;
    }

    @Override
    public Optional<TaskSeries> findById(long familyId, Long visibleToMemberId, long seriesId) {
        return Optional.ofNullable(series.get(seriesId))
                .filter(s -> s.familyId() == familyId)
                .filter(s -> visible(s, visibleToMemberId));
    }

    @Override
    public List<TaskSeries> findActive(long familyId, Long visibleToMemberId) {
        List<TaskSeries> found = new ArrayList<>();
        for (TaskSeries rule : series.values()) {
            if (rule.familyId() == familyId && !rule.isStopped() && visible(rule, visibleToMemberId)) {
                found.add(rule);
            }
        }
        return found;
    }

    /** Повторяет условие из SQL: {@code null} — видно всё, иначе только своё. */
    private static boolean visible(TaskSeries rule, Long visibleToMemberId) {
        if (visibleToMemberId == null) {
            return true;
        }
        return rule.creatorId() == visibleToMemberId
                || rule.assignees().stream()
                        .anyMatch(assignee -> assignee.memberId() == visibleToMemberId);
    }

    @Override
    public List<TaskSeries> findActive() {
        return series.values().stream().filter(rule -> !rule.isStopped()).toList();
    }
}

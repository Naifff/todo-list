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
    public Optional<TaskSeries> findById(long familyId, long seriesId) {
        return Optional.ofNullable(series.get(seriesId)).filter(s -> s.familyId() == familyId);
    }

    @Override
    public List<TaskSeries> findActive(long familyId) {
        List<TaskSeries> found = new ArrayList<>();
        for (TaskSeries rule : series.values()) {
            if (rule.familyId() == familyId && !rule.isStopped()) {
                found.add(rule);
            }
        }
        return found;
    }

    @Override
    public List<TaskSeries> findActive() {
        return series.values().stream().filter(rule -> !rule.isStopped()).toList();
    }
}

package com.familytodo.application.port;

import com.familytodo.domain.TaskSeries;
import java.util.List;
import java.util.Optional;

/**
 * Порт хранилища серий.
 *
 * <p>Тот же инвариант, что и у {@link TaskRepository}: всё, что возвращает серии конкретной семьи,
 * принимает {@code familyId}. Исключение одно и оно явное — {@link #findActive()} для джобы
 * материализации: у неё нет «смотрящего», она обходит все семьи подряд.
 */
public interface TaskSeriesRepository {

    long nextId();

    TaskSeries save(TaskSeries series);

    Optional<TaskSeries> findById(long familyId, long seriesId);

    List<TaskSeries> findActive(long familyId);

    /** Системная выборка: все неостановленные серии всех семей. Только для джобы. */
    List<TaskSeries> findActive();
}

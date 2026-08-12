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
 *
 * <p>Видимость внутри семьи передаётся вторым аргументом и означает то же, что {@code
 * visibleToMemberId} у {@link com.familytodo.application.TaskQuery}: для родителя {@code null} —
 * видно всё, для ребёнка его номер — видны только правила, где он исполнитель или автор. Фильтр
 * обязан выполняться в SQL: отсев в памяти означал бы, что чужие строки уже прочитаны.
 */
public interface TaskSeriesRepository {

    long nextId();

    TaskSeries save(TaskSeries series);

    Optional<TaskSeries> findById(long familyId, Long visibleToMemberId, long seriesId);

    List<TaskSeries> findActive(long familyId, Long visibleToMemberId);

    /** Системная выборка: все неостановленные серии всех семей. Только для джобы. */
    List<TaskSeries> findActive();
}

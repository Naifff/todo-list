package com.familytodo.application.port;

import com.familytodo.application.TaskQuery;
import com.familytodo.domain.Task;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Порт хранилища задач.
 *
 * <p>Инвариант интерфейса: <b>каждый метод, возвращающий задачи, принимает {@code familyId}</b> —
 * прямо или внутри {@link TaskQuery}. Именно поэтому {@code family_id} продублирован в таблице
 * задач: изоляция между семьями держится формой этого интерфейса, а не аккуратностью вызывающего.
 */
public interface TaskRepository {

    long nextId();

    Task save(Task task);

    Optional<Task> findById(long familyId, long taskId);

    List<Task> find(TaskQuery query);

    void delete(long familyId, long taskId);

    /**
     * Записать вхождение серии.
     *
     * <p>Отдельным методом, а не флагом у {@link #save}: у вхождения есть то, чего нет у обычной
     * задачи, — серия и локальная дата вхождения. Дата хранится, а не выводится из {@code
     * starts_at}: смена таймзоны семьи не должна задним числом переносить уже созданные дела в
     * соседний день.
     */
    void saveOccurrence(Task task, long seriesId, LocalDate occurrenceOn);

    /** Даты, для которых вхождения уже созданы: по ним джоба понимает, чего не хватает. */
    Set<LocalDate> occurrenceDates(long familyId, long seriesId, LocalDate from, LocalDate to);

    /**
     * Удалить ещё не случившиеся открытые вхождения серии.
     *
     * <p>Закрытые не трогает: {@code DONE} и {@code DECLINED} — это история семьи, и остановка
     * правила не повод её стирать.
     *
     * @return сколько удалено
     */
    int deleteOpenOccurrencesFrom(long familyId, long seriesId, LocalDate from);
}

package com.familytodo.application.port;

import com.familytodo.application.TaskQuery;
import com.familytodo.domain.Task;
import java.util.List;
import java.util.Optional;

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
}

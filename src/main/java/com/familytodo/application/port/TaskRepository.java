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
     * <p>Отдельным методом, а не флагом у {@link #save}: вставка идёт с {@code do nothing} по паре
     * (серия, дата), и исполнители переписываются только у действительно новой строки. Серию и дату
     * вхождения несёт сама задача — дата хранится, а не выводится из {@code starts_at}: смена
     * таймзоны семьи не должна задним числом переносить уже созданные дела в соседний день.
     */
    void saveOccurrence(Task task);

    /** Даты, для которых вхождения уже созданы: по ним джоба понимает, чего не хватает. */
    Set<LocalDate> occurrenceDates(long familyId, long seriesId, LocalDate from, LocalDate to);

    /**
     * Даты, вхождения которых <b>удалили руками</b>: «в эту пятницу тренировки не будет».
     *
     * <p>Рядом с {@link #occurrenceDates}, потому что отвечают они на один вопрос джобы — какие дни
     * не создавать. Разница в причине: одни уже созданы, другие созданы не должны быть.
     *
     * <p>⚠️ Пропуск записывает сам {@link #delete}, в одной транзакции с удалением строки. В
     * юзкейсе это зависело бы от памяти вызывающего, а забыть его — значит вернуть ровно ту ошибку,
     * ради которой пропуски и заведены: удалённое вхождение приходит обратно в течение часа.
     */
    Set<LocalDate> skippedDates(long familyId, long seriesId, LocalDate from, LocalDate to);

    /**
     * Удалить ещё не случившиеся открытые вхождения серии.
     *
     * <p>Закрытые не трогает: {@code DONE} и {@code DECLINED} — это история семьи, и остановка
     * правила не повод её стирать.
     *
     * <p>⚠️ Пропусков, в отличие от {@link #delete}, <b>не записывает</b>, и это не упущение.
     * Удаление вхождения человеком означает «этот день отменяю», а здесь дела убирает само правило:
     * снятая верхняя граница обязана вернуть их обратно. Записанные пропуски сделали бы такой
     * возврат невозможным.
     *
     * @return сколько удалено
     */
    int deleteOpenOccurrencesFrom(long familyId, long seriesId, LocalDate from);
}

package com.familytodo.application.port;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/**
 * Выборка задач, у которых наступил срок, и отметка о напоминании.
 *
 * <p>Разделено на два шага намеренно, вопреки исходному плану с одним {@code UPDATE ... RETURNING}.
 * Причина: попадает ли момент в тихие часы, зависит от таймзоны <b>семьи</b>, и решение об этом
 * нельзя выразить в одном запросе поверх всех семей сразу. Атомарность здесь и не нужна — процесс
 * один, планировщик однопоточный; важен только порядок «пометить → закоммитить → отправить», и он
 * сохраняется.
 */
public interface ReminderRepository {

    /**
     * @param taskId кого напоминать — определяется по задаче
     * @param zone таймзона семьи: по ней считаются тихие часы
     */
    record DueReminder(long taskId, long familyId, Instant dueAt, ZoneId zone) {}

    /** Открытые задачи со сроком не позже {@code now}, которым ещё не напоминали. */
    List<DueReminder> findDue(Instant now, int limit);

    /** Отметка идёт до отправки: откат после успешной доставки дал бы дубль на следующем тике. */
    void markReminded(Collection<Long> taskIds, Instant now);
}

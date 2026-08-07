package com.familytodo.application.port;

import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.ZoneId;
import java.util.List;

/**
 * Порт доставки уведомлений. Реализация решает, куда и как писать; юзкейс — только кому и о чём.
 *
 * <p>Кого уведомлять, решает вызывающий: сам порт никого не фильтрует, кроме недостижимых
 * получателей.
 */
public interface Notifier {

    /** Назначили задачу — карточка исполнителю. */
    void taskAssigned(Member recipient, Task task);

    /** Закрыли — автору. */
    void taskCompleted(Member recipient, Task task, Member by);

    /** Отказались — автору, с причиной. */
    void taskDeclined(Member recipient, Task task, Member by, String reason);

    /** Вернули в работу. */
    void taskReopened(Member recipient, Task task, Member by);

    /** Закрыто системой: исполнителя исключили из семьи. */
    void taskCancelled(Member recipient, Task task, String reason);

    /** Задачу переназначили на другого — прежнему исполнителю. */
    void taskUnassigned(Member recipient, Task task);

    /** Наступил срок. */
    void taskDue(Member recipient, Task task);

    /**
     * Утренний список. Считается на каждого отдельно: родитель видит дела всей семьи, ребёнок —
     * только свои, поэтому одним сообщением на семью тут не обойтись.
     *
     * @param family состав семьи — нужен, чтобы подписать, кто кого просил
     */
    void digest(
            Member recipient,
            List<Task> tasks,
            List<Member> family,
            ZoneId zone,
            int horizonDays);
}

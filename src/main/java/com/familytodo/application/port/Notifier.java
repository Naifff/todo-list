package com.familytodo.application.port;

import com.familytodo.domain.Member;
import com.familytodo.domain.Task;

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
}

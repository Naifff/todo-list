package com.familytodo.application;

import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * Описание выборки задач. Видимость — не свойство задачи, а условие запроса: фильтр должен
 * выполняться в SQL, иначе чужие строки уже прочитаны, и остаётся надеяться, что их не покажут.
 *
 * @param familyId обязателен всегда — граница между семьями
 * @param visibleToMemberId для CHILD — его id; задача попадает в выборку, только если он её
 *     исполнитель или автор. Для PARENT {@code null}: родитель видит весь список семьи
 * @param assigneeId ограничение по исполнителю ({@code /my})
 * @param creatorId ограничение по автору ({@code /assigned})
 */
public record TaskQuery(
        long familyId,
        Long visibleToMemberId,
        Long assigneeId,
        Long creatorId,
        Set<TaskStatus> statuses) {

    private static final Set<TaskStatus> OPEN_ONLY = EnumSet.of(TaskStatus.OPEN);

    public TaskQuery {
        statuses = Set.copyOf(statuses);
    }

    /** Всё, что участнику вообще положено видеть в своей семье. */
    public static TaskQuery visibleTo(Member viewer) {
        return new TaskQuery(viewer.familyId(), visibilityLimit(viewer), null, null, OPEN_ONLY);
    }

    /** {@code /my} — что просили у меня. */
    public static TaskQuery assignedTo(Member viewer) {
        return new TaskQuery(
                viewer.familyId(), visibilityLimit(viewer), viewer.id(), null, OPEN_ONLY);
    }

    /** {@code /assigned} — что я попросил у других. */
    public static TaskQuery createdBy(Member viewer) {
        return new TaskQuery(
                viewer.familyId(), visibilityLimit(viewer), null, viewer.id(), OPEN_ONLY);
    }

    /**
     * Системная выборка: открытые задачи участника независимо от того, кто спрашивает. Нужна при
     * исключении из семьи — там «смотрящего» нет вовсе.
     */
    public static TaskQuery openAssignedTo(long familyId, long memberId) {
        return new TaskQuery(familyId, null, memberId, null, OPEN_ONLY);
    }

    public TaskQuery withStatuses(Set<TaskStatus> newStatuses) {
        return new TaskQuery(familyId, visibleToMemberId, assigneeId, creatorId, newStatuses);
    }

    private static Long visibilityLimit(Member viewer) {
        return viewer.role() == Role.CHILD ? viewer.id() : null;
    }
}

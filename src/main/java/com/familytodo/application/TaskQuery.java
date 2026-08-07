package com.familytodo.application;

import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskStatus;
import java.time.Instant;
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
        Set<TaskStatus> statuses,
        Instant from,
        Instant to,
        boolean undatedOnly) {

    /** Прежняя форма без горизонта: подавляющее большинство выборок не про диапазон дат. */
    public TaskQuery(
            long familyId,
            Long visibleToMemberId,
            Long assigneeId,
            Long creatorId,
            Set<TaskStatus> statuses) {
        this(familyId, visibleToMemberId, assigneeId, creatorId, statuses, null, null, false);
    }

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
        return new TaskQuery(
                familyId, visibleToMemberId, assigneeId, creatorId, newStatuses, from, to,
                undatedOnly);
    }

    /**
     * Дела, попадающие в горизонт. Момент дела — начало интервала, а если его нет, то срок:
     * «отвезти детей 08:00» стоит в календаре по началу, «вынести мусор к 19:00» — по сроку.
     */
    public static TaskQuery inRange(Member viewer, Instant from, Instant to) {
        return new TaskQuery(
                viewer.familyId(),
                visibilityLimit(viewer),
                null,
                null,
                OPEN_ONLY,
                from,
                to,
                false);
    }

    /**
     * Дела без срока и без интервала.
     *
     * <p>Отдельной выборкой, а не приписанные к сегодня: «когда-нибудь разобрать шкаф» не обещано
     * на сегодня, и показывать его среди сегодняшних значило бы врать.
     */
    public static TaskQuery undated(Member viewer) {
        return new TaskQuery(
                viewer.familyId(), visibilityLimit(viewer), null, null, OPEN_ONLY, null, null, true);
    }

    private static Long visibilityLimit(Member viewer) {
        return viewer.role() == Role.CHILD ? viewer.id() : null;
    }
}

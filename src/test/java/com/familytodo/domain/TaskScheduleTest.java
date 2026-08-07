package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Интервал и место.
 *
 * <p>Срок и интервал — разные вещи и живут рядом: «вынести мусор к 19:00» это обещание закончить,
 * «отвезти детей 08:00–08:40» это занятое время. Тесты следят, чтобы одно не подменяло другое.
 */
class TaskScheduleTest {

    private static final long FAMILY = 1L;
    private static final long MOM = 10L;
    private static final long KID = 12L;

    private static final Instant CREATED = Instant.parse("2026-09-01T05:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-01T16:00:00Z");
    private static final Instant START = Instant.parse("2026-09-01T05:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T05:40:00Z");

    private static Actor mom() {
        return Actor.member(MOM, FAMILY, Role.PARENT);
    }

    private static Task task() {
        return Task.create(
                100L,
                FAMILY,
                "Отвезти детей в школу",
                MOM,
                new Assignee(KID, Role.CHILD),
                DUE,
                CREATED);
    }

    @Test
    void newTaskHasNoSchedule() {
        Task task = task();

        assertThat(task.isScheduled()).isFalse();
        assertThat(task.startsAt()).isNull();
        assertThat(task.endsAt()).isNull();
        assertThat(task.location()).isNull();
    }

    @Test
    void storesIntervalAndLocation() {
        Task task = task();

        task.schedule(mom(), START, END, "школа");

        assertThat(task.isScheduled()).isTrue();
        assertThat(task.startsAt()).isEqualTo(START);
        assertThat(task.endsAt()).isEqualTo(END);
        assertThat(task.location()).isEqualTo("школа");
    }

    /** Срок не должен исчезать при планировании: он управляет напоминаниями. */
    @Test
    void schedulingLeavesTheDeadlineAlone() {
        Task task = task();

        task.schedule(mom(), START, END, "школа");

        assertThat(task.dueAt()).isEqualTo(DUE);
    }

    /** «В 8 утра» без длительности встречается чаще, чем точный интервал. */
    @Test
    void openEndedStartIsAllowed() {
        Task task = task();

        task.schedule(mom(), START, null, null);

        assertThat(task.startsAt()).isEqualTo(START);
        assertThat(task.endsAt()).isNull();
    }

    @Test
    void locationWithoutIntervalIsAllowed() {
        Task task = task();

        task.schedule(mom(), null, null, "Zoom");

        assertThat(task.isScheduled()).isFalse();
        assertThat(task.location()).isEqualTo("Zoom");
    }

    @Test
    void endWithoutStartIsRejected() {
        Task task = task();

        assertThatThrownBy(() -> task.schedule(mom(), null, END, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endBeforeStartIsRejected() {
        Task task = task();

        assertThatThrownBy(() -> task.schedule(mom(), END, START, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLengthIntervalIsRejected() {
        Task task = task();

        assertThatThrownBy(() -> task.schedule(mom(), START, START, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLongLocationIsRejected() {
        Task task = task();

        assertThatThrownBy(
                        () ->
                                task.schedule(
                                        mom(),
                                        START,
                                        END,
                                        "ш".repeat(Task.MAX_LOCATION_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankLocationBecomesEmpty() {
        Task task = task();

        task.schedule(mom(), START, END, "   ");

        assertThat(task.location()).isNull();
    }

    @Test
    void scheduleCanBeCleared() {
        Task task = task();
        task.schedule(mom(), START, END, "школа");

        task.schedule(mom(), null, null, null);

        assertThat(task.isScheduled()).isFalse();
        assertThat(task.location()).isNull();
    }

    /** Право то же, что на правку: расписание задаёт тот, кто просил. */
    @Test
    void assigneeMayNotSchedule() {
        Task task = task();

        assertThatThrownBy(
                        () ->
                                task.schedule(
                                        Actor.member(KID, FAMILY, Role.CHILD), START, END, "школа"))
                .isInstanceOf(DomainException.NotPermitted.class);
    }

    @Test
    void parentMayScheduleAChildsTask() {
        Task task = task();

        assertThatCode(
                        () ->
                                task.schedule(
                                        Actor.member(11L, FAMILY, Role.PARENT), START, END, "школа"))
                .doesNotThrowAnyException();
    }

    @Test
    void strangerMayNotSchedule() {
        Task task = task();

        assertThatThrownBy(
                        () ->
                                task.schedule(
                                        Actor.member(MOM, 999L, Role.PARENT), START, END, "школа"))
                .isInstanceOf(DomainException.NotPermitted.class);
    }

    /** Планировать закрытое дело так же бессмысленно, как править его название. */
    @Test
    void closedTaskCannotBeScheduled() {
        Task task = task();
        task.complete(mom(), DUE);

        assertThatThrownBy(() -> task.schedule(mom(), START, END, "школа"))
                .isInstanceOf(DomainException.InvalidTransition.class);
    }
}

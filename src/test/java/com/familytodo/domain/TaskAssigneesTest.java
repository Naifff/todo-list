package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Дело, поручённое нескольким сразу.
 *
 * <p>Главное правило здесь несимметрично, и это решение, а не упущение: <b>«сделано» закрывает дело
 * всем, «отказ» снимает только с себя</b>. «Сделано» — факт о мире: к врачу сходили, и второму
 * родителю держать это в голове больше не нужно. Отказ — ответ на просьбу, а отвечает на неё каждый
 * адресат за себя; дело уходит в {@code DECLINED}, только когда отказались все.
 *
 * <p>Матрица прав при <b>одном</b> исполнителе живёт в {@link TaskTest} и обязана остаться
 * неизменной — она же и проверяет, что новая механика не поменяла старое поведение.
 */
class TaskAssigneesTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;

    private static final long MOM = 10L;
    private static final long DAD = 11L;
    private static final long KID = 12L;
    private static final long GRANNY = 14L;

    private static final Instant CREATED = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-11T16:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-11T13:00:00Z");

    private static Actor parent(long id) {
        return Actor.member(id, FAMILY, Role.PARENT);
    }

    private static Actor child(long id) {
        return Actor.member(id, FAMILY, Role.CHILD);
    }

    /** Ровно тот случай, ради которого всё затевалось: запись к врачу нужна обоим родителям. */
    private static Task doctorForBothParents() {
        return Task.create(
                100L,
                FAMILY,
                "Отвезти ребёнка к врачу",
                MOM,
                List.of(new Assignee(MOM, Role.PARENT), new Assignee(DAD, Role.PARENT)),
                DUE,
                CREATED);
    }

    @Nested
    class Creating {

        @Test
        void everyoneNamedBecomesAnAssignee() {
            Task task = doctorForBothParents();

            assertThat(task.assignments()).extracting(Assignment::memberId).containsExactly(MOM, DAD);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.assignments()).noneMatch(Assignment::hasDeclined);
        }

        @Test
        void theSamePersonNamedTwiceIsStillOneAssignee() {
            Task task =
                    Task.create(
                            101L,
                            FAMILY,
                            "Вынести мусор",
                            MOM,
                            List.of(new Assignee(DAD, Role.PARENT), new Assignee(DAD, Role.PARENT)),
                            DUE,
                            CREATED);

            assertThat(task.assignments()).hasSize(1);
        }

        @Test
        void aTaskWithoutAnyAssigneeIsRejected() {
            assertThatThrownBy(
                            () ->
                                    Task.create(
                                            102L, FAMILY, "Ничьё", MOM, List.of(), DUE, CREATED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** Дело только на себя — когда исполнитель один и это автор. Вдвоём с кем-то уже не «себе». */
        @Test
        void aTaskSharedWithSomeoneElseIsNotSelfAssigned() {
            assertThat(doctorForBothParents().isSelfAssigned()).isFalse();
        }
    }

    @Nested
    class Declining {

        @Test
        void oneRefusalLeavesTheTaskOpenForTheOthers() {
            Task task = doctorForBothParents();

            task.decline(parent(DAD), "я на работе до восьми", NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.closedAt()).isNull();
            assertThat(task.isDeclinedBy(DAD)).isTrue();
            assertThat(task.isDeclinedBy(MOM)).isFalse();
        }

        @Test
        void theLastRefusalClosesTheTaskAndKeepsEveryReason() {
            Task task = doctorForBothParents();

            task.decline(parent(DAD), "я на работе до восьми", NOW);
            task.decline(parent(MOM), "я в командировке", LATER);

            assertThat(task.status()).isEqualTo(TaskStatus.DECLINED);
            assertThat(task.closedAt()).isEqualTo(LATER);
            assertThat(task.declineReasonOf(DAD)).contains("я на работе до восьми");
            assertThat(task.declineReasonOf(MOM)).contains("я в командировке");
        }

        @Test
        void refusingTwiceIsRejected() {
            Task task = doctorForBothParents();
            task.decline(parent(DAD), "занят", NOW);

            assertThatThrownBy(() -> task.decline(parent(DAD), "всё ещё занят", LATER))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }

        @Test
        void someoneWhoIsNotAnAssigneeMayNotRefuse() {
            Task task = doctorForBothParents();

            assertThatThrownBy(() -> task.decline(child(KID), "не хочу", NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Отказавшийся уже ответил: кнопки «Готово» у него больше нет. */
        @Test
        void theOneWhoRefusedMayNoLongerCompleteAsAnAssignee() {
            Task task =
                    Task.create(
                            103L,
                            FAMILY,
                            "Убрать комнату",
                            MOM,
                            List.of(new Assignee(KID, Role.CHILD), new Assignee(DAD, Role.PARENT)),
                            DUE,
                            CREATED);
            task.decline(child(KID), "уроки", NOW);

            assertThat(task.mayComplete(child(KID))).isFalse();
            assertThatThrownBy(() -> task.complete(child(KID), LATER))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** А вот родитель, который отказался, всё ещё родитель — право закрыть у него не отсюда. */
        @Test
        void aParentWhoRefusedStillMayCloseTheTaskAsAParent() {
            Task task = doctorForBothParents();
            task.decline(parent(DAD), "занят", NOW);

            assertThatCode(() -> task.complete(parent(DAD), LATER)).doesNotThrowAnyException();
            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        }
    }

    @Nested
    class Completing {

        @Test
        void oneAssigneeClosesItForEveryone() {
            Task task = doctorForBothParents();

            task.complete(parent(MOM), NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
            assertThat(task.closedAt()).isEqualTo(NOW);
        }

        @Test
        void completingAfterOneRefusalStillClosesTheWholeTask() {
            Task task = doctorForBothParents();
            task.decline(parent(DAD), "занят", NOW);

            task.complete(parent(MOM), LATER);

            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        }
    }

    @Nested
    class Reopening {

        @Test
        void reopeningClearsEveryRefusal() {
            Task task = doctorForBothParents();
            task.decline(parent(DAD), "занят", NOW);
            task.decline(parent(MOM), "в командировке", LATER);

            task.reopen(parent(MOM));

            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.closedAt()).isNull();
            assertThat(task.assignments()).noneMatch(Assignment::hasDeclined);
        }
    }

    @Nested
    class ChangingWhoDoesIt {

        @Test
        void theCreatorMayAddSomeoneElse() {
            Task task =
                    Task.create(
                            104L,
                            FAMILY,
                            "Отвезти к врачу",
                            MOM,
                            List.of(new Assignee(MOM, Role.PARENT)),
                            DUE,
                            CREATED);

            task.assign(parent(MOM), new Assignee(DAD, Role.PARENT));

            assertThat(task.assignments()).extracting(Assignment::memberId).containsExactly(MOM, DAD);
        }

        @Test
        void addingSomeoneAlreadyOnItChangesNothing() {
            Task task = doctorForBothParents();

            task.assign(parent(MOM), new Assignee(DAD, Role.PARENT));

            assertThat(task.assignments()).hasSize(2);
        }

        @Test
        void theCreatorMayTakeSomeoneOff() {
            Task task = doctorForBothParents();

            task.unassign(parent(MOM), DAD);

            assertThat(task.assignments()).extracting(Assignment::memberId).containsExactly(MOM);
        }

        /** Дело без исполнителя не просьба, а запись в никуда. */
        @Test
        void theLastAssigneeMayNotBeTakenOff() {
            Task task =
                    Task.create(
                            105L,
                            FAMILY,
                            "Вынести мусор",
                            MOM,
                            List.of(new Assignee(KID, Role.CHILD)),
                            DUE,
                            CREATED);

            assertThatThrownBy(() -> task.unassign(parent(MOM), KID))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }

        /** ⚠️ Родитель распоряжается кругом исполнителей в любом деле семьи, а ребёнок — нет. */
        @Test
        void aChildMayNotChangeWhoDoesSomeoneElsesTask() {
            Task task = doctorForBothParents();
            Actor child = Actor.member(KID, FAMILY, Role.CHILD);

            assertThatThrownBy(() -> task.assign(child, new Assignee(KID, Role.CHILD)))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThatThrownBy(() -> task.unassign(child, DAD))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void anActorFromAnotherFamilyMayNotChangeWhoDoesIt() {
            Task task = doctorForBothParents();
            Actor stranger = Actor.member(MOM, OTHER_FAMILY, Role.PARENT);

            assertThatThrownBy(() -> task.assign(stranger, new Assignee(KID, Role.CHILD)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class ParentRights {

        /**
         * Право родителя на правку расширяется до «хоть один исполнитель — ребёнок». Правило
         * защищает детей от того, что их поручениями распоряжается кто попало, и присутствие
         * ребёнка среди исполнителей этот повод создаёт целиком.
         */
        @Test
        void aParentMayModifyWhenAtLeastOneAssigneeIsAChild() {
            Task task =
                    Task.create(
                            106L,
                            FAMILY,
                            "Собрать вещи",
                            MOM,
                            List.of(new Assignee(DAD, Role.PARENT), new Assignee(KID, Role.CHILD)),
                            DUE,
                            CREATED);

            assertThat(task.mayModify(parent(GRANNY))).isTrue();
        }

        /**
         * ⚠️ Было наоборот: родитель правил только дела с ребёнком-исполнителем. Изменено 16
         * августа живьём — исполнитель-взрослый не мог перенести дело, которое сам же и делает.
         */
        @Test
        void aParentMayModifyEvenWhenEveryAssigneeIsAnAdult() {
            assertThat(doctorForBothParents().mayModify(parent(GRANNY))).isTrue();
        }
    }
}

package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Матрица ролей × переходов. Основная страховка проекта: десятки кейсов без поднятия Spring.
 *
 * <p>Если для проверки правила прав понадобился контекст — правило утекло не в тот слой.
 */
class TaskTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;

    private static final long MOM = 10L;
    private static final long DAD = 11L;
    private static final long KID = 12L;
    private static final long OTHER_KID = 13L;
    private static final long GRANNY = 14L;

    private static final Instant CREATED = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-07T16:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    private static Actor parent(long id) {
        return Actor.member(id, FAMILY, Role.PARENT);
    }

    private static Actor child(long id) {
        return Actor.member(id, FAMILY, Role.CHILD);
    }

    /** Мама попросила ребёнка вынести мусор — базовая задача для большинства кейсов. */
    private static Task momAsksKid() {
        return Task.create(
                100L, FAMILY, "Вынести мусор", MOM, new Assignee(KID, Role.CHILD), DUE, CREATED);
    }

    /** Папа попросил маму — обе стороны PARENT, самый тонкий случай матрицы. */
    private static Task dadAsksMom() {
        return Task.create(
                101L, FAMILY, "Забрать посылку", DAD, new Assignee(MOM, Role.PARENT), DUE, CREATED);
    }

    @Test
    void newTaskIsOpen() {
        Task task = momAsksKid();

        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.closedAt()).isNull();
        assertThat(task.assignments()).noneMatch(Assignment::hasDeclined);
    }

    @Nested
    class Complete {

        @Test
        void allowedForAssignee() {
            Task task = momAsksKid();

            task.complete(child(KID), NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
            assertThat(task.closedAt()).isEqualTo(NOW);
        }

        @Test
        void allowedForCreator() {
            Task task = momAsksKid();

            task.complete(parent(MOM), NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        }

        /** Папа не автор и не исполнитель, но родитель — закрыть может. */
        @Test
        void allowedForAnyParent() {
            Task task = momAsksKid();

            task.complete(parent(DAD), NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        }

        @Test
        void deniedForUnrelatedChild() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.complete(child(OTHER_KID), NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void repeatedCompleteReportsCurrentStatus() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.complete(child(KID), NOW))
                    .isInstanceOfSatisfying(
                            DomainException.InvalidTransition.class,
                            e -> assertThat(e.currentStatus()).isEqualTo(TaskStatus.DONE));
        }

        /** Права проверяются раньше состояния: постороннему нельзя узнать даже статус. */
        @Test
        void permissionIsCheckedBeforeState() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.complete(child(OTHER_KID), NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Decline {

        @Test
        void allowedOnlyForAssignee() {
            Task task = momAsksKid();

            task.decline(child(KID), "я на тренировке", NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DECLINED);
            assertThat(task.declineReasonOf(KID)).contains("я на тренировке");
            assertThat(task.closedAt()).isEqualTo(NOW);
        }

        @Test
        void deniedForCreator() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.decline(parent(MOM), "передумала", NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void deniedForOtherParent() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.decline(parent(DAD), "не надо", NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void requiresReason() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.decline(child(KID), "   ", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        }
    }

    @Nested
    class Reopen {

        @Test
        void allowedForAssigneeAfterDone() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            task.reopen(child(KID));

            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.closedAt()).isNull();
        }

        @Test
        void allowedForCreatorAfterDecline() {
            Task task = momAsksKid();
            task.decline(child(KID), "я на тренировке", NOW);

            task.reopen(parent(MOM));

            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.assignments()).noneMatch(Assignment::hasDeclined);
        }

        /** Переоткрытие — не то же, что закрытие: постороннему родителю оно недоступно. */
        @Test
        void deniedForUninvolvedParent() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.reopen(parent(DAD)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedWhenAlreadyOpen() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.reopen(child(KID)))
                    .isInstanceOfSatisfying(
                            DomainException.InvalidTransition.class,
                            e -> assertThat(e.currentStatus()).isEqualTo(TaskStatus.OPEN));
        }
    }

    @Nested
    class Edit {

        @Test
        void allowedForCreator() {
            Task task = momAsksKid();

            task.edit(parent(MOM), "Вынести мусор и бумагу", DUE.plusSeconds(3600));

            assertThat(task.title()).isEqualTo("Вынести мусор и бумагу");
            assertThat(task.dueAt()).isEqualTo(DUE.plusSeconds(3600));
        }

        /** Ребёнок вправе править то, что создал сам, — включая задачу родителю. */
        @Test
        void allowedForChildCreator() {
            Task task =
                    Task.create(
                            102L,
                            FAMILY,
                            "Купить корм коту",
                            KID,
                            new Assignee(MOM, Role.PARENT),
                            DUE,
                            CREATED);

            task.edit(child(KID), "Купить корм и наполнитель", DUE);

            assertThat(task.title()).isEqualTo("Купить корм и наполнитель");
        }

        @Test
        void allowedForParentWhenAssigneeIsChild() {
            Task task = momAsksKid();

            task.edit(parent(DAD), "Вынести мусор до обеда", DUE);

            assertThat(task.title()).isEqualTo("Вынести мусор до обеда");
        }

        /** Самое тонкое правило матрицы: родитель не старше другого родителя. */
        @Test
        void deniedForParentOverAnotherParentsAssignment() {
            Task task = dadAsksMom();

            assertThatThrownBy(() -> task.edit(parent(GRANNY), "не моё дело", DUE))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.title()).isEqualTo("Забрать посылку");
        }

        @Test
        void deniedForAssigneeWhoIsNotCreator() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.edit(child(KID), "вынести завтра", DUE))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Правка закрытой задачи бессмысленна: срок и название уже ни на что не влияют. */
        @Test
        void deniedWhenTaskIsClosed() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.edit(parent(MOM), "новое название", DUE))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }

        @Test
        void rejectsBlankTitle() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.edit(parent(MOM), "  ", DUE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsTitleLongerThanSchemaAllows() {
            Task task = momAsksKid();
            String tooLong = "я".repeat(201);

            assertThatThrownBy(() -> task.edit(parent(MOM), tooLong, DUE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allowsClearingDueDate() {
            Task task = momAsksKid();

            task.edit(parent(MOM), "Вынести мусор", null);

            assertThat(task.dueAt()).isNull();
        }
    }

    @Nested
    class Delete {

        @Test
        void allowedForCreator() {
            Task task = momAsksKid();

            assertThatCode(() -> task.assertDeletableBy(parent(MOM))).doesNotThrowAnyException();
        }

        @Test
        void allowedForParentWhenAssigneeIsChild() {
            Task task = momAsksKid();

            assertThatCode(() -> task.assertDeletableBy(parent(DAD))).doesNotThrowAnyException();
        }

        @Test
        void deniedForParentOverAnotherParentsAssignment() {
            Task task = dadAsksMom();

            assertThatThrownBy(() -> task.assertDeletableBy(parent(GRANNY)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedForAssigneeWhoIsNotCreator() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.assertDeletableBy(child(KID)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Закрытую задачу удалить можно: «создал по ошибке» вскрывается и после закрытия. */
        @Test
        void allowedForClosedTask() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatCode(() -> task.assertDeletableBy(parent(MOM))).doesNotThrowAnyException();
        }
    }

    @Nested
    class CancelBySystem {

        @Test
        void systemActorClosesTaskOfRemovedMember() {
            Task task = momAsksKid();

            task.cancelBySystem(Actor.system(), "участник исключён из семьи", NOW);

            assertThat(task.status()).isEqualTo(TaskStatus.DECLINED);
            assertThat(task.declineReasonOf(KID)).contains("участник исключён из семьи");
            assertThat(task.closedAt()).isEqualTo(NOW);
        }

        /** Иначе любой участник обошёл бы правило «decline только исполнителю». */
        @Test
        void deniedForOrdinaryMember() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.cancelBySystem(parent(MOM), "мне так удобнее", NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void deniedForAlreadyClosedTask() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.cancelBySystem(Actor.system(), "исключён", NOW))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }
    }

    /**
     * Защита в глубину. Изоляцию по семьям держит фильтр в SQL, но подделанный {@code
     * callback_data} с чужим id не должен пройти и на уровне домена.
     */
    @Nested
    class CrossFamily {

        private final Actor stranger = Actor.member(MOM, OTHER_FAMILY, Role.PARENT);

        @Test
        void completeIsDenied() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.complete(stranger, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void declineIsDenied() {
            Task task =
                    Task.create(
                            103L,
                            FAMILY,
                            "Купить хлеб",
                            MOM,
                            new Assignee(MOM, Role.PARENT),
                            DUE,
                            CREATED);

            assertThatThrownBy(() -> task.decline(stranger, "не хочу", NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void reopenIsDenied() {
            Task task = momAsksKid();
            task.complete(child(KID), NOW);

            assertThatThrownBy(() -> task.reopen(stranger))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
        }

        @Test
        void editIsDenied() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.edit(stranger, "чужое", DUE))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deleteIsDenied() {
            Task task = momAsksKid();

            assertThatThrownBy(() -> task.assertDeletableBy(stranger))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }
}

package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TaskServiceTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-07T16:00:00Z");

    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final TaskService service = new TaskService(tasks, members, notifier, clock);

    private Member mom;
    private Member dad;
    private Member kid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        mom = members.save(Member.join(10L, FAMILY, 100L, 100L, "Мама", Role.PARENT, NOW));
        dad = members.save(Member.join(11L, FAMILY, 101L, 101L, "Папа", Role.PARENT, NOW));
        kid = members.save(Member.join(12L, FAMILY, 102L, 102L, "Петя", Role.CHILD, NOW));
        stranger =
                members.save(
                        Member.join(90L, OTHER_FAMILY, 900L, 900L, "Чужой", Role.PARENT, NOW));
    }

    @Nested
    class Create {

        @Test
        void assignsTaskAndNotifiesAssignee() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(task.assignments().getFirst().memberId()).isEqualTo(kid.id());
            assertThat(task.creatorId()).isEqualTo(mom.id());
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.ASSIGNED, kid.id()));
        }

        /** Ребёнок вправе попросить родителя — это заявленное свойство продукта, а не поблажка. */
        @Test
        void childMayAssignToParent() {
            Task task = service.create(kid, mom.id(), "Купить корм коту", DUE);

            assertThat(task.assignments().getFirst().memberId()).isEqualTo(mom.id());
            assertThat(task.assignments().getFirst().role()).isEqualTo(Role.PARENT);
            assertThat(notifier.sent()).hasSize(1);
        }

        /** Себе — значит уже знаешь: уведомление было бы шумом. */
        @Test
        void selfAssignedTaskProducesNoNotifications() {
            Task task = service.create(mom, mom.id(), "Позвонить в поликлинику", DUE);

            assertThat(task.isSelfAssigned()).isTrue();
            assertThat(notifier.sent()).isEmpty();
        }

        @Test
        void assigneeFromAnotherFamilyIsNotFound() {
            assertThatThrownBy(() -> service.create(mom, stranger.id(), "Чужое дело", DUE))
                    .isInstanceOf(DomainException.NotFound.class);
            assertThat(notifier.sent()).isEmpty();
        }

        @Test
        void removedMemberCannotBeAssigned() {
            Member removed =
                    members.save(Member.join(13L, FAMILY, 103L, 103L, "Бабушка", Role.CHILD, NOW));
            removeFromFamily(removed);

            assertThatThrownBy(() -> service.create(mom, removed.id(), "Полить цветы", DUE))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void removedMemberCannotCreate() {
            removeFromFamily(kid);

            assertThatThrownBy(() -> service.create(kid, mom.id(), "Что-нибудь", DUE))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void taskWithoutDueDateIsAllowed() {
            Task task = service.create(mom, kid.id(), "Разобрать шкаф", null);

            assertThat(task.dueAt()).isNull();
        }
    }

    @Nested
    class Complete {

        @Test
        void notifiesCreatorNotAssignee() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            service.complete(kid, task.id());

            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.COMPLETED, mom.id()));
        }

        @Test
        void storesClosedAtFromClock() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            Task closed = service.complete(kid, task.id());

            assertThat(closed.status()).isEqualTo(TaskStatus.DONE);
            assertThat(closed.closedAt()).isEqualTo(NOW);
        }

        /**
         * Автор закрыл дело сам — узнать об этом должен исполнитель.
         *
         * <p>⚠️ Это изменение прежнего поведения, а не его уточнение. Раньше закрытие сообщалось
         * только автору, и мама, вынесшая мусор вместо ребёнка, оставляла дело висеть у него в
         * {@code /my} без единого слова. Правило стало общим: о закрытии узнают все причастные,
         * кроме нажавшего. Особый случай «исполнителю не сообщать, если он один» был бы
         * несвязным — с несколькими исполнителями сообщать приходится обязательно.
         */
        @Test
        void creatorClosingOwnRequestTellsTheAssignee() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            service.complete(mom, task.id());

            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.COMPLETED, kid.id()));
        }

        @Test
        void selfAssignedTaskProducesNoNotifications() {
            Task task = service.create(mom, mom.id(), "Позвонить в поликлинику", DUE);

            service.complete(mom, task.id());

            assertThat(notifier.sent()).isEmpty();
        }

        @Test
        void unknownTaskIsNotFound() {
            assertThatThrownBy(() -> service.complete(mom, 999L))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        /**
         * Ключевой тест изоляции на уровне юзкейса: чужая задача не находится вовсе, потому что
         * {@code familyId} — обязательный параметр выборки, а не условие в коде сервиса.
         */
        @Test
        void taskOfAnotherFamilyIsNotFound() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(() -> service.complete(stranger, task.id()))
                    .isInstanceOf(DomainException.NotFound.class);
            assertThat(tasks.findById(FAMILY, task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }
    }

    @Nested
    class Decline {

        @Test
        void notifiesCreatorWithReason() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            service.decline(kid, task.id(), "я на тренировке");

            assertThat(notifier.sent())
                    .extracting(
                            FakeNotifier.Sent::kind,
                            FakeNotifier.Sent::recipientId,
                            FakeNotifier.Sent::detail)
                    .containsExactly(tuple(Kind.DECLINED, mom.id(), "я на тренировке"));
        }

        @Test
        void creatorCannotDecline() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(() -> service.decline(mom, task.id(), "передумала"))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Reopen {

        @Test
        void returnsTaskToAssigneeAndNotifiesThem() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.complete(kid, task.id());
            notifier.clear();

            Task reopened = service.reopen(mom, task.id());

            assertThat(reopened.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::kind, FakeNotifier.Sent::recipientId)
                    .containsExactly(tuple(Kind.REOPENED, kid.id()));
        }

        @Test
        void assigneeReopeningOwnTaskNotifiesCreator() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.complete(kid, task.id());
            notifier.clear();

            service.reopen(kid, task.id());

            assertThat(notifier.sent())
                    .extracting(FakeNotifier.Sent::recipientId)
                    .containsExactly(mom.id());
        }
    }

    @Nested
    class EditAndDelete {

        @Test
        void creatorEditsTitleAndDueDate() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            Task edited = service.edit(mom, task.id(), "Вынести мусор и бумагу", null);

            assertThat(edited.title()).isEqualTo("Вынести мусор и бумагу");
            assertThat(edited.dueAt()).isNull();
        }

        @Test
        void assigneeCannotEdit() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(() -> service.edit(kid, task.id(), "завтра", DUE))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void creatorDeletesTask() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            service.delete(mom, task.id());

            assertThat(tasks.findById(FAMILY, task.id())).isEmpty();
        }

        @Test
        void deletingTaskOfAnotherFamilyIsNotFound() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThatThrownBy(() -> service.delete(stranger, task.id()))
                    .isInstanceOf(DomainException.NotFound.class);
            assertThat(tasks.findById(FAMILY, task.id())).isPresent();
        }
    }

    @Nested
    class Visibility {

        @Test
        void parentSeesEveryOpenTaskOfTheFamily() {
            service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.create(dad, mom.id(), "Забрать посылку", DUE);
            service.create(kid, kid.id(), "Сделать уроки", DUE);

            List<Task> visible = service.find(TaskQuery.visibleTo(dad));

            assertThat(visible).hasSize(3);
        }

        /** Ребёнок видит только своё — и как исполнитель, и как автор, но не чужое между родителями. */
        @Test
        void childSeesOnlyOwnTasks() {
            Task forKid = service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.create(dad, mom.id(), "Забрать посылку", DUE);
            Task byKid = service.create(kid, dad.id(), "Починить велосипед", DUE);

            List<Task> visible = service.find(TaskQuery.visibleTo(kid));

            assertThat(visible).extracting(Task::id).containsExactly(forKid.id(), byKid.id());
        }

        /** Ограничение ребёнка задаётся в самом запросе, а не отсевом результата после выборки. */
        @Test
        void childRestrictionIsPartOfTheQuery() {
            assertThat(TaskQuery.visibleTo(kid).visibleToMemberId()).isEqualTo(kid.id());
            assertThat(TaskQuery.visibleTo(dad).visibleToMemberId()).isNull();
        }

        @Test
        void myTasksReturnsOnlyWhereMemberIsAssignee() {
            Task forKid = service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.create(kid, dad.id(), "Починить велосипед", DUE);

            List<Task> mine = service.find(TaskQuery.assignedTo(kid));

            assertThat(mine).extracting(Task::id).containsExactly(forKid.id());
        }

        @Test
        void requestedTasksReturnsOnlyWhereMemberIsCreator() {
            service.create(mom, kid.id(), "Вынести мусор", DUE);
            Task byKid = service.create(kid, dad.id(), "Починить велосипед", DUE);

            List<Task> requested = service.find(TaskQuery.createdBy(kid));

            assertThat(requested).extracting(Task::id).containsExactly(byKid.id());
        }

        @Test
        void closedTasksAreOutOfOpenLists() {
            Task task = service.create(mom, kid.id(), "Вынести мусор", DUE);
            service.complete(kid, task.id());

            assertThat(service.find(TaskQuery.assignedTo(kid))).isEmpty();
        }

        @Test
        void listsNeverCrossFamilyBoundary() {
            service.create(mom, kid.id(), "Вынести мусор", DUE);

            assertThat(service.find(TaskQuery.visibleTo(stranger))).isEmpty();
        }
    }

    private void removeFromFamily(Member member) {
        Family family = Family.create(FAMILY, "Ивановы", ZoneOffset.UTC, NOW);
        family.removeMember(mom.asActor(), member, members.findActive(FAMILY));
    }
}

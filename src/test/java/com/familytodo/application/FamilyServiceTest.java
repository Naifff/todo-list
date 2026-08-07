package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.FakeNotifier.Kind;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FamilyServiceTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final Instant DUE = Instant.parse("2026-08-07T16:00:00Z");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryTaskRepository tasks = new InMemoryTaskRepository();
    private final FakeNotifier notifier = new FakeNotifier();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FamilyService service =
            new FamilyService(families, members, tasks, notifier, clock);
    private final TaskService taskService = new TaskService(tasks, members, notifier, clock);

    private Member founder() {
        return service.createFamily(100000001L, 100000001L, "Мама", "Ивановы", MOSCOW);
    }

    private Member join(Member founder, String name, Role role) {
        Member member =
                Member.join(
                        members.nextId(),
                        founder.familyId(),
                        members.nextId() + 1000L,
                        members.nextId() + 2000L,
                        name,
                        role,
                        NOW);
        return members.save(member);
    }

    @Nested
    class CreateFamily {

        @Test
        void founderBecomesParent() {
            Member founder = founder();

            assertThat(founder.role()).isEqualTo(Role.PARENT);
            assertThat(founder.status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(founder.telegramUserId()).isEqualTo(100000001L);
        }

        /** Иначе семья, зарегистрированная днём, получит дайджест ближайшим тиком джобы. */
        @Test
        void digestIsMarkedAsAlreadySentOnRegistrationDay() {
            Member founder = founder();

            assertThat(families.findById(founder.familyId()).orElseThrow().lastDigestDate())
                    .isEqualTo(LocalDate.of(2026, 8, 7));
        }

        @Test
        void familyKeepsItsTimezone() {
            Member founder = founder();

            assertThat(families.findById(founder.familyId()).orElseThrow().timezone())
                    .isEqualTo(MOSCOW);
        }

        /** Один человек = одна семья: повторная регистрация не создаёт вторую. */
        @Test
        void personAlreadyInFamilyCannotCreateAnother() {
            founder();

            assertThatThrownBy(
                            () ->
                                    service.createFamily(
                                            100000001L, 100000001L, "Мама", "Петровы", MOSCOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class RemoveMember {

        @Test
        void marksMemberRemoved() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);

            service.removeMember(mom, kid.id());

            assertThat(members.findById(mom.familyId(), kid.id()).orElseThrow().status())
                    .isEqualTo(MemberStatus.REMOVED);
        }

        /** Открытые задачи ушедшего закрываются системой и не висят на нём вечно. */
        @Test
        void cancelsOpenTasksAssignedToRemovedMember() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);
            Task task = taskService.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            service.removeMember(mom, kid.id());

            Task cancelled = tasks.findById(mom.familyId(), task.id()).orElseThrow();
            assertThat(cancelled.status()).isEqualTo(TaskStatus.DECLINED);
            assertThat(cancelled.declineReason()).isNotBlank();
            assertThat(cancelled.closedAt()).isEqualTo(NOW);
        }

        @Test
        void notifiesAuthorsOfCancelledTasks() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);
            Task task = taskService.create(mom, kid.id(), "Вынести мусор", DUE);
            notifier.clear();

            service.removeMember(mom, kid.id());

            assertThat(notifier.sent())
                    .extracting(
                            FakeNotifier.Sent::kind,
                            FakeNotifier.Sent::recipientId,
                            FakeNotifier.Sent::taskId)
                    .containsExactly(tuple(Kind.CANCELLED, mom.id(), task.id()));
        }

        /** Задачи, которые ушедший поручил другим, его уход не отменяет: делать их всё ещё нужно. */
        @Test
        void keepsTasksTheRemovedMemberCreatedForOthers() {
            Member mom = founder();
            Member dad = join(mom, "Папа", Role.PARENT);
            Member kid = join(mom, "Петя", Role.CHILD);
            Task byKid = taskService.create(kid, dad.id(), "Починить велосипед", DUE);

            service.removeMember(mom, kid.id());

            assertThat(tasks.findById(mom.familyId(), byKid.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.OPEN);
        }

        @Test
        void closedTasksOfRemovedMemberAreLeftAlone() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);
            Task task = taskService.create(mom, kid.id(), "Вынести мусор", DUE);
            taskService.complete(kid, task.id());

            service.removeMember(mom, kid.id());

            assertThat(tasks.findById(mom.familyId(), task.id()).orElseThrow().status())
                    .isEqualTo(TaskStatus.DONE);
        }

        @Test
        void deniedForChild() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);
            Member otherKid = join(mom, "Вася", Role.CHILD);

            assertThatThrownBy(() -> service.removeMember(kid, otherKid.id()))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedForLastParent() {
            Member mom = founder();
            join(mom, "Петя", Role.CHILD);

            assertThatThrownBy(() -> service.removeMember(mom, mom.id()))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void unknownMemberIsNotFound() {
            Member mom = founder();

            assertThatThrownBy(() -> service.removeMember(mom, 999L))
                    .isInstanceOf(DomainException.NotFound.class);
        }
    }

    @Nested
    class ChangeRole {

        @Test
        void parentPromotesChild() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);

            service.changeRole(mom, kid.id(), Role.PARENT);

            assertThat(members.findById(mom.familyId(), kid.id()).orElseThrow().role())
                    .isEqualTo(Role.PARENT);
        }

        @Test
        void lastParentCannotBeDemoted() {
            Member mom = founder();
            join(mom, "Петя", Role.CHILD);

            assertThatThrownBy(() -> service.changeRole(mom, mom.id(), Role.CHILD))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Roster {

        @Test
        void listsOnlyActiveMembersOfOwnFamily() {
            Member mom = founder();
            Member kid = join(mom, "Петя", Role.CHILD);
            service.removeMember(mom, kid.id());

            assertThat(service.roster(mom)).extracting(Member::id).containsExactly(mom.id());
        }
    }
}

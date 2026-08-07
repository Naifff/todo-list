package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.application.fake.FakeNotifier;
import com.familytodo.application.fake.InMemoryFamilyRepository;
import com.familytodo.application.fake.InMemoryInviteRepository;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryTaskRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Invite;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final InMemoryFamilyRepository families = new InMemoryFamilyRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final InMemoryInviteRepository invites = new InMemoryInviteRepository();
    private final MutableClock clock = new MutableClock(NOW);

    private final FamilyService familyService =
            new FamilyService(
                    families, members, new InMemoryTaskRepository(), new FakeNotifier(), clock);
    private final InviteService service =
            new InviteService(invites, members, new InviteCodeGenerator(), clock);

    private Member mom() {
        return familyService.createFamily(100000001L, 100000001L, "Мама", "Ивановы", MOSCOW);
    }

    @Nested
    class Issue {

        @Test
        void parentIssuesInviteWithRequestedRole() {
            Member mom = mom();

            Invite invite = service.issue(mom, Role.CHILD);

            assertThat(invite.role()).isEqualTo(Role.CHILD);
            assertThat(invite.familyId()).isEqualTo(mom.familyId());
            assertThat(invite.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
            assertThat(invites.findByCode(invite.code())).isPresent();
        }

        @Test
        void deniedForChild() {
            Member mom = mom();
            Member kid =
                    members.save(
                            Member.join(
                                    members.nextId(),
                                    mom.familyId(),
                                    777L,
                                    777L,
                                    "Петя",
                                    Role.CHILD,
                                    NOW));

            assertThatThrownBy(() -> service.issue(kid, Role.CHILD))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void issuedCodesDiffer() {
            Member mom = mom();

            assertThat(service.issue(mom, Role.CHILD).code())
                    .isNotEqualTo(service.issue(mom, Role.CHILD).code());
        }
    }

    @Nested
    class Redeem {

        @Test
        void newcomerJoinsWithTheRoleFromInvite() {
            Member mom = mom();
            Invite invite = service.issue(mom, Role.CHILD);

            Member joined = service.redeem(invite.code(), 512034877L, 512034877L, "Петя");

            assertThat(joined.familyId()).isEqualTo(mom.familyId());
            assertThat(joined.role()).isEqualTo(Role.CHILD);
            assertThat(joined.privateChatId()).isEqualTo(512034877L);
            assertThat(members.findByTelegramUserId(512034877L)).isPresent();
        }

        @Test
        void codeIsBurnedAfterUse() {
            Member mom = mom();
            Invite invite = service.issue(mom, Role.CHILD);
            service.redeem(invite.code(), 512034877L, 512034877L, "Петя");

            assertThatThrownBy(() -> service.redeem(invite.code(), 600000L, 600000L, "Вася"))
                    .isInstanceOf(DomainException.InvalidTransition.class);
            assertThat(members.findByTelegramUserId(600000L)).isEmpty();
        }

        @Test
        void expiredCodeIsRejected() {
            Member mom = mom();
            Invite invite = service.issue(mom, Role.CHILD);
            clock.advance(Duration.ofHours(24));

            assertThatThrownBy(() -> service.redeem(invite.code(), 512034877L, 512034877L, "Петя"))
                    .isInstanceOf(DomainException.InvalidTransition.class);
            assertThat(members.findByTelegramUserId(512034877L)).isEmpty();
        }

        @Test
        void unknownCodeIsNotFound() {
            mom();

            assertThatThrownBy(() -> service.redeem("нет-такого", 512034877L, 512034877L, "Петя"))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        /** Один человек = одна семья: `telegram_user_id` уникален глобально. */
        @Test
        void personAlreadyInAFamilyCannotJoinAnother() {
            Member mom = mom();
            Invite invite = service.issue(mom, Role.CHILD);

            assertThatThrownBy(
                            () ->
                                    service.redeem(
                                            invite.code(),
                                            mom.telegramUserId(),
                                            mom.privateChatId(),
                                            "Мама"))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Отказ не должен гасить код: приглашение ещё пригодится тому, кому оно предназначалось. */
        @Test
        void rejectedAttemptLeavesCodeUsable() {
            Member mom = mom();
            Invite invite = service.issue(mom, Role.CHILD);
            try {
                service.redeem(invite.code(), mom.telegramUserId(), mom.privateChatId(), "Мама");
            } catch (DomainException.NotPermitted expected) {
                // ожидаемо
            }

            Member joined = service.redeem(invite.code(), 512034877L, 512034877L, "Петя");

            assertThat(joined.role()).isEqualTo(Role.CHILD);
        }
    }

    /** Часы, которые можно двигать: TTL приглашения проверяется без {@code Thread.sleep}. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

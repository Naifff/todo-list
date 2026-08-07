package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcInviteRepository;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.domain.Actor;
import com.familytodo.domain.Family;
import com.familytodo.domain.Invite;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FamilyRepositoryIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private JdbcFamilyRepository familyRepository;
    private JdbcMemberRepository memberRepository;
    private JdbcInviteRepository inviteRepository;

    @BeforeEach
    void repositories() {
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        familyRepository = new JdbcFamilyRepository(jdbc, sequence);
        memberRepository = new JdbcMemberRepository(jdbc, sequence);
        inviteRepository = new JdbcInviteRepository(jdbc, sequence);
    }

    private Family family() {
        return familyRepository.save(
                Family.create(familyRepository.nextId(), "Ивановы", MOSCOW, NOW));
    }

    private Member member(Family family, long telegramUserId, String name, Role role) {
        return memberRepository.save(
                Member.join(
                        memberRepository.nextId(),
                        family.id(),
                        telegramUserId,
                        telegramUserId,
                        name,
                        role,
                        NOW));
    }

    @Nested
    class Families {

        /** Таймзона должна возвращаться в виде, который сразу годится для {@code ZoneId}. */
        @Test
        void roundTripsTimezoneAsZoneId() {
            Family saved = family();

            Family loaded = familyRepository.findById(saved.id()).orElseThrow();

            assertThat(loaded.timezone()).isEqualTo(MOSCOW);
            assertThat(loaded.timezone().getRules()).isEqualTo(MOSCOW.getRules());
        }

        @Test
        void roundTripsDigestTimeAndLastDigestDate() {
            Family saved = family();
            saved.changeDigestTime(
                    Actor.member(1L, saved.id(), Role.PARENT), LocalTime.of(7, 30));
            saved.markDigestSent(LocalDate.of(2026, 8, 8));
            familyRepository.save(saved);

            Family loaded = familyRepository.findById(saved.id()).orElseThrow();

            assertThat(loaded.digestTime()).isEqualTo(LocalTime.of(7, 30));
            assertThat(loaded.lastDigestDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        }

        /** Джоба дайджеста обходит все семьи — выборка должна их отдавать. */
        @Test
        void findsEveryFamily() {
            family();
            family();

            assertThat(familyRepository.findAll()).hasSize(2);
        }
    }

    @Nested
    class Members {

        @Test
        void roundTripsEveryField() {
            Family family = family();
            Member saved = member(family, 100000001L, "Мама", Role.PARENT);
            saved.markBotBlocked();
            memberRepository.save(saved);

            Member loaded = memberRepository.findById(family.id(), saved.id()).orElseThrow();

            assertThat(loaded.displayName()).isEqualTo("Мама");
            assertThat(loaded.role()).isEqualTo(Role.PARENT);
            assertThat(loaded.status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(loaded.blockedBot()).isTrue();
            assertThat(loaded.isReachable()).isFalse();
            assertThat(loaded.telegramUserId()).isEqualTo(100000001L);
            assertThat(loaded.createdAt()).isEqualTo(NOW);
        }

        /**
         * Уникальность {@code telegram_user_id} — это и есть «один человек = одна семья». Тест
         * заодно доказывает, что транслятор доводит код SQLite до осмысленного типа: без него
         * пришёл бы {@code UncategorizedSQLException}, неотличимый от обрыва соединения.
         */
        @Test
        void rejectsSecondMemberWithTheSameTelegramUserId() {
            Family first = family();
            Family second = family();
            member(first, 100000001L, "Мама", Role.PARENT);

            assertThatThrownBy(() -> member(second, 100000001L, "Она же", Role.PARENT))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void findsByTelegramUserIdWithoutKnowingTheFamily() {
            Family family = family();
            Member saved = member(family, 100000001L, "Мама", Role.PARENT);

            assertThat(memberRepository.findByTelegramUserId(100000001L))
                    .get()
                    .extracting(Member::id, Member::familyId)
                    .containsExactly(saved.id(), family.id());
        }

        @Test
        void memberOfAnotherFamilyIsNotFoundById() {
            Family first = family();
            Family second = family();
            Member saved = member(first, 100000001L, "Мама", Role.PARENT);

            assertThat(memberRepository.findById(second.id(), saved.id())).isEmpty();
        }

        @Test
        void activeListExcludesRemovedAndOtherFamilies() {
            Family family = family();
            Family other = family();
            Member mom = member(family, 100000001L, "Мама", Role.PARENT);
            Member kid = member(family, 512034877L, "Петя", Role.CHILD);
            member(other, 600000L, "Чужой", Role.PARENT);

            family.removeMember(mom.asActor(), kid, memberRepository.findActive(family.id()));
            memberRepository.save(kid);

            assertThat(memberRepository.findActive(family.id()))
                    .extracting(Member::id)
                    .containsExactly(mom.id());
        }
    }

    @Nested
    class Invites {

        private Invite issue(Family family, Member parent, String code) {
            return inviteRepository.save(
                    Invite.restore(
                            inviteRepository.nextId(),
                            family.id(),
                            code,
                            Role.CHILD,
                            parent.id(),
                            NOW.plusSeconds(86_400),
                            null,
                            null));
        }

        @Test
        void roundTripsUnusedInvite() {
            Family family = family();
            Member mom = member(family, 100000001L, "Мама", Role.PARENT);
            Invite saved = issue(family, mom, "aBcDeFgHiJkLmNoPqRsTuV");

            Invite loaded = inviteRepository.findByCode(saved.code()).orElseThrow();

            assertThat(loaded.usedBy()).isNull();
            assertThat(loaded.usedAt()).isNull();
            assertThat(loaded.role()).isEqualTo(Role.CHILD);
            assertThat(loaded.expiresAt()).isEqualTo(NOW.plusSeconds(86_400));
            assertThat(loaded.isUsable(NOW)).isTrue();
        }

        @Test
        void roundTripsRedeemedInvite() {
            Family family = family();
            Member mom = member(family, 100000001L, "Мама", Role.PARENT);
            Member kid = member(family, 512034877L, "Петя", Role.CHILD);
            Invite saved = issue(family, mom, "aBcDeFgHiJkLmNoPqRsTuV");
            saved.redeem(kid.id(), NOW);
            inviteRepository.save(saved);

            Invite loaded = inviteRepository.findByCode(saved.code()).orElseThrow();

            assertThat(loaded.usedBy()).isEqualTo(kid.id());
            assertThat(loaded.usedAt()).isEqualTo(NOW);
            assertThat(loaded.isUsable(NOW)).isFalse();
        }

        /** Код уникален на уровне схемы: два приглашения с одним кодом невозможны физически. */
        @Test
        void rejectsDuplicateCode() {
            Family family = family();
            Member mom = member(family, 100000001L, "Мама", Role.PARENT);
            issue(family, mom, "aBcDeFgHiJkLmNoPqRsTuV");

            assertThatThrownBy(() -> issue(family, mom, "aBcDeFgHiJkLmNoPqRsTuV"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void unknownCodeIsEmpty() {
            assertThat(inviteRepository.findByCode("нет-такого")).isEmpty();
        }
    }

    @Test
    void sequencesAreIndependentPerEntity() {
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);

        assertThat(sequence.next("family")).isEqualTo(1L);
        assertThat(sequence.next("member")).isEqualTo(1L);
        assertThat(sequence.next("family")).isEqualTo(2L);
    }
}

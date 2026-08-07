package com.familytodo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcInviteRepository;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Invite;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import com.familytodo.support.NoOpNotifier;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Вход в семью по приглашению — на реальной базе.
 *
 * <p>Этот класс появился после отказа в проде: жена перешла по ссылке и получила «Что-то пошло не
 * так». Причина — {@code invite.used_by} записывался ссылкой на участника, строки которого ещё не
 * было, и внешний ключ отбивал вставку.
 *
 * <p>Юнит-тесты этого не видели и увидеть не могли: {@code InMemoryInviteRepository} внешних ключей
 * не проверяет. Ровно тот случай, о котором предупреждает {@code CLAUDE.md} — фейк, прощающий
 * больше базы, обесценивает тесты юзкейсов. Поэтому проверка живёт здесь, где ключи включены.
 */
class InviteRedemptionIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static final long WIFE_TELEGRAM_ID = 512034877L;

    private JdbcMemberRepository members;
    private JdbcInviteRepository invites;
    private InviteService service;

    private Family family;
    private Member founder;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        JdbcFamilyRepository families = new JdbcFamilyRepository(jdbc, sequence);
        members = new JdbcMemberRepository(jdbc, sequence);
        invites = new JdbcInviteRepository(jdbc, sequence);

        FamilyService familyService =
                new FamilyService(
                        families,
                        members,
                        new JdbcTaskRepository(jdbc, sequence),
                        new NoOpNotifier(),
                        clock);
        service = new InviteService(invites, members, new InviteCodeGenerator(), clock);

        founder = familyService.createFamily(100000001L, 100000001L, "Мама", "Румянцевы", MOSCOW);
        family = families.findById(founder.familyId()).orElseThrow();
    }

    @Nested
    class NewMember {

        /** Тот самый сценарий, который упал в проде. */
        @Test
        void personWithALinkJoinsTheFamily() {
            Invite invite = service.issue(founder, Role.PARENT);

            Member joined =
                    service.redeem(invite.code(), WIFE_TELEGRAM_ID, WIFE_TELEGRAM_ID, "Жена");

            assertThat(joined.familyId()).isEqualTo(family.id());
            assertThat(joined.role()).isEqualTo(Role.PARENT);
            assertThat(joined.status()).isEqualTo(MemberStatus.ACTIVE);
        }

        /** Строка участника обязана оказаться в базе, а не только вернуться из метода. */
        @Test
        void joinedMemberIsPersisted() {
            Invite invite = service.issue(founder, Role.CHILD);

            Member joined = service.redeem(invite.code(), WIFE_TELEGRAM_ID, WIFE_TELEGRAM_ID, "Петя");

            assertThat(members.findById(family.id(), joined.id())).isPresent();
            assertThat(members.findByTelegramUserId(WIFE_TELEGRAM_ID)).isPresent();
        }

        @Test
        void inviteIsMarkedUsedByTheJoinedMember() {
            Invite invite = service.issue(founder, Role.PARENT);

            Member joined =
                    service.redeem(invite.code(), WIFE_TELEGRAM_ID, WIFE_TELEGRAM_ID, "Жена");

            Invite stored = invites.findByCode(invite.code()).orElseThrow();
            assertThat(stored.usedBy()).isEqualTo(joined.id());
            assertThat(stored.usedAt()).isEqualTo(NOW);
        }

        /** Код одноразовый: второй человек по той же ссылке не входит. */
        @Test
        void theSameLinkCannotBeUsedTwice() {
            Invite invite = service.issue(founder, Role.PARENT);
            service.redeem(invite.code(), WIFE_TELEGRAM_ID, WIFE_TELEGRAM_ID, "Жена");

            assertThatThrownBy(() -> service.redeem(invite.code(), 700001L, 700001L, "Посторонний"))
                    .isInstanceOf(DomainException.InvalidTransition.class);

            assertThat(members.findActive(family.id())).hasSize(2);
        }
    }

    @Nested
    class Rejoining {

        /** Исключённого зовут обратно той же дорогой — строка участника уже есть. */
        @Test
        void removedMemberCanBeInvitedBack() {
            Invite first = service.issue(founder, Role.CHILD);
            Member kid = service.redeem(first.code(), WIFE_TELEGRAM_ID, WIFE_TELEGRAM_ID, "Петя");
            family.removeMember(founder.asActor(), kid, members.findActive(family.id()));
            members.save(kid);

            Invite second = service.issue(founder, Role.CHILD);
            Member back = service.redeem(second.code(), WIFE_TELEGRAM_ID, 999L, "Петя");

            assertThat(back.id()).isEqualTo(kid.id());
            assertThat(back.status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(invites.findByCode(second.code()).orElseThrow().usedBy()).isEqualTo(kid.id());
        }
    }
}

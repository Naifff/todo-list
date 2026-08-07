package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemberTest {

    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static Member member() {
        return Member.join(10L, 1L, 100000001L, 100000001L, "Мама", Role.PARENT, NOW);
    }

    @Test
    void joinsAsActive() {
        Member member = member();

        assertThat(member.status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.isActive()).isTrue();
        assertThat(member.blockedBot()).isFalse();
    }

    @Test
    void actorCarriesFamilyAndRole() {
        Actor actor = member().asActor();

        assertThat(actor).isInstanceOf(Actor.MemberActor.class);
        Actor.MemberActor memberActor = (Actor.MemberActor) actor;
        assertThat(memberActor.memberId()).isEqualTo(10L);
        assertThat(memberActor.familyId()).isEqualTo(1L);
        assertThat(memberActor.role()).isEqualTo(Role.PARENT);
    }

    /** Без этого напоминания недоставимы: бот пишет в личный чат, а не в чат-источник апдейта. */
    @Test
    void remembersPrivateChatId() {
        Member member = member();

        member.rememberPrivateChat(987654321L);

        assertThat(member.privateChatId()).isEqualTo(987654321L);
    }

    @Test
    void marksBotBlockedAndBack() {
        Member member = member();

        member.markBotBlocked();
        assertThat(member.blockedBot()).isTrue();
        assertThat(member.isReachable()).isFalse();

        member.markBotUnblocked();
        assertThat(member.blockedBot()).isFalse();
        assertThat(member.isReachable()).isTrue();
    }

    /** Исключённому уведомления не шлём, даже если бота он не блокировал. */
    @Test
    void removedMemberIsNotReachable() {
        Member member = member();

        member.markRemoved();

        assertThat(member.isReachable()).isFalse();
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThatThrownBy(() -> Member.join(10L, 1L, 1L, 1L, " ", Role.PARENT, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

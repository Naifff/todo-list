package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InviteTest {

    private static final long FAMILY = 1L;
    private static final long MOM = 10L;
    private static final long KID = 12L;
    private static final long NEWCOMER = 20L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static Actor parent() {
        return Actor.member(MOM, FAMILY, Role.PARENT);
    }

    private static Actor child() {
        return Actor.member(KID, FAMILY, Role.CHILD);
    }

    private static Invite issued() {
        return Invite.issue(parent(), 500L, FAMILY, "aBcDeFgHiJkLmNoPqRsTuV", Role.CHILD, NOW);
    }

    @Nested
    class Issue {

        @Test
        void expiresIn24Hours() {
            Invite invite = issued();

            assertThat(invite.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
            assertThat(invite.role()).isEqualTo(Role.CHILD);
            assertThat(invite.createdBy()).isEqualTo(MOM);
        }

        @Test
        void deniedForChild() {
            assertThatThrownBy(
                            () ->
                                    Invite.issue(
                                            child(), 500L, FAMILY, "code", Role.CHILD, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedForParentOfAnotherFamily() {
            Actor stranger = Actor.member(MOM, 999L, Role.PARENT);

            assertThatThrownBy(
                            () -> Invite.issue(stranger, 500L, FAMILY, "code", Role.CHILD, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Redeem {

        @Test
        void marksCodeUsed() {
            Invite invite = issued();

            invite.redeem(NEWCOMER, NOW.plus(Duration.ofHours(1)));

            assertThat(invite.usedBy()).isEqualTo(NEWCOMER);
            assertThat(invite.usedAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
            assertThat(invite.isUsable(NOW.plus(Duration.ofHours(1)))).isFalse();
        }

        @Test
        void rejectsSecondUse() {
            Invite invite = issued();
            invite.redeem(NEWCOMER, NOW);

            assertThatThrownBy(() -> invite.redeem(21L, NOW))
                    .isInstanceOf(DomainException.InvalidTransition.class);
            assertThat(invite.usedBy()).isEqualTo(NEWCOMER);
        }

        @Test
        void rejectsExpiredCode() {
            Invite invite = issued();

            assertThatThrownBy(() -> invite.redeem(NEWCOMER, NOW.plus(Duration.ofHours(24))))
                    .isInstanceOf(DomainException.InvalidTransition.class);
            assertThat(invite.usedBy()).isNull();
        }

        @Test
        void acceptsCodeOneSecondBeforeExpiry() {
            Invite invite = issued();

            invite.redeem(NEWCOMER, NOW.plus(Duration.ofHours(24)).minusSeconds(1));

            assertThat(invite.usedBy()).isEqualTo(NEWCOMER);
        }
    }

    @Nested
    class CodeGenerator {

        private static final Pattern BASE64_URL = Pattern.compile("^[A-Za-z0-9_-]+$");

        private final InviteCodeGenerator generator = new InviteCodeGenerator();

        /**
         * 128 бит в base64url — 22 символа против 36 у UUID. Причина выбора именно длина ссылки и
         * алфавит: {@code UUID.randomUUID()} тоже основан на {@code SecureRandom} и слабым не
         * является.
         */
        @Test
        void producesShortUrlSafeCode() {
            String code = generator.generate();

            assertThat(code).hasSize(22);
            assertThat(code).matches(BASE64_URL);
        }

        /** Ссылка t.me/<bot>?start=inv_<код> должна уложиться в 64 символа параметра start. */
        @Test
        void fitsTelegramStartParameter() {
            assertThat("inv_" + generator.generate()).hasSizeLessThanOrEqualTo(64);
        }

        @Test
        void doesNotRepeat() {
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                codes.add(generator.generate());
            }

            assertThat(codes).hasSize(1000);
        }
    }
}

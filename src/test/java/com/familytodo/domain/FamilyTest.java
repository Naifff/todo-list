package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FamilyTest {

    private static final long FAMILY = 1L;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static Family family() {
        return Family.create(FAMILY, "Ивановы", MOSCOW, NOW);
    }

    private static Member mom() {
        return Member.join(10L, FAMILY, 100000001L, 100000001L, "Мама", Role.PARENT, NOW);
    }

    private static Member dad() {
        return Member.join(11L, FAMILY, 512034877L, 512034877L, "Папа", Role.PARENT, NOW);
    }

    private static Member kid() {
        return Member.join(12L, FAMILY, 700000001L, 700000001L, "Петя", Role.CHILD, NOW);
    }

    @Nested
    class Creation {

        @Test
        void startsWithDefaultDigestTime() {
            Family family = family();

            assertThat(family.digestTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(family.timezone()).isEqualTo(MOSCOW);
            assertThat(family.name()).isEqualTo("Ивановы");
        }

        /** Иначе регистрация днём немедленно вызовет дайджест ближайшим тиком джобы. */
        @Test
        void marksDigestAsAlreadySentToday() {
            Family family = family();

            assertThat(family.lastDigestDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        }

        /** «Сегодня» — по календарю семьи: в UTC+3 вечер 7-го уже наступил 8-е. */
        @Test
        void todayIsResolvedInFamilyTimezone() {
            Family family = Family.create(FAMILY, "Ивановы", MOSCOW, Instant.parse("2026-08-07T22:30:00Z"));

            assertThat(family.lastDigestDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        }

        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> Family.create(FAMILY, "  ", MOSCOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class RemoveMember {

        @Test
        void marksMemberRemovedInsteadOfDeleting() {
            Family family = family();
            Member target = kid();

            family.removeMember(mom().asActor(), target, List.of(mom(), dad(), target));

            assertThat(target.status()).isEqualTo(MemberStatus.REMOVED);
            assertThat(target.isActive()).isFalse();
        }

        @Test
        void deniedForChild() {
            Family family = family();
            Member target = dad();

            assertThatThrownBy(
                            () ->
                                    family.removeMember(
                                            kid().asActor(), target, List.of(mom(), target, kid())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(target.status()).isEqualTo(MemberStatus.ACTIVE);
        }

        /** Иначе семья остаётся без единственной роли, которая может звать новых участников. */
        @Test
        void deniedForLastActiveParent() {
            Family family = family();
            Member onlyParent = mom();

            assertThatThrownBy(
                            () ->
                                    family.removeMember(
                                            onlyParent.asActor(),
                                            onlyParent,
                                            List.of(onlyParent, kid())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(onlyParent.status()).isEqualTo(MemberStatus.ACTIVE);
        }

        @Test
        void allowedForParentWhenAnotherParentRemains() {
            Family family = family();
            Member target = dad();

            family.removeMember(mom().asActor(), target, List.of(mom(), target, kid()));

            assertThat(target.status()).isEqualTo(MemberStatus.REMOVED);
        }

        @Test
        void deniedForActorFromAnotherFamily() {
            Family family = family();
            Member target = kid();
            Actor stranger = Actor.member(10L, 999L, Role.PARENT);

            assertThatThrownBy(
                            () -> family.removeMember(stranger, target, List.of(mom(), dad(), target)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedForMemberOfAnotherFamily() {
            Family family = family();
            Member foreign = Member.join(90L, 999L, 1L, 1L, "Чужой", Role.CHILD, NOW);

            assertThatThrownBy(
                            () -> family.removeMember(mom().asActor(), foreign, List.of(mom(), dad())))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void deniedWhenAlreadyRemoved() {
            Family family = family();
            Member target = kid();
            family.removeMember(mom().asActor(), target, List.of(mom(), dad(), target));

            assertThatThrownBy(
                            () ->
                                    family.removeMember(
                                            mom().asActor(), target, List.of(mom(), dad())))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }
    }

    @Nested
    class ChangeRole {

        @Test
        void promotesChildToParent() {
            Family family = family();
            Member target = kid();

            family.changeRole(mom().asActor(), target, Role.PARENT, List.of(mom(), target));

            assertThat(target.role()).isEqualTo(Role.PARENT);
        }

        /** Понижение последнего родителя — тот же обход, что и его исключение. */
        @Test
        void deniedForLastActiveParent() {
            Family family = family();
            Member onlyParent = mom();

            assertThatThrownBy(
                            () ->
                                    family.changeRole(
                                            onlyParent.asActor(),
                                            onlyParent,
                                            Role.CHILD,
                                            List.of(onlyParent, kid())))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(onlyParent.role()).isEqualTo(Role.PARENT);
        }

        @Test
        void allowedWhenAnotherParentRemains() {
            Family family = family();
            Member target = dad();

            family.changeRole(mom().asActor(), target, Role.CHILD, List.of(mom(), target));

            assertThat(target.role()).isEqualTo(Role.CHILD);
        }

        @Test
        void deniedForChild() {
            Family family = family();
            Member target = kid();

            assertThatThrownBy(
                            () ->
                                    family.changeRole(
                                            kid().asActor(),
                                            target,
                                            Role.PARENT,
                                            List.of(mom(), target)))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Settings {

        @Test
        void parentChangesTimezoneAndDigestTime() {
            Family family = family();

            family.changeTimezone(mom().asActor(), ZoneId.of("Asia/Novosibirsk"));
            family.changeDigestTime(mom().asActor(), LocalTime.of(7, 30));

            assertThat(family.timezone()).isEqualTo(ZoneId.of("Asia/Novosibirsk"));
            assertThat(family.digestTime()).isEqualTo(LocalTime.of(7, 30));
        }

        @Test
        void childCannotChangeSettings() {
            Family family = family();

            assertThatThrownBy(() -> family.changeTimezone(kid().asActor(), ZoneId.of("UTC")))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThatThrownBy(() -> family.changeDigestTime(kid().asActor(), LocalTime.NOON))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void digestIsMarkedSentForFamilyLocalDate() {
            Family family = family();

            family.markDigestSent(LocalDate.of(2026, 8, 8));

            assertThat(family.lastDigestDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        }

        @Test
        void settingsAreDeniedForActorFromAnotherFamily() {
            Family family = family();
            Actor stranger = Actor.member(10L, 999L, Role.PARENT);

            assertThatThrownBy(() -> family.changeDigestTime(stranger, LocalTime.NOON))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void ownParentMayChangeSettings() {
            Family family = family();

            assertThatCode(() -> family.changeDigestTime(dad().asActor(), LocalTime.of(9, 0)))
                    .doesNotThrowAnyException();
        }
    }
}

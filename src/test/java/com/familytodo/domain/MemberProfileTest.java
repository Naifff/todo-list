package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Имя и цвет участника.
 *
 * <p>Имя приходит из профиля Telegram и в семье часто бесполезно: «Naif» вместо «Папа». Править его
 * может родитель — тот же, кто раздаёт роли и приглашения.
 *
 * <p>Цвет нужен календарю: в сетке на неделю по имени в блоке не разобрать, чьё дело, а по цвету
 * видно с одного взгляда.
 */
class MemberProfileTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");

    private final Family family = Family.create(1L, "Ивановы", MOSCOW, NOW);
    private final Member mom = Member.join(10L, 1L, 100L, 100L, "Мама", Role.PARENT, NOW);
    private final Member kid = Member.join(12L, 1L, 102L, 102L, "Naif", Role.CHILD, NOW);

    @Nested
    class Renaming {

        @Test
        void aParentRenamesAnyone() {
            family.renameMember(mom.asActor(), kid, "Петя");

            assertThat(kid.displayName()).isEqualTo("Петя");
        }

        /**
         * Ребёнок не правит имена — включая своё.
         *
         * <p>Право то же, что на роли и приглашения: имя видно всей семье, и «Петя» вместо «Пётр»
         * поменяли бы туда-обратно ровно один раз.
         */
        @Test
        void aChildRenamesNobody() {
            assertThatThrownBy(() -> family.renameMember(kid.asActor(), kid, "Петя"))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThatThrownBy(() -> family.renameMember(kid.asActor(), mom, "Не мама"))
                    .isInstanceOf(DomainException.NotPermitted.class);

            assertThat(mom.displayName()).isEqualTo("Мама");
        }

        @Test
        void anActorFromAnotherFamilyMayNotRename() {
            Actor stranger = Actor.member(90L, 2L, Role.PARENT);

            assertThatThrownBy(() -> family.renameMember(stranger, kid, "Чужой"))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void aMemberOfAnotherFamilyMayNotBeRenamed() {
            Member alien = Member.join(90L, 2L, 900L, 900L, "Чужой", Role.CHILD, NOW);

            assertThatThrownBy(() -> family.renameMember(mom.asActor(), alien, "Свой"))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        @Test
        void aRemovedMemberIsNotRenamed() {
            family.removeMember(mom.asActor(), kid, List.of(mom, kid));

            assertThatThrownBy(() -> family.renameMember(mom.asActor(), kid, "Петя"))
                    .isInstanceOf(DomainException.InvalidTransition.class);
        }

        @Test
        void aBlankOrTooLongNameIsRejected() {
            assertThatThrownBy(() -> family.renameMember(mom.asActor(), kid, "  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    family.renameMember(
                                            mom.asActor(), kid, "и".repeat(Member.MAX_NAME + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Colours {

        /** Цвет есть у каждого с первого дня: календарь не должен ждать, пока его выберут. */
        @Test
        void everyMemberHasAColourFromTheStart() {
            assertThat(mom.color()).isNotNull();
            assertThat(kid.color()).isNotNull();
        }

        /** Соседние по номеру участники получают разные цвета — иначе смысл теряется. */
        @Test
        void membersJoiningInOrderGetDifferentColours() {
            List<MemberColor> assigned =
                    List.of(
                            MemberColor.forMember(1L),
                            MemberColor.forMember(2L),
                            MemberColor.forMember(3L),
                            MemberColor.forMember(4L));

            assertThat(assigned).doesNotHaveDuplicates();
        }

        /** Цвет не пляшет между запусками: он выводится из номера, а не из порядка выборки. */
        @Test
        void theSameMemberAlwaysGetsTheSameColour() {
            assertThat(MemberColor.forMember(7L)).isEqualTo(MemberColor.forMember(7L));
        }

        @Test
        void aParentRecoloursAnyone() {
            family.recolorMember(mom.asActor(), kid, MemberColor.PURPLE);

            assertThat(kid.color()).isEqualTo(MemberColor.PURPLE);
        }

        @Test
        void aChildRecoloursNobody() {
            assertThatThrownBy(() -> family.recolorMember(kid.asActor(), kid, MemberColor.GREEN))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThatThrownBy(() -> family.recolorMember(kid.asActor(), mom, MemberColor.GREEN))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Каждый цвет знает свой hex — он уезжает прямо в CSS страницы расписания. */
        @Test
        void everyColourCarriesAHexForTheCalendar() {
            for (MemberColor color : MemberColor.values()) {
                assertThat(color.hex()).matches("#[0-9a-fA-F]{6}");
                assertThat(color.title()).isNotBlank();
            }
        }

        @Test
        void coloursAreDistinct() {
            assertThat(java.util.Arrays.stream(MemberColor.values()).map(MemberColor::hex).toList())
                    .doesNotHaveDuplicates();
        }
    }
}

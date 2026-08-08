package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Позиция списка покупок. Правил здесь заметно меньше, чем у задачи, и это само по себе решение:
 * список покупок общий, поэтому роль не ограничивает ничего — ни добавление, ни вычёркивание.
 *
 * <p>Что осталось от прав: позиция принадлежит семье, и актор из чужой семьи не проходит. Это та же
 * защита в глубину, что у {@link Task}: изоляцию держит фильтр по {@code family_id} в SQL, но
 * подделанный {@code callback_data} не должен проходить и здесь.
 *
 * <p>Статус участника ({@code REMOVED}) тут не проверяется намеренно — как и у задачи. Исключённого
 * отсекает {@code UpdateRouter} фильтром {@code Member::isActive} до всякого хендлера, а {@link
 * Actor} статуса не несёт вовсе.
 */
class ShoppingItemTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;

    private static final long MOM = 10L;
    private static final long KID = 12L;

    private static final Instant ADDED = Instant.parse("2026-08-08T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private static Actor parent(long id) {
        return Actor.member(id, FAMILY, Role.PARENT);
    }

    private static Actor child(long id) {
        return Actor.member(id, FAMILY, Role.CHILD);
    }

    private static ShoppingItem milk() {
        return ShoppingItem.add(
                100L, FAMILY, ShoppingList.FOOD, "Молоко", parent(MOM), ADDED);
    }

    @Nested
    class Adding {

        @Test
        void addedItemIsNotBoughtAndRemembersWhoAsked() {
            ShoppingItem item = milk();

            assertThat(item.id()).isEqualTo(100L);
            assertThat(item.familyId()).isEqualTo(FAMILY);
            assertThat(item.list()).isEqualTo(ShoppingList.FOOD);
            assertThat(item.title()).isEqualTo("Молоко");
            assertThat(item.addedBy()).isEqualTo(MOM);
            assertThat(item.addedAt()).isEqualTo(ADDED);
            assertThat(item.isBought()).isFalse();
            assertThat(item.boughtBy()).isNull();
            assertThat(item.boughtAt()).isNull();
        }

        /** Главное новое правило: ребёнок вносит заказы наравне со взрослым. */
        @Test
        void aChildMayAddToEitherList() {
            assertThatCode(
                            () ->
                                    ShoppingItem.add(
                                            101L,
                                            FAMILY,
                                            ShoppingList.HOUSEHOLD,
                                            "Мыло",
                                            child(KID),
                                            ADDED))
                    .doesNotThrowAnyException();
        }

        @Test
        void anActorFromAnotherFamilyMayNotAdd() {
            Actor stranger = Actor.member(MOM, OTHER_FAMILY, Role.PARENT);

            assertThatThrownBy(
                            () ->
                                    ShoppingItem.add(
                                            102L,
                                            FAMILY,
                                            ShoppingList.FOOD,
                                            "Хлеб",
                                            stranger,
                                            ADDED))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Списки покупок наполняют люди. Автоматических переходов здесь нет и не должно быть. */
        @Test
        void theSystemMayNotAdd() {
            assertThatThrownBy(
                            () ->
                                    ShoppingItem.add(
                                            103L,
                                            FAMILY,
                                            ShoppingList.FOOD,
                                            "Хлеб",
                                            Actor.system(),
                                            ADDED))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Buying {

        @Test
        void markBoughtRecordsWhoAndWhen() {
            ShoppingItem item = milk();

            item.markBought(child(KID), NOW);

            assertThat(item.isBought()).isTrue();
            assertThat(item.boughtBy()).isEqualTo(KID);
            assertThat(item.boughtAt()).isEqualTo(NOW);
        }

        /**
         * Повторное нажатие не ошибка — как и у задач.
         *
         * <p>Список перерисовывается одним сообщением, и два тапа подряд по подтормаживающей связи
         * это норма, а не попытка что-то сломать. Но покупателя и время не переписываем: первым
         * купил тот, кто купил.
         */
        @Test
        void markingAnAlreadyBoughtItemKeepsTheFirstBuyer() {
            ShoppingItem item = milk();
            item.markBought(child(KID), NOW);

            item.markBought(parent(MOM), NOW.plusSeconds(60));

            assertThat(item.boughtBy()).isEqualTo(KID);
            assertThat(item.boughtAt()).isEqualTo(NOW);
        }

        @Test
        void unmarkBoughtClearsBuyerAndTime() {
            ShoppingItem item = milk();
            item.markBought(child(KID), NOW);

            item.unmarkBought(parent(MOM));

            assertThat(item.isBought()).isFalse();
            assertThat(item.boughtBy()).isNull();
            assertThat(item.boughtAt()).isNull();
        }

        @Test
        void unmarkingAnItemThatIsNotBoughtIsNotAnError() {
            ShoppingItem item = milk();

            assertThatCode(() -> item.unmarkBought(parent(MOM))).doesNotThrowAnyException();

            assertThat(item.isBought()).isFalse();
        }

        /** Вычёркивает кто угодно из семьи: купил — отметил, независимо от того, кто просил. */
        @Test
        void anyMemberOfTheFamilyMayTickAnItemOffRegardlessOfWhoAddedIt() {
            ShoppingItem item = milk();

            assertThatCode(() -> item.markBought(child(KID), NOW)).doesNotThrowAnyException();
        }

        @Test
        void anActorFromAnotherFamilyMayNotTickAnItemOff() {
            ShoppingItem item = milk();
            Actor stranger = Actor.member(KID, OTHER_FAMILY, Role.CHILD);

            assertThatThrownBy(() -> item.markBought(stranger, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThatThrownBy(() -> item.unmarkBought(stranger))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }
    }

    @Nested
    class Titles {

        @Test
        void aBlankTitleIsRejected() {
            assertThatThrownBy(
                            () ->
                                    ShoppingItem.add(
                                            104L,
                                            FAMILY,
                                            ShoppingList.FOOD,
                                            "   ",
                                            parent(MOM),
                                            ADDED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aTitleLongerThanTheLimitIsRejected() {
            String tooLong = "м".repeat(ShoppingItem.MAX_TITLE_LENGTH + 1);

            assertThatThrownBy(
                            () ->
                                    ShoppingItem.add(
                                            105L,
                                            FAMILY,
                                            ShoppingList.FOOD,
                                            tooLong,
                                            parent(MOM),
                                            ADDED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aTitleExactlyAtTheLimitIsAccepted() {
            String atLimit = "м".repeat(ShoppingItem.MAX_TITLE_LENGTH);

            assertThatCode(
                            () ->
                                    ShoppingItem.add(
                                            106L,
                                            FAMILY,
                                            ShoppingList.FOOD,
                                            atLimit,
                                            parent(MOM),
                                            ADDED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class Restoring {

        /** Путь из {@code RowMapper}: состояние берётся из строки как есть, без проверки прав. */
        @Test
        void restoreRebuildsABoughtItem() {
            ShoppingItem item =
                    ShoppingItem.restore(
                            200L,
                            FAMILY,
                            ShoppingList.HOUSEHOLD,
                            "Стиральный порошок",
                            MOM,
                            ADDED,
                            KID,
                            NOW);

            assertThat(item.isBought()).isTrue();
            assertThat(item.boughtBy()).isEqualTo(KID);
            assertThat(item.boughtAt()).isEqualTo(NOW);
        }

        @Test
        void restoreRebuildsAnOpenItem() {
            ShoppingItem item =
                    ShoppingItem.restore(
                            201L, FAMILY, ShoppingList.FOOD, "Хлеб", MOM, ADDED, null, null);

            assertThat(item.isBought()).isFalse();
            assertThat(item.boughtBy()).isNull();
        }
    }
}

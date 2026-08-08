package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryShoppingRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Юзкейс списков покупок на фейковых портах.
 *
 * <p>Главное здесь — многострочное добавление. Без него на каждую мелочь приходится четыре тапа, и
 * список покупок проигрывает бумажке на холодильнике.
 */
class ShoppingServiceTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;

    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");

    private final InMemoryShoppingRepository items = new InMemoryShoppingRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();

    private ShoppingService service;
    private Member mom;
    private Member kid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        service = new ShoppingService(items, Clock.fixed(NOW, ZoneOffset.UTC));

        mom = members.save(Member.join(10L, FAMILY, 100L, 100L, "Мама", Role.PARENT, NOW));
        kid = members.save(Member.join(12L, FAMILY, 102L, 102L, "Петя", Role.CHILD, NOW));
        stranger =
                members.save(
                        Member.join(90L, OTHER_FAMILY, 900L, 900L, "Чужой", Role.PARENT, NOW));
    }

    @Nested
    class Adding {

        @Test
        void oneLineAddsOneItem() {
            List<ShoppingItem> added = service.add(mom, ShoppingList.FOOD, "Молоко");

            assertThat(added).extracting(ShoppingItem::title).containsExactly("Молоко");
            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко");
        }

        /** Ради этого всё и затевалось: список с холодильника переносится одним сообщением. */
        @Test
        void severalLinesAddSeveralItemsInOrder() {
            service.add(mom, ShoppingList.FOOD, "Молоко\nХлеб\nСыр");

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко", "Хлеб", "Сыр");
        }

        @Test
        void blankLinesAndSurroundingSpacesAreIgnored() {
            service.add(mom, ShoppingList.FOOD, "  Молоко  \n\n   \n Хлеб\n");

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко", "Хлеб");
        }

        /** Список пишут в спешке, и «молоко» дважды в одном сообщении — описка, а не заказ на два. */
        @Test
        void duplicatesWithinOneMessageAreDropped() {
            service.add(mom, ShoppingList.FOOD, "Молоко\nхлеб\nМОЛОКО\nХлеб");

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко", "хлеб");
        }

        /** А вот повтор в разные дни — законный: молоко кончается снова. */
        @Test
        void anItemAlreadyInTheListMayBeAddedAgainLater() {
            service.add(mom, ShoppingList.FOOD, "Молоко");
            service.add(kid, ShoppingList.FOOD, "Молоко");

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко", "Молоко");
        }

        @Test
        void aChildAddsToEitherList() {
            service.add(kid, ShoppingList.HOUSEHOLD, "Мыло");

            assertThat(titles(ShoppingList.HOUSEHOLD)).containsExactly("Мыло");
        }

        @Test
        void anEmptyMessageAddsNothing() {
            assertThat(service.add(mom, ShoppingList.FOOD, "   \n\n ")).isEmpty();
            assertThat(titles(ShoppingList.FOOD)).isEmpty();
        }

        /**
         * Слишком длинная строка отвергает всё сообщение целиком.
         *
         * <p>Частичный успех непроверяем на глаз: человек увидит «добавлено 3» и не узнает, какая
         * из четырёх строк пропала.
         */
        @Test
        void aLineLongerThanTheLimitRejectsTheWholeMessage() {
            String tooLong = "м".repeat(ShoppingItem.MAX_TITLE_LENGTH + 1);

            assertThatThrownBy(() -> service.add(mom, ShoppingList.FOOD, "Молоко\n" + tooLong))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(titles(ShoppingList.FOOD)).isEmpty();
        }

        /**
         * Вставленный из заметок список на двести строк — не заказ, а промах.
         *
         * <p>Ограничение защищает не базу, а экран: клавиатура Telegram не резиновая, и на сотне
         * кнопок сообщение просто не отправится.
         */
        @Test
        void aMessageWithTooManyLinesIsRejectedWhole() {
            String many =
                    String.join(
                            "\n",
                            java.util.stream.IntStream.rangeClosed(
                                            1, ShoppingService.MAX_ITEMS_PER_MESSAGE + 1)
                                    .mapToObj(i -> "позиция " + i)
                                    .toList());

            assertThatThrownBy(() -> service.add(mom, ShoppingList.FOOD, many))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(titles(ShoppingList.FOOD)).isEmpty();
        }
    }

    @Nested
    class Toggling {

        @Test
        void togglingMarksBoughtAndThenReturnsItBack() {
            long id = service.add(mom, ShoppingList.FOOD, "Молоко").getFirst().id();

            ShoppingItem bought = service.toggle(kid, id);
            assertThat(bought.isBought()).isTrue();
            assertThat(bought.boughtBy()).isEqualTo(kid.id());

            ShoppingItem back = service.toggle(mom, id);
            assertThat(back.isBought()).isFalse();
        }

        /** Прямой id чужой позиции: {@code callback_data} недоверенный. */
        @Test
        void anItemOfAnotherFamilyIsNotFound() {
            long id = service.add(mom, ShoppingList.FOOD, "Молоко").getFirst().id();

            assertThatThrownBy(() -> service.toggle(stranger, id))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void anItemThatDoesNotExistIsNotFound() {
            assertThatThrownBy(() -> service.toggle(mom, 777L))
                    .isInstanceOf(DomainException.NotFound.class);
        }
    }

    @Nested
    class Clearing {

        @Test
        void clearingRemovesOnlyTheBoughtOnes() {
            List<ShoppingItem> added = service.add(mom, ShoppingList.FOOD, "Молоко\nХлеб");
            service.toggle(mom, added.getFirst().id());

            int removed = service.clearBought(mom, ShoppingList.FOOD);

            assertThat(removed).isEqualTo(1);
            assertThat(titles(ShoppingList.FOOD)).containsExactly("Хлеб");
        }

        @Test
        void clearingAnUntouchedListRemovesNothing() {
            service.add(mom, ShoppingList.FOOD, "Молоко");

            assertThat(service.clearBought(mom, ShoppingList.FOOD)).isZero();
            assertThat(titles(ShoppingList.FOOD)).hasSize(1);
        }

        /** Ребёнок очищает наравне со взрослым — список общий, права не делятся. */
        @Test
        void aChildMayClearTheList() {
            List<ShoppingItem> added = service.add(mom, ShoppingList.FOOD, "Молоко");
            service.toggle(kid, added.getFirst().id());

            assertThat(service.clearBought(kid, ShoppingList.FOOD)).isEqualTo(1);
        }
    }

    @Nested
    class Listing {

        @Test
        void listsAreIndependent() {
            service.add(mom, ShoppingList.FOOD, "Молоко");
            service.add(mom, ShoppingList.HOUSEHOLD, "Мыло");

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко");
            assertThat(titles(ShoppingList.HOUSEHOLD)).containsExactly("Мыло");
        }

        @Test
        void aStrangerSeesNothingOfOurList() {
            service.add(mom, ShoppingList.FOOD, "Молоко");

            assertThat(service.items(stranger, ShoppingList.FOOD)).isEmpty();
        }

        @Test
        void boughtItemsSinkToTheBottom() {
            List<ShoppingItem> added = service.add(mom, ShoppingList.FOOD, "Молоко\nХлеб\nСыр");
            service.toggle(mom, added.getFirst().id());

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Хлеб", "Сыр", "Молоко");
        }
    }

    private List<String> titles(ShoppingList list) {
        return service.items(mom, list).stream().map(ShoppingItem::title).toList();
    }
}

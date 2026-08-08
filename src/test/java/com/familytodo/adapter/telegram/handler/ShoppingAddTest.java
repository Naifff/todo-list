package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.ShoppingView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.ShoppingService;
import com.familytodo.application.fake.InMemoryShoppingRepository;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Наполнение списка и очистка купленного.
 *
 * <p>Многострочный ввод — смысл всей фичи: список с холодильника переносится одним сообщением, а не
 * четырьмя тапами на каждую мелочь.
 */
class ShoppingAddTest {

    private static final long FAMILY = 1L;
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final int LIST_MESSAGE = 555;

    private final InMemoryShoppingRepository items = new InMemoryShoppingRepository();
    private final DialogStateStore dialogs = new DialogStateStore();
    private final RecordingSender sender = new RecordingSender();

    private ShoppingService shopping;
    private ShoppingHandler handler;
    private Member mom;
    private Member kid;

    @BeforeEach
    void setUp() {
        shopping = new ShoppingService(items, Clock.fixed(NOW, ZoneOffset.UTC));
        handler = new ShoppingHandler(shopping, sender, dialogs);

        mom = Member.join(10L, FAMILY, 100L, 100L, "Мама", Role.PARENT, NOW);
        kid = Member.join(12L, FAMILY, 102L, 102L, "Петя", Role.CHILD, NOW);
    }

    @Nested
    class Adding {

        @Test
        void theAddButtonAsksWhatToBuy() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));

            assertThat(sender.texts).containsExactly(Texts.SHOP_ASK_ITEMS);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .containsInstanceOf(DialogState.AwaitingShoppingItems.class);
        }

        @Test
        void oneLineBecomesOneItem() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));

            assertThat(handler.continueDialog(text(mom, "Молоко"))).isTrue();

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко");
        }

        /** Ради этого всё и затевалось. */
        @Test
        void severalLinesBecomeSeveralItems() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));

            handler.continueDialog(text(mom, "Молоко\nХлеб\nСыр"));

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Молоко", "Хлеб", "Сыр");
        }

        /** Позиции попадают в тот список, из которого нажали кнопку. */
        @Test
        void itemsLandInTheListTheButtonCameFrom() {
            handler.handle(callback(mom), addTo(ShoppingList.HOUSEHOLD));

            handler.continueDialog(text(mom, "Мыло"));

            assertThat(titles(ShoppingList.HOUSEHOLD)).containsExactly("Мыло");
            assertThat(titles(ShoppingList.FOOD)).isEmpty();
        }

        /** Список живёт одним сообщением: после добавления переписывается он, а не шлётся новый. */
        @Test
        void theListMessageIsRewrittenRatherThanDuplicated() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));
            sender.clear();

            handler.continueDialog(text(mom, "Молоко"));

            assertThat(sender.edits).hasSize(1);
            assertThat(sender.edits.getFirst()).contains("Молоко");
        }

        @Test
        void theDialogEndsAfterTheItemsAreTaken() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));
            handler.continueDialog(text(mom, "Молоко"));

            assertThat(dialogs.get(mom.telegramUserId())).isEmpty();
        }

        /** Без начатого сценария свободный текст этому хендлеру не принадлежит. */
        @Test
        void textOutsideTheDialogIsNotOurs() {
            assertThat(handler.continueDialog(text(mom, "Молоко"))).isFalse();
            assertThat(titles(ShoppingList.FOOD)).isEmpty();
        }

        @Test
        void aChildFillsTheListToo() {
            handler.handle(callback(kid), addTo(ShoppingList.FOOD));
            handler.continueDialog(text(kid, "Чипсы"));

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Чипсы");
        }

        /**
         * Слишком длинная строка отвергает сообщение целиком, и человек остаётся в сценарии: иначе
         * пришлось бы заново открывать список и снова жать «Добавить».
         */
        @Test
        void aRejectedMessageKeepsTheDialogOpen() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));
            sender.clear();

            handler.continueDialog(text(mom, "м".repeat(ShoppingItem.MAX_TITLE_LENGTH + 1)));

            assertThat(titles(ShoppingList.FOOD)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.SHOP_ITEM_REJECTED);
            assertThat(dialogs.get(mom.telegramUserId()))
                    .describedAs("сценарий не оборвался")
                    .isPresent();
        }

        @Test
        void aMessageOfOnlyBlanksIsRejectedTheSameWay() {
            handler.handle(callback(mom), addTo(ShoppingList.FOOD));
            sender.clear();

            handler.continueDialog(text(mom, "   \n\n  "));

            assertThat(titles(ShoppingList.FOOD)).isEmpty();
            assertThat(sender.texts).containsExactly(Texts.SHOP_ITEM_REJECTED);
        }
    }

    @Nested
    class Clearing {

        @Test
        void clearingRemovesTheBoughtOnesAndRedraws() {
            List<ShoppingItem> added = shopping.add(mom, ShoppingList.FOOD, "Молоко\nХлеб");
            shopping.toggle(mom, added.getFirst().id());
            sender.clear();

            handler.handle(callback(mom), clear(ShoppingList.FOOD));

            assertThat(titles(ShoppingList.FOOD)).containsExactly("Хлеб");
            assertThat(sender.edits).hasSize(1);
        }

        @Test
        void clearingTouchesOnlyTheListItWasPressedIn() {
            List<ShoppingItem> food = shopping.add(mom, ShoppingList.FOOD, "Молоко");
            List<ShoppingItem> household = shopping.add(mom, ShoppingList.HOUSEHOLD, "Мыло");
            shopping.toggle(mom, food.getFirst().id());
            shopping.toggle(mom, household.getFirst().id());

            handler.handle(callback(mom), clear(ShoppingList.FOOD));

            assertThat(titles(ShoppingList.FOOD)).isEmpty();
            assertThat(titles(ShoppingList.HOUSEHOLD)).containsExactly("Мыло");
        }
    }

    @Nested
    class Buttons {

        /**
         * Кнопка очистки появляется, только когда есть что убирать.
         *
         * <p>Иначе нажатие ничего не делает и выглядит поломкой: подтверждения у кнопки нет, а
         * ответить «нечего убирать» через {@code answerCallbackQuery} отсюда нельзя.
         */
        @Test
        void theClearButtonAppearsOnlyWhenSomethingIsBought() {
            shopping.add(mom, ShoppingList.FOOD, "Молоко");

            assertThat(buttons(ShoppingList.FOOD)).doesNotContain(Texts.SHOP_CLEAR_BOUGHT);

            shopping.toggle(mom, items.findByList(FAMILY, ShoppingList.FOOD).getFirst().id());

            assertThat(buttons(ShoppingList.FOOD)).contains(Texts.SHOP_CLEAR_BOUGHT);
        }

        @Test
        void theAddButtonIsAlwaysThereIncludingOnAnEmptyList() {
            assertThat(buttons(ShoppingList.FOOD)).contains(Texts.SHOP_ADD);
        }

        private List<String> buttons(ShoppingList list) {
            List<ShoppingItem> found = items.findByList(FAMILY, list);
            InlineKeyboardMarkup markup =
                    ShoppingView.keyboard(list, found, Math.min(found.size(), ShoppingView.MAX_ITEMS));
            List<String> labels = new ArrayList<>();
            markup.getKeyboard().forEach(row -> row.forEach(button -> labels.add(button.getText())));
            return labels;
        }
    }

    // --- вспомогательное ---

    private List<String> titles(ShoppingList list) {
        return items.findByList(FAMILY, list).stream().map(ShoppingItem::title).toList();
    }

    private static CallbackData addTo(ShoppingList list) {
        return new CallbackData(ShoppingView.PREFIX, ShoppingView.ADD, list.name());
    }

    private static CallbackData clear(ShoppingList list) {
        return new CallbackData(ShoppingView.PREFIX, ShoppingView.CLEAR, list.name());
    }

    private BotRequest callback(Member member) {
        return request(member, "", Optional.of(LIST_MESSAGE), Optional.of("callback-1"));
    }

    private BotRequest text(Member member, String body) {
        return request(member, body, Optional.empty(), Optional.empty());
    }

    private BotRequest request(
            Member member, String body, Optional<Integer> messageId, Optional<String> queryId) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                body,
                Optional.empty(),
                Optional.empty(),
                messageId,
                queryId);
    }

    private static final class RecordingSender extends BotSender {
        private final List<String> texts = new ArrayList<>();
        private final List<String> edits = new ArrayList<>();

        RecordingSender() {
            super(mock(org.telegram.telegrambots.meta.generics.TelegramClient.class));
        }

        @Override
        public boolean send(long chatId, String html) {
            texts.add(html);
            return true;
        }

        @Override
        public boolean send(long chatId, String html, InlineKeyboardMarkup markup) {
            texts.add(html);
            return true;
        }

        @Override
        public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
            edits.add(html);
        }

        void clear() {
            texts.clear();
            edits.clear();
        }
    }
}

package com.familytodo.adapter.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.ShoppingView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.ShoppingService;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.application.fake.InMemoryShoppingRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
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
 * Экран списка покупок.
 *
 * <p>⚠️ Проверяется в том числе то, что {@code callback_data} недоверенный: кнопки отражают права,
 * но не обеспечивают их, и чужой номер обязан выглядеть как несуществующий.
 */
class ShoppingHandlerTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final int MESSAGE_ID = 555;

    private final InMemoryShoppingRepository items = new InMemoryShoppingRepository();
    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final RecordingSender sender = new RecordingSender();

    private ShoppingService shopping;
    private ShoppingHandler handler;
    private Member mom;
    private Member kid;
    private Member stranger;

    @BeforeEach
    void setUp() {
        shopping = new ShoppingService(items, Clock.fixed(NOW, ZoneOffset.UTC));
        handler = new ShoppingHandler(shopping, sender, new DialogStateStore());

        mom = members.save(Member.join(10L, FAMILY, 100L, 100L, "Мама", Role.PARENT, NOW));
        kid = members.save(Member.join(12L, FAMILY, 102L, 102L, "Петя", Role.CHILD, NOW));
        stranger =
                members.save(
                        Member.join(90L, OTHER_FAMILY, 900L, 900L, "Чужой", Role.PARENT, NOW));
    }

    @Nested
    class OpeningTheList {

        @Test
        void shopOpensTheFoodListStraightAway() {
            shopping.add(mom, ShoppingList.FOOD, "Молоко\nХлеб");

            handler.handle(command(mom));

            assertThat(sender.texts).hasSize(1);
            assertThat(sender.texts.getFirst()).contains(Texts.SHOP_FOOD_HEADER);
            assertThat(sender.texts.getFirst()).contains("Молоко").contains("Хлеб");
        }

        @Test
        void anEmptyListSaysSoInsteadOfShowingNothing() {
            handler.handle(command(mom));

            assertThat(sender.texts.getFirst()).contains(Texts.SHOP_EMPTY);
        }

        /** Ребёнок открывает общий список наравне со взрослым. */
        @Test
        void aChildSeesTheSharedList() {
            shopping.add(mom, ShoppingList.FOOD, "Молоко");

            handler.handle(command(kid));

            assertThat(sender.texts.getFirst()).contains("Молоко");
        }

        @Test
        void theOtherListIsOneButtonAway() {
            shopping.add(mom, ShoppingList.HOUSEHOLD, "Мыло");

            handler.handle(callback(mom), new CallbackData("sh", "l", ShoppingList.HOUSEHOLD.name()));

            assertThat(sender.edits).hasSize(1);
            assertThat(sender.edits.getFirst()).contains(Texts.SHOP_HOUSEHOLD_HEADER);
            assertThat(sender.edits.getFirst()).contains("Мыло");
        }
    }

    @Nested
    class Ticking {

        @Test
        void tappingAnItemMarksItBoughtAndRedrawsTheSameMessage() {
            long id = shopping.add(mom, ShoppingList.FOOD, "Молоко").getFirst().id();

            handler.handle(callback(kid), CallbackData.of("sh", "t", id));

            assertThat(items.findById(FAMILY, id).orElseThrow().isBought()).isTrue();
            assertThat(sender.edits).describedAs("список живёт одним сообщением").hasSize(1);
            assertThat(sender.texts).isEmpty();
        }

        @Test
        void tappingABoughtItemPutsItBack() {
            long id = shopping.add(mom, ShoppingList.FOOD, "Молоко").getFirst().id();
            handler.handle(callback(mom), CallbackData.of("sh", "t", id));

            handler.handle(callback(mom), CallbackData.of("sh", "t", id));

            assertThat(items.findById(FAMILY, id).orElseThrow().isBought()).isFalse();
        }

        /** После нажатия перерисовывается тот список, к которому позиция относится. */
        @Test
        void redrawingKeepsTheListTheItemBelongsTo() {
            long id = shopping.add(mom, ShoppingList.HOUSEHOLD, "Мыло").getFirst().id();

            handler.handle(callback(mom), CallbackData.of("sh", "t", id));

            assertThat(sender.edits.getFirst()).contains(Texts.SHOP_HOUSEHOLD_HEADER);
        }

        @Test
        void aBoughtItemIsMarkedAndSinksToTheBottom() {
            List<Long> ids =
                    shopping.add(mom, ShoppingList.FOOD, "Молоко\nХлеб").stream()
                            .map(item -> item.id())
                            .toList();

            handler.handle(callback(mom), CallbackData.of("sh", "t", ids.getFirst()));

            String rendered = sender.edits.getFirst();
            assertThat(rendered.indexOf("Хлеб")).isLessThan(rendered.indexOf("Молоко"));
            assertThat(rendered).contains(ShoppingView.BOUGHT_MARK);
        }
    }

    @Nested
    class UntrustedInput {

        /** Прямой id чужой позиции: ответ обязан быть «не найдено», а не «нельзя». */
        @Test
        void anItemOfAnotherFamilyIsNotFound() {
            long id = shopping.add(mom, ShoppingList.FOOD, "Молоко").getFirst().id();

            assertThatThrownBy(
                            () -> handler.handle(callback(stranger), CallbackData.of("sh", "t", id)))
                    .isInstanceOf(DomainException.NotFound.class);

            assertThat(items.findById(FAMILY, id).orElseThrow().isBought())
                    .describedAs("чужое нажатие ничего не изменило")
                    .isFalse();
        }

        @Test
        void anItemThatDoesNotExistIsNotFound() {
            assertThatThrownBy(
                            () -> handler.handle(callback(mom), CallbackData.of("sh", "t", 777L)))
                    .isInstanceOf(DomainException.NotFound.class);
        }

        @Test
        void anUnknownListNameIsRejected() {
            assertThatThrownBy(
                            () ->
                                    handler.handle(
                                            callback(mom), new CallbackData("sh", "l", "PHARMACY")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anUnknownActionIsRejected() {
            assertThatThrownBy(
                            () -> handler.handle(callback(mom), new CallbackData("sh", "zz", "1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Escaping {

        /**
         * ⚠️ Заголовок позиции — пользовательский текст в HTML-сообщении. Неэкранированный даёт
         * HTTP 400, то есть список не приходит вовсе.
         */
        @Test
        void anItemTitleWithMarkupIsEscaped() {
            shopping.add(mom, ShoppingList.FOOD, "<b>молоко</b> & хлеб");

            handler.handle(command(mom));

            assertThat(sender.texts.getFirst())
                    .contains("&lt;b&gt;молоко&lt;/b&gt; &amp; хлеб")
                    .doesNotContain("<b>молоко");
        }
    }

    // --- вспомогательное ---

    private BotRequest command(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "/shop",
                Optional.of("shop"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private BotRequest callback(Member member) {
        return new BotRequest(
                member.telegramUserId(),
                member.privateChatId(),
                member.displayName(),
                Optional.of(member),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(MESSAGE_ID),
                Optional.of("callback-1"));
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
    }
}

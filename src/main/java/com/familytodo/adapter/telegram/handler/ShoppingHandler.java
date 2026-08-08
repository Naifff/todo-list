package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.view.ShoppingView;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.ShoppingService;
import com.familytodo.domain.Member;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Списки покупок: {@code /shop}.
 *
 * <p>Команда открывает продовольственный список сразу, а не спрашивает какой — переход во второй
 * стоит одну кнопку, которая видна всегда. Промежуточный экран выбора добавлял бы тап к каждому
 * заходу ради вопроса, ответ на который в девяти случаях из десяти один и тот же.
 */
@Component
public class ShoppingHandler implements CommandHandler, CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(ShoppingHandler.class);

    private final ShoppingService shopping;
    private final BotSender sender;
    private final DialogStateStore dialogs;

    public ShoppingHandler(ShoppingService shopping, BotSender sender, DialogStateStore dialogs) {
        this.shopping = shopping;
        this.sender = sender;
        this.dialogs = dialogs;
    }

    @Override
    public Set<String> commands() {
        return Set.of("shop");
    }

    @Override
    public String prefix() {
        return ShoppingView.PREFIX;
    }

    /**
     * Оба интерфейса объявляют этот метод по умолчанию, и Java требует снять неоднозначность явно.
     * Ответ здесь простой: списки покупок — часть семьи, незнакомцу они не видны.
     */
    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        send(request, request.requireMember(), ShoppingList.FOOD);
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member member = request.requireMember();

        switch (data.action()) {
            case ShoppingView.OPEN -> edit(request, member, list(data.argument()));
            case ShoppingView.TOGGLE -> toggle(request, member, data.longArgument());
            case ShoppingView.ADD -> askForItems(request, list(data.argument()));
            case ShoppingView.CLEAR -> clear(request, member, list(data.argument()));
            default ->
                    throw new IllegalArgumentException("unknown shopping action " + data.action());
        }
    }

    /**
     * ⚠️ Позиция грузится заново и с проверкой актора: {@code callback_data} недоверенный, кнопки
     * отражают права, но не обеспечивают их. Чужой номер даст {@code NotFound} из юзкейса.
     */
    private void toggle(BotRequest request, Member member, long itemId) {
        ShoppingItem changed = shopping.toggle(member, itemId);
        edit(request, member, changed.list());
    }

    /**
     * Свободный текст принимаем только внутри начатого сценария — бот сам спросил, значит ждёт.
     *
     * <p>Отказ <b>не обрывает</b> сценарий: иначе после опечатки пришлось бы заново открывать
     * список и снова жать «Добавить».
     */
    @Override
    public boolean continueDialog(BotRequest request) {
        Optional<DialogState.AwaitingShoppingItems> awaiting =
                dialogs.get(request.telegramUserId())
                        .filter(DialogState.AwaitingShoppingItems.class::isInstance)
                        .map(DialogState.AwaitingShoppingItems.class::cast);
        if (awaiting.isEmpty()) {
            return false;
        }

        DialogState.AwaitingShoppingItems step = awaiting.get();
        List<ShoppingItem> added;
        try {
            added = shopping.add(request.requireMember(), step.list(), request.text());
        } catch (IllegalArgumentException e) {
            // ⚠️ только класс исключения и никакого текста: в нём лежит то, что человек написал
            log.warn("shopping items rejected: {}", e.getClass().getSimpleName());
            sender.send(request.chatId(), Texts.SHOP_ITEM_REJECTED);
            return true;
        }

        if (added.isEmpty()) {
            sender.send(request.chatId(), Texts.SHOP_ITEM_REJECTED);
            return true;
        }

        dialogs.clear(request.telegramUserId());
        redraw(request.chatId(), step.messageId(), request.requireMember(), step.list());
        return true;
    }

    private void askForItems(BotRequest request, ShoppingList list) {
        dialogs.put(
                request.telegramUserId(),
                new DialogState.AwaitingShoppingItems(
                        list,
                        request.messageId()
                                .orElseThrow(() -> new IllegalStateException("no message to edit"))));
        sender.send(request.chatId(), Texts.SHOP_ASK_ITEMS);
    }

    private void clear(BotRequest request, Member member, ShoppingList list) {
        shopping.clearBought(member, list);
        edit(request, member, list);
    }

    private void send(BotRequest request, Member member, ShoppingList list) {
        List<ShoppingItem> items = shopping.items(member, list);
        ShoppingView.Rendered rendered = ShoppingView.render(list, items);
        sender.send(
                request.chatId(),
                rendered.text(),
                ShoppingView.keyboard(list, items, rendered.shown()));
    }

    /** Список живёт одним сообщением: нажатие переписывает его, а не добавляет ещё одно. */
    private void edit(BotRequest request, Member member, ShoppingList list) {
        redraw(
                request.chatId(),
                request.messageId()
                        .orElseThrow(() -> new IllegalStateException("no message to edit")),
                member,
                list);
    }

    private void redraw(long chatId, int messageId, Member member, ShoppingList list) {
        List<ShoppingItem> items = shopping.items(member, list);
        ShoppingView.Rendered rendered = ShoppingView.render(list, items);
        sender.edit(chatId, messageId, rendered.text(), ShoppingView.keyboard(list, items, rendered.shown()));
    }

    /** Название списка приходит от клиента, поэтому разбирается строго. */
    private static ShoppingList list(String argument) {
        try {
            return ShoppingList.valueOf(argument);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown shopping list " + argument, e);
        }
    }
}

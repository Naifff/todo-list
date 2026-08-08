package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.view.ShoppingView;
import com.familytodo.application.ShoppingService;
import com.familytodo.domain.Member;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Списки покупок: {@code /shop}.
 *
 * <p>Команда открывает продовольственный список сразу, а не спрашивает какой — переход во второй
 * стоит одну кнопку, которая видна всегда. Промежуточный экран выбора добавлял бы тап к каждому
 * заходу ради вопроса, ответ на который в девяти случаях из десяти один и тот же.
 */
@Component
public class ShoppingHandler implements CommandHandler, CallbackHandler {

    private final ShoppingService shopping;
    private final BotSender sender;

    public ShoppingHandler(ShoppingService shopping, BotSender sender) {
        this.shopping = shopping;
        this.sender = sender;
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
        List<ShoppingItem> items = shopping.items(member, list);
        ShoppingView.Rendered rendered = ShoppingView.render(list, items);
        sender.edit(
                request.chatId(),
                request.messageId().orElseThrow(() -> new IllegalStateException("no message to edit")),
                rendered.text(),
                ShoppingView.keyboard(list, items, rendered.shown()));
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

package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.domain.ShoppingItem;
import com.familytodo.domain.ShoppingList;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Вёрстка списка покупок — одним сообщением, как и списки дел.
 *
 * <p>Позиции идут и текстом, и кнопками, и дублирование здесь осознанное. Текст читается и
 * пересылается целиком; кнопка — это цель для пальца в магазине, где нужно попасть в «молоко», а не
 * сверяться с номером в списке.
 */
public final class ShoppingView {

    public static final String PREFIX = "sh";

    /** Действия кнопок. Держим здесь же, чтобы вёрстка и разбор не разъехались. */
    public static final String OPEN = "l";

    public static final String TOGGLE = "t";

    public static final String ADD = "a";

    public static final String CLEAR = "c";

    /** Купленное помечается и в тексте, и на кнопке — иначе в магазине не видно с одного взгляда. */
    public static final String BOUGHT_MARK = "✅";

    private static final String OPEN_MARK = "•";

    /**
     * Больше не показываем. Ограничение защищает не базу, а сообщение: клавиатура Telegram не
     * резиновая, а список на сотню позиций всё равно не читается с телефона.
     */
    public static final int MAX_ITEMS = 30;

    /** На кнопке помещается немного: длинное название режется, полное остаётся в тексте. */
    private static final int BUTTON_TITLE = 24;

    private ShoppingView() {}

    /** @param shown сколько позиций попало в сообщение — по нему строится клавиатура */
    public record Rendered(String text, int shown) {}

    public static Rendered render(ShoppingList list, List<ShoppingItem> items) {
        StringBuilder text = new StringBuilder(header(list));
        if (items.isEmpty()) {
            return new Rendered(text.append("\n\n").append(Texts.SHOP_EMPTY).toString(), 0);
        }

        int shown = Math.min(items.size(), MAX_ITEMS);
        text.append("\n");
        for (int i = 0; i < shown; i++) {
            text.append('\n').append(line(items.get(i)));
        }
        if (items.size() > shown) {
            text.append("\n\n").append(Texts.SHOP_TRUNCATED);
        }
        return new Rendered(text.toString(), shown);
    }

    public static InlineKeyboardMarkup keyboard(ShoppingList list, List<ShoppingItem> items, int shown) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (int i = 0; i < shown; i++) {
            ShoppingItem item = items.get(i);
            InlineKeyboardRow row = new InlineKeyboardRow();
            row.add(
                    InlineKeyboardButton.builder()
                            .text(buttonTitle(item))
                            .callbackData(CallbackData.of(PREFIX, TOGGLE, item.id()).serialize())
                            .build());
            rows.add(row);
        }

        InlineKeyboardRow actions = new InlineKeyboardRow();
        actions.add(button(Texts.SHOP_ADD, ADD, list));
        // ⚠️ кнопка очистки появляется, только когда есть что убирать: иначе нажатие ничего не
        // делает и читается как поломка — ответить «нечего убирать» отсюда нечем
        if (items.stream().anyMatch(ShoppingItem::isBought)) {
            actions.add(button(Texts.SHOP_CLEAR_BOUGHT, CLEAR, list));
        }
        rows.add(actions);

        InlineKeyboardRow footer = new InlineKeyboardRow();
        ShoppingList other = list == ShoppingList.FOOD ? ShoppingList.HOUSEHOLD : ShoppingList.FOOD;
        footer.add(button(switchLabel(other), OPEN, other));
        rows.add(footer);

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton button(String label, String action, ShoppingList list) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, action, list.name()).serialize())
                .build();
    }

    public static String header(ShoppingList list) {
        return switch (list) {
            case FOOD -> Texts.SHOP_FOOD_HEADER;
            case HOUSEHOLD -> Texts.SHOP_HOUSEHOLD_HEADER;
        };
    }

    /**
     * ⚠️ Заголовок позиции — пользовательский текст в HTML-сообщении. Неэкранированный даёт HTTP
     * 400, то есть список не приходит вовсе.
     */
    private static String line(ShoppingItem item) {
        String title = HtmlEscaper.escape(item.title());
        return item.isBought()
                ? BOUGHT_MARK + " <s>" + title + "</s>"
                : OPEN_MARK + " " + title;
    }

    /**
     * Текст кнопки — не HTML: Telegram показывает его как есть, поэтому экранировать здесь нечего и
     * не нужно. Экранированная строка на кнопке выглядела бы как {@code &lt;b&gt;}.
     */
    private static String buttonTitle(ShoppingItem item) {
        String title = item.title();
        String trimmed =
                title.length() <= BUTTON_TITLE
                        ? title
                        : title.substring(0, BUTTON_TITLE - 1) + "…";
        return (item.isBought() ? BOUGHT_MARK + " " : "") + trimmed;
    }

    private static String switchLabel(ShoppingList other) {
        return switch (other) {
            case FOOD -> Texts.SHOP_SWITCH_TO_FOOD;
            case HOUSEHOLD -> Texts.SHOP_SWITCH_TO_HOUSEHOLD;
        };
    }
}

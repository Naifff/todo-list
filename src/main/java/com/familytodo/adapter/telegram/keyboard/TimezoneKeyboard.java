package com.familytodo.adapter.telegram.keyboard;

import com.familytodo.adapter.telegram.CallbackData;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Выбор часового пояса кнопками.
 *
 * <p>В {@code callback_data} едет <b>индекс</b>, а не идентификатор зоны: алфавит поля не допускает
 * {@code /}, а замена его на подчёркивание необратима — в мире есть {@code America/New_York}.
 * Индекс живёт ровно столько, сколько диалог (15 минут), так что рассинхронизации со списком между
 * деплоем и нажатием практически неоткуда взяться.
 */
public final class TimezoneKeyboard {

    public static final String PREFIX = "s";
    public static final String ACTION = "tz";

    /** От Калининграда до Камчатки: список закрывает всю страну и умещается в пять строк. */
    private static final List<Zone> ZONES =
            List.of(
                    new Zone("Калининград", "Europe/Kaliningrad"),
                    new Zone("Москва", "Europe/Moscow"),
                    new Zone("Самара", "Europe/Samara"),
                    new Zone("Екатеринбург", "Asia/Yekaterinburg"),
                    new Zone("Омск", "Asia/Omsk"),
                    new Zone("Красноярск", "Asia/Krasnoyarsk"),
                    new Zone("Иркутск", "Asia/Irkutsk"),
                    new Zone("Якутск", "Asia/Yakutsk"),
                    new Zone("Владивосток", "Asia/Vladivostok"),
                    new Zone("Магадан", "Asia/Magadan"),
                    new Zone("Камчатка", "Asia/Kamchatka"));

    private TimezoneKeyboard() {}

    public static InlineKeyboardMarkup markup() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < ZONES.size(); i += 2) {
            InlineKeyboardRow row = new InlineKeyboardRow(button(i));
            if (i + 1 < ZONES.size()) {
                row.add(button(i + 1));
            }
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /** Пустой результат — подделанный или устаревший индекс, а не сбой. */
    public static Optional<ZoneId> resolve(String argument) {
        try {
            int index = Integer.parseInt(argument);
            if (index < 0 || index >= ZONES.size()) {
                return Optional.empty();
            }
            return Optional.of(ZoneId.of(ZONES.get(index).zoneId()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static InlineKeyboardButton button(int index) {
        return InlineKeyboardButton.builder()
                .text(ZONES.get(index).label())
                .callbackData(CallbackData.of(PREFIX, ACTION, index).serialize())
                .build();
    }

    private record Zone(String label, String zoneId) {}
}

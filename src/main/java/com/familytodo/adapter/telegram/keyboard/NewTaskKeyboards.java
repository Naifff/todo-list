package com.familytodo.adapter.telegram.keyboard;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.domain.Member;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/** Клавиатуры сценария {@code /new}: выбор исполнителя и выбор срока. */
public final class NewTaskKeyboards {

    public static final String PREFIX = "n";
    public static final String ASSIGNEE = "who";
    public static final String DUE = "due";

    public static final String TODAY = "today";
    public static final String TOMORROW = "tomorrow";
    public static final String WEEKEND = "weekend";
    public static final String NONE = "none";
    public static final String CUSTOM = "custom";

    /** Повторение. */
    public static final String REPEAT = "rep";

    public static final String DAY = "day";
    public static final String ONCE = "once";
    public static final String DAILY = "daily";
    public static final String WEEKDAYS = "wd";
    public static final String PICK_DAYS = "pick";
    public static final String DAYS_DONE = "done";

    private NewTaskKeyboards() {}

    /**
     * Себя ставим первым и подписываем «Себе» — самый частый случай не должен требовать поиска
     * собственного имени в списке.
     *
     * <p>Имена участников — пользовательский текст, но в подпись кнопки разметка не попадает:
     * Telegram рисует её как обычный текст. Экранируем всё равно, чтобы вьюха вела себя одинаково.
     */
    public static InlineKeyboardMarkup assignees(List<Member> family, long selfId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(assigneeButton("Себе", selfId)));

        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Member member : family) {
            if (member.id() == selfId) {
                continue;
            }
            row.add(assigneeButton(HtmlEscaper.escape(member.displayName()), member.id()));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static InlineKeyboardMarkup dueDates() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(dueButton("Сегодня", TODAY), dueButton("Завтра", TOMORROW)))
                .keyboardRow(
                        new InlineKeyboardRow(
                                dueButton("В выходные", WEEKEND), dueButton("Без срока", NONE)))
                .keyboardRow(new InlineKeyboardRow(dueButton("Время и место", CUSTOM)))
                .build();
    }

    public static InlineKeyboardMarkup repeatOptions() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button("Не повторять", REPEAT, ONCE)))
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Каждый день", REPEAT, DAILY),
                                button("По будням", REPEAT, WEEKDAYS)))
                .keyboardRow(new InlineKeyboardRow(button("Свои дни", REPEAT, PICK_DAYS)))
                .build();
    }

    /** Отмеченные дни видны прямо на кнопках: отдельного текста «выбрано пн, ср» не нужно. */
    public static InlineKeyboardMarkup dayPicker(Set<DayOfWeek> chosen) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (DayOfWeek day : DayOfWeek.values()) {
            String label = SHORT_DAYS.get(day);
            row.add(
                    button(
                            chosen.contains(day) ? "· " + label + " ·" : label,
                            DAY,
                            Integer.toString(day.getValue())));
        }
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row)
                .keyboardRow(new InlineKeyboardRow(button("Готово", REPEAT, DAYS_DONE)))
                .build();
    }

    private static final Map<DayOfWeek, String> SHORT_DAYS =
            Map.of(
                    DayOfWeek.MONDAY, "Пн",
                    DayOfWeek.TUESDAY, "Вт",
                    DayOfWeek.WEDNESDAY, "Ср",
                    DayOfWeek.THURSDAY, "Чт",
                    DayOfWeek.FRIDAY, "Пт",
                    DayOfWeek.SATURDAY, "Сб",
                    DayOfWeek.SUNDAY, "Вс");

    private static InlineKeyboardButton button(String label, String action, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, action, argument).serialize())
                .build();
    }

    private static InlineKeyboardButton assigneeButton(String label, long memberId) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(CallbackData.of(PREFIX, ASSIGNEE, memberId).serialize())
                .build();
    }

    private static InlineKeyboardButton dueButton(String label, String choice) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, DUE, choice).serialize())
                .build();
    }
}

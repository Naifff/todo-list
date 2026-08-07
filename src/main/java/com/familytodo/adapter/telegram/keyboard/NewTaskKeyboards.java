package com.familytodo.adapter.telegram.keyboard;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.domain.Member;
import java.util.ArrayList;
import java.util.List;
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
                .keyboardRow(new InlineKeyboardRow(dueButton("Своя дата", CUSTOM)))
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

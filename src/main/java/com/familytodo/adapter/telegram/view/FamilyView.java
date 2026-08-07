package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/** Состав семьи, приглашения и настройки. */
public final class FamilyView {

    public static final String PREFIX = "f";
    public static final String MENU = "menu";
    public static final String INVITE = "inv";
    public static final String REMOVE = "rm";
    public static final String REMOVE_ASK = "rmask";
    public static final String REMOVE_DO = "rmdo";
    public static final String SETTINGS = "set";
    public static final String TIMEZONE = "tz";
    public static final String DIGEST = "digest";

    /** На сколько дней вперёд собирать утренний список. */
    public static final String HORIZON = "horiz";

    /** Часы, из которых выбирается дайджест. Ночь и день не предлагаем — это утреннее письмо. */
    public static final List<Integer> DIGEST_HOURS = List.of(6, 7, 8, 9, 10, 11);

    private FamilyView() {}

    public static String roster(Family family, List<Member> members) {
        StringBuilder out = new StringBuilder();
        out.append("<b>").append(HtmlEscaper.escape(family.name())).append("</b>\n");
        out.append("Часовой пояс: ").append(family.timezone().getId()).append('\n');
        out.append("Дайджест: ").append(family.digestTime()).append("\n\n");

        for (Member member : members) {
            out.append(member.role() == Role.PARENT ? "👤 " : "🧒 ");
            out.append(HtmlEscaper.escape(member.displayName()));
            out.append(member.role() == Role.PARENT ? " — родитель" : " — ребёнок");
            out.append('\n');
        }
        return out.toString();
    }

    public static InlineKeyboardMarkup menu(boolean isParent) {
        if (!isParent) {
            return InlineKeyboardMarkup.builder().keyboard(List.of()).build();
        }
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Позвать ребёнка", INVITE, "child"),
                                button("Позвать взрослого", INVITE, "parent")))
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Исключить", REMOVE, "0"),
                                button("Настройки", SETTINGS, "0")))
                .build();
    }

    public static InlineKeyboardMarkup members(List<Member> candidates, String action) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Member member : candidates) {
            rows.add(
                    new InlineKeyboardRow(
                            button(
                                    HtmlEscaper.escape(member.displayName()),
                                    action,
                                    Long.toString(member.id()))));
        }
        rows.add(new InlineKeyboardRow(button("← Назад", MENU, "0")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /** Исключение отменяет открытые дела участника — подтверждение здесь не формальность. */
    public static InlineKeyboardMarkup confirmRemoval(Member target) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button(
                                        "Да, исключить",
                                        REMOVE_DO,
                                        Long.toString(target.id())),
                                button("Отмена", MENU, "0")))
                .build();
    }

    public static InlineKeyboardMarkup settings() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Часовой пояс", TIMEZONE, "ask"),
                                button("Время дайджеста", DIGEST, "ask")))
                .keyboardRow(new InlineKeyboardRow(button("Горизонт дайджеста", HORIZON, "ask")))
                .keyboardRow(new InlineKeyboardRow(button("← Назад", MENU, "0")))
                .build();
    }

    public static InlineKeyboardMarkup digestHours() {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int hour : DIGEST_HOURS) {
            row.add(button(String.format("%02d:00", hour), DIGEST, Integer.toString(hour)));
        }
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row)
                .keyboardRow(new InlineKeyboardRow(button("← Назад", MENU, "0")))
                .build();
    }

    /** Значения берутся у домена: их проверяет правило, а не разметка кнопок. */
    public static InlineKeyboardMarkup digestHorizons() {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int days : Family.DIGEST_HORIZONS) {
            row.add(button(horizonLabel(days), HORIZON, Integer.toString(days)));
        }
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row)
                .keyboardRow(new InlineKeyboardRow(button("← Назад", MENU, "0")))
                .build();
    }

    private static String horizonLabel(int days) {
        return switch (days) {
            case 1 -> "День";
            case 3 -> "3 дня";
            case 7 -> "Неделя";
            default -> days + " дней";
        };
    }

    private static InlineKeyboardButton button(String label, String action, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, action, argument).serialize())
                .build();
    }
}

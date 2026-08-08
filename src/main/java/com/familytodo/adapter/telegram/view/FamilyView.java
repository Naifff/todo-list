package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberColor;
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

    /** Правка профиля участника: имя и цвет. */
    public static final String PROFILE = "who";

    public static final String PROFILE_PICK = "whopick";

    public static final String RENAME = "rename";

    public static final String COLORS = "colors";

    public static final String COLOR = "color";

    /** Часы, из которых выбирается дайджест. Ночь и день не предлагаем — это утреннее письмо. */
    public static final List<Integer> DIGEST_HOURS = List.of(6, 7, 8, 9, 10, 11);

    private FamilyView() {}

    public static String roster(Family family, List<Member> members) {
        StringBuilder out = new StringBuilder();
        out.append("<b>").append(HtmlEscaper.escape(family.name())).append("</b>\n");
        out.append("Часовой пояс: ").append(family.timezone().getId()).append('\n');
        out.append("Дайджест: ").append(family.digestTime()).append("\n\n");

        for (Member member : members) {
            out.append(dot(member.color())).append(' ');
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
                                button("Имя и цвет", PROFILE, "0"),
                                button("Исключить", REMOVE, "0")))
                .keyboardRow(new InlineKeyboardRow(button("Настройки", SETTINGS, "0")))
                .build();
    }

    /** Что можно сделать с выбранным участником. */
    public static InlineKeyboardMarkup profile(Member target) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Переименовать", RENAME, Long.toString(target.id())),
                                button("Цвет", COLORS, Long.toString(target.id()))))
                .keyboardRow(new InlineKeyboardRow(button("← Назад", MENU, "0")))
                .build();
    }

    /**
     * Палитра. Аргумент кнопки — {@code <id>-<ЦВЕТ>}: формат {@code prefix:action:argument} даёт
     * ровно одно поле, а нужны и участник, и выбор.
     */
    public static InlineKeyboardMarkup colors(Member target) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (MemberColor color : MemberColor.values()) {
            row.add(
                    button(
                            dot(color) + " " + color.title(),
                            COLOR,
                            target.id() + "-" + color.name()));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(new InlineKeyboardRow(button("← Назад", MENU, "0")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Кружок цвета для списка и кнопок.
     *
     * <p>Эмодзи, а не hex: в сообщении Telegram цвет задать нечем, а кружок виден и в списке, и на
     * кнопке. Точный оттенок живёт в файле расписания, здесь важно лишь различать.
     */
    public static String dot(MemberColor color) {
        return switch (color) {
            case BLUE -> "🔵";
            case RED -> "🔴";
            case GREEN -> "🟢";
            case PURPLE -> "🟣";
            case ORANGE -> "🟠";
            case TEAL -> "🩵";
            case MAROON -> "🟤";
            case OLIVE -> "🫒";
        };
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

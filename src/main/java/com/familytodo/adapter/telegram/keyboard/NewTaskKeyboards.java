package com.familytodo.adapter.telegram.keyboard;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.view.FamilyView;
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
    public static final String DUE = "due";

    /** Выбор исполнителей: отметить одного, закончить. */
    public static final String TOGGLE_ASSIGNEE = "wtog";

    public static final String ASSIGNEES_DONE = "wok";

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
     * Кто делает: список с отметками. Тап отмечает или снимает, экран остаётся, внизу «Дальше».
     *
     * <p>Себя ставим первым и подписываем «Себе» — самый частый случай не должен требовать поиска
     * собственного имени в списке.
     *
     * <p>⚠️ Отметки <b>всегда</b>, даже когда исполнитель один, — и это исправление, а не
     * первоначальный замысел. Сначала тап по имени выбирал одного и сразу вёл дальше, а несколько
     * выбирались отдельной кнопкой под именами: так экономилось нажатие на частом случае. С
     * телефона это не сработало ни разу — естественный жест «ткнуть в имя» случается раньше, чем
     * кнопку замечают, а после него добавить второго уже негде. Одно лишнее нажатие дешевле, чем
     * возможность, до которой не доходят.
     *
     * <p>Имена участников — пользовательский текст, но в подпись кнопки разметка не попадает:
     * Telegram рисует её как обычный текст. Экранируем всё равно, чтобы вьюха вела себя одинаково.
     */
    public static InlineKeyboardMarkup assignees(
            List<Member> family, long selfId, List<Long> chosen) {

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(
                new InlineKeyboardRow(
                        assigneeButton(
                                withColour(self(family, selfId), "Себе"), selfId, chosen)));

        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Member member : family) {
            if (member.id() == selfId) {
                continue;
            }
            row.add(
                    assigneeButton(
                            withColour(member, HtmlEscaper.escape(member.displayName())),
                            member.id(),
                            chosen));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(new InlineKeyboardRow(button("Дальше ▸", ASSIGNEES_DONE, "0")));
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

    /** Отмеченные видны прямо на кнопках — как и в выборе дней недели. */
    /**
     * Цвет участника кружком перед именем.
     *
     * <p>⚠️ Цвет текста кнопки Telegram задать не даёт — эмодзи единственный способ. Кружок тот же,
     * что в составе семьи и в расписании, поэтому «синий — это папа» узнаётся один раз на все
     * экраны. Отметки при этом остаются: цвет отвечает «кто», точки — «выбран».
     */
    private static String withColour(Member member, String label) {
        return member == null ? label : FamilyView.dot(member.color()) + " " + label;
    }

    /** Себя в списке может не оказаться только если состав пришёл неполным — тогда просто без цвета. */
    private static Member self(List<Member> family, long selfId) {
        return family.stream().filter(member -> member.id() == selfId).findFirst().orElse(null);
    }

    private static InlineKeyboardButton assigneeButton(
            String label, long memberId, List<Long> chosen) {
        return InlineKeyboardButton.builder()
                .text(chosen.contains(memberId) ? "· " + label + " ·" : label)
                .callbackData(CallbackData.of(PREFIX, TOGGLE_ASSIGNEE, memberId).serialize())
                .build();
    }

    private static InlineKeyboardButton dueButton(String label, String choice) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, DUE, choice).serialize())
                .build();
    }
}

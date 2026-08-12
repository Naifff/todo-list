package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Member;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.TaskSeries;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/** Как серия называется человеку — и экран, на котором ею можно распорядиться. */
public final class SeriesView {

    public static final String PREFIX = "sr";

    /** Действия кнопок. Рядом с вёрсткой, чтобы разбор и отрисовка не разъехались. */
    public static final String OPEN = "o";

    public static final String BACK = "b";

    public static final String STOP = "s";

    public static final String STOP_OK = "sok";

    public static final String END = "e";

    public static final String ENDLESS = "ne";

    /**
     * У «← Назад» аргумента нет — список один. Пустую строку {@link CallbackData} не принимает, а
     * заводить ради этого второй формат нечестнее, чем одна буква.
     */
    public static final String LIST = "l";

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** На кнопке помещается немного: длинное название режется, полное остаётся в тексте. */
    private static final int BUTTON_TITLE = 28;

    private SeriesView() {}

    public static String describe(TaskSeries series) {
        return "Повторяется " + rule(series.recurrence()) + " в " + series.startTime().format(TIME);
    }

    public static String rule(Recurrence recurrence) {
        if (recurrence.isDaily()) {
            return "каждый день";
        }
        if (recurrence.isWeekdays()) {
            return "по будням";
        }
        return "по дням: "
                + recurrence.days().stream()
                        .sorted()
                        .map(SeriesView::shortName)
                        .collect(Collectors.joining(", "));
    }

    /** Список правил. Как и у покупок: текст читается, кнопки нужны пальцу. */
    public static String list(List<TaskSeries> series, Map<Long, Member> byId) {
        StringBuilder out = new StringBuilder(Texts.SERIES_HEADER);
        if (series.isEmpty()) {
            return out.append("\n\n").append(Texts.SERIES_EMPTY).toString();
        }
        for (TaskSeries rule : series) {
            out.append("\n\n<b>").append(HtmlEscaper.escape(rule.title())).append("</b>\n");
            out.append(rule(rule.recurrence())).append(" в ").append(rule.startTime().format(TIME));
            out.append(" · ").append(names(rule, byId));
            if (rule.endsOn() != null) {
                out.append("\nдо ").append(rule.endsOn().format(DATE));
            }
        }
        return out.toString();
    }

    public static InlineKeyboardMarkup listKeyboard(List<TaskSeries> series) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (TaskSeries rule : series) {
            rows.add(
                    new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text(buttonTitle(rule))
                                    .callbackData(
                                            CallbackData.of(PREFIX, OPEN, rule.id()).serialize())
                                    .build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    public static String card(TaskSeries rule, Map<Long, Member> byId) {
        StringBuilder out = new StringBuilder();
        out.append("🔁 <b>").append(HtmlEscaper.escape(rule.title())).append("</b>\n\n");
        out.append(describe(rule)).append('\n');
        out.append("Делают: ").append(names(rule, byId)).append('\n');
        out.append("Просит: ").append(AssigneeNames.of(byId, rule.creatorId())).append('\n');
        if (rule.location() != null) {
            out.append("Где: ").append(HtmlEscaper.escape(rule.location())).append('\n');
        }
        out.append("До: ")
                .append(rule.endsOn() == null ? "без конца" : rule.endsOn().format(DATE));
        return out.toString();
    }

    public static InlineKeyboardMarkup cardKeyboard(TaskSeries rule) {
        InlineKeyboardRow limits = new InlineKeyboardRow(button(Texts.SERIES_END, END, rule.id()));
        // ⚠️ «Убрать границу» появляется, только когда граница есть: постоянная кнопка, которая
        // ничего не делает, читается как сломанная — ответить «нечего убирать» отсюда нечем
        if (rule.endsOn() != null) {
            limits.add(button(Texts.SERIES_ENDLESS, ENDLESS, rule.id()));
        }

        return InlineKeyboardMarkup.builder()
                .keyboardRow(limits)
                .keyboardRow(new InlineKeyboardRow(button(Texts.SERIES_STOP, STOP, rule.id())))
                .keyboardRow(new InlineKeyboardRow(backButton()))
                .build();
    }

    /** Подтверждение остановки: карточка остаётся на экране, чтобы было видно, что именно гасим. */
    public static String stopConfirmation(TaskSeries rule, Map<Long, Member> byId) {
        return card(rule, byId) + "\n\n" + Texts.SERIES_STOP_CONFIRM;
    }

    public static InlineKeyboardMarkup stopKeyboard(TaskSeries rule) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button(Texts.SERIES_STOP_OK, STOP_OK, rule.id()),
                                button(Texts.SERIES_STOP_CANCEL, OPEN, rule.id())))
                .build();
    }

    /**
     * Число ушедших дел — не подробность: остановка вечерней тренировки убирает из списков и
     * календаря семьи десяток дел сразу, и «серия остановлена» без числа этого не показывает.
     */
    public static String stopped(int removed) {
        return removed == 0
                ? Texts.SERIES_STOPPED
                : Texts.SERIES_STOPPED + " Убрано будущих дел: " + removed + ".";
    }

    /** То же число после сдвига границы: укороченная серия убирает дела ровно так же. */
    public static String limited(int removed) {
        return removed == 0 ? "" : "\n\nУбрано будущих дел: " + removed + ".";
    }

    private static InlineKeyboardButton button(String label, String action, long seriesId) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(CallbackData.of(PREFIX, action, seriesId).serialize())
                .build();
    }

    private static InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text(Texts.SERIES_BACK)
                .callbackData(new CallbackData(PREFIX, BACK, LIST).serialize())
                .build();
    }

    private static String names(TaskSeries rule, Map<Long, Member> byId) {
        return rule.assignees().stream()
                .map(Assignee::memberId)
                .map(memberId -> AssigneeNames.of(byId, memberId))
                .collect(Collectors.joining(", "));
    }

    /**
     * Текст кнопки — не HTML: Telegram показывает его как есть, поэтому экранировать здесь нечего.
     * Экранированная строка выглядела бы на кнопке как {@code &lt;b&gt;}.
     */
    private static String buttonTitle(TaskSeries rule) {
        String title = rule.title();
        return title.length() <= BUTTON_TITLE
                ? title
                : title.substring(0, BUTTON_TITLE - 1) + "…";
    }

    private static String shortName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, RU);
    }
}

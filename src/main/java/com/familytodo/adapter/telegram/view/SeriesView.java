package com.familytodo.adapter.telegram.view;

import com.familytodo.domain.Recurrence;
import com.familytodo.domain.TaskSeries;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.stream.Collectors;

/** Как серия называется человеку. */
public final class SeriesView {

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

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

    private static String shortName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, RU);
    }
}

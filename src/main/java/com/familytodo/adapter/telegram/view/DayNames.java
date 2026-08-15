package com.familytodo.adapter.telegram.view;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * Короткие названия дней недели.
 *
 * <p>⚠️ Заданы явно, а не взяты из {@code getDisplayName}: расписание печатается ровно в том виде, в
 * каком его принимает разбор, и локаль JDK не должна однажды прислать «понедельник» или «Mon».
 * Правка расписания делается копированием собственного сообщения, и разъехавшийся регистр или форма
 * превратили бы копию в неразбираемый текст.
 */
public final class DayNames {

    private static final Map<DayOfWeek, String> SHORT =
            Map.of(
                    DayOfWeek.MONDAY, "Пн",
                    DayOfWeek.TUESDAY, "Вт",
                    DayOfWeek.WEDNESDAY, "Ср",
                    DayOfWeek.THURSDAY, "Чт",
                    DayOfWeek.FRIDAY, "Пт",
                    DayOfWeek.SATURDAY, "Сб",
                    DayOfWeek.SUNDAY, "Вс");

    private DayNames() {}

    public static String shortName(DayOfWeek day) {
        return SHORT.get(day);
    }
}

package com.familytodo.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Правило повторения: в какие дни недели случается дело.
 *
 * <p>Все три правила v1 — ежедневно, по будням, по выбранным дням — это один и тот же набор дней.
 * Отдельных видов правила нет: «каждый день» это просто все семь. Разные представления в интерфейсе
 * не повод заводить разные правила в домене.
 *
 * <p>Месячных правил и «каждого второго вторника» нет сознательно: разбор таких правил стоит
 * дороже, чем польза для семейного списка.
 */
public record Recurrence(Set<DayOfWeek> days) {

    private static final Set<DayOfWeek> WORKING =
            EnumSet.of(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY);

    public Recurrence {
        if (days == null || days.isEmpty()) {
            // правило без дней не случается никогда — это не правило, а тихо мёртвая серия
            throw new IllegalArgumentException("recurrence needs at least one day of week");
        }
        days = Collections.unmodifiableSet(EnumSet.copyOf(days));
    }

    public static Recurrence daily() {
        return new Recurrence(EnumSet.allOf(DayOfWeek.class));
    }

    public static Recurrence weekdays() {
        return new Recurrence(WORKING);
    }

    public static Recurrence on(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("recurrence needs at least one day of week");
        }
        return new Recurrence(days);
    }

    public boolean occursOn(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }

    public boolean isDaily() {
        return days.size() == 7;
    }

    public boolean isWeekdays() {
        return days.equals(WORKING);
    }

    /**
     * Форма для колонки: номера дней ISO через запятую, всегда по возрастанию. Порядок фиксирован,
     * чтобы одно и то же правило давало одну и ту же строку — иначе сравнение серий в базе врёт.
     */
    public String stored() {
        return days.stream()
                .sorted()
                .map(day -> Integer.toString(day.getValue()))
                .collect(Collectors.joining(","));
    }

    public static Recurrence parse(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("recurrence is empty");
        }
        try {
            Set<DayOfWeek> days =
                    Arrays.stream(stored.split(","))
                            .map(String::strip)
                            .map(Integer::parseInt)
                            .map(DayOfWeek::of)
                            .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
            return on(days);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            // молча подставить «каждый день» значило бы завалить человека делами, которых он не просил
            throw new IllegalArgumentException("unreadable recurrence: " + stored.length() + " chars", e);
        }
    }
}

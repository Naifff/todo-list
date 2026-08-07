package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Правило повторения.
 *
 * <p>Все три правила v1 — ежедневно, по будням, по выбранным дням — это один и тот же набор дней
 * недели. Отдельных видов правила нет: «каждый день» это просто все семь.
 *
 * <p>Месячных правил и «каждого второго вторника» нет сознательно: разбор таких правил стоит
 * дороже, чем польза для семейного списка.
 */
class RecurrenceTest {

    /** Понедельник 31 августа 2026 — вся неделя ниже отсчитывается от него. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    @Nested
    class Rules {

        @Test
        void dailyOccursEveryDayOfTheWeek() {
            Recurrence daily = Recurrence.daily();

            for (int i = 0; i < 7; i++) {
                assertThat(daily.occursOn(MONDAY.plusDays(i)))
                        .describedAs(MONDAY.plusDays(i).getDayOfWeek().toString())
                        .isTrue();
            }
        }

        @Test
        void weekdaysSkipTheWeekend() {
            Recurrence weekdays = Recurrence.weekdays();

            assertThat(weekdays.occursOn(MONDAY)).isTrue();
            assertThat(weekdays.occursOn(MONDAY.plusDays(4))).isTrue(); // пятница
            assertThat(weekdays.occursOn(MONDAY.plusDays(5))).isFalse(); // суббота
            assertThat(weekdays.occursOn(MONDAY.plusDays(6))).isFalse(); // воскресенье
        }

        @Test
        void chosenDaysOccurOnlyOnThoseDays() {
            Recurrence twiceAWeek = Recurrence.on(Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY));

            assertThat(twiceAWeek.occursOn(MONDAY)).isFalse();
            assertThat(twiceAWeek.occursOn(MONDAY.plusDays(1))).isTrue();
            assertThat(twiceAWeek.occursOn(MONDAY.plusDays(2))).isFalse();
            assertThat(twiceAWeek.occursOn(MONDAY.plusDays(3))).isTrue();
        }

        /** «Каждый день» и «все семь дней» — одно и то же правило, а не два похожих. */
        @Test
        void dailyEqualsAllSevenDays() {
            assertThat(Recurrence.daily()).isEqualTo(Recurrence.on(EnumSet.allOf(DayOfWeek.class)));
        }

        @Test
        void weekdaysEqualsMondayThroughFriday() {
            assertThat(Recurrence.weekdays())
                    .isEqualTo(
                            Recurrence.on(
                                    EnumSet.of(
                                            DayOfWeek.MONDAY,
                                            DayOfWeek.TUESDAY,
                                            DayOfWeek.WEDNESDAY,
                                            DayOfWeek.THURSDAY,
                                            DayOfWeek.FRIDAY)));
        }
    }

    @Nested
    class Validation {

        /** Правило без дней не повторяется никогда — это не правило, а тихо мёртвая серия. */
        @Test
        void emptyDaysAreRejected() {
            assertThatThrownBy(() -> Recurrence.on(Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullDaysAreRejected() {
            assertThatThrownBy(() -> Recurrence.on(null)).isInstanceOf(IllegalArgumentException.class);
        }

        /** Набор внутри правила неизменяем: серия живёт в базе месяцами. */
        @Test
        void daysCannotBeChangedFromOutside() {
            Set<DayOfWeek> mutable = EnumSet.of(DayOfWeek.MONDAY);
            Recurrence recurrence = Recurrence.on(mutable);

            mutable.add(DayOfWeek.SUNDAY);

            assertThat(recurrence.occursOn(MONDAY.plusDays(6))).isFalse();
            assertThatThrownBy(() -> recurrence.days().add(DayOfWeek.SUNDAY))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Storage {

        /** Правило едет в одну текстовую колонку — и обязано читаться обратно тем же. */
        @Test
        void roundTripsThroughItsStoredForm() {
            for (Recurrence rule :
                    List.of(
                            Recurrence.daily(),
                            Recurrence.weekdays(),
                            Recurrence.on(Set.of(DayOfWeek.SUNDAY)),
                            Recurrence.on(Set.of(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)))) {
                assertThat(Recurrence.parse(rule.stored())).isEqualTo(rule);
            }
        }

        @Test
        void storedFormIsStableRegardlessOfInputOrder() {
            Recurrence one = Recurrence.on(Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY));
            Recurrence other = Recurrence.on(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));

            assertThat(one.stored()).isEqualTo(other.stored());
        }

        @Test
        void garbageInTheColumnIsRejectedLoudly() {
            assertThatThrownBy(() -> Recurrence.parse("каждый-вторник"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Recurrence.parse("")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

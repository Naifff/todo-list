package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Урок.
 *
 * <p>Это <b>не задача</b>, и разница не в размере: у урока нет исполнителя (никто не просил ребёнка
 * сделать алгебру), от него нельзя отказаться с причиной и его нельзя «сделать» — он просто идёт.
 * Шесть уроков на пять дней на тридцать пять недель это тысяча строк в год на ребёнка, и строками
 * {@code task} они засорили бы ровно те экраны, ради которых бот делался.
 */
class LessonTest {

    private static final long FAMILY = 1L;
    private static final long KID = 12L;
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** 1 сентября 2026 — вторник. */
    private static final LocalDate FIRST_OF_SEPTEMBER = LocalDate.of(2026, 9, 1);

    @Nested
    class Validation {

        @Test
        void subjectIsRequired() {
            assertThatThrownBy(() -> lesson(" ", LocalTime.of(8, 30), LocalTime.of(9, 15)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aLessonThatEndsBeforeItStartsIsRejected() {
            assertThatThrownBy(
                            () -> lesson("Математика", LocalTime.of(9, 15), LocalTime.of(8, 30)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** Урок нулевой длины — это не урок, а опечатка в звонках. */
        @Test
        void aLessonOfZeroLengthIsRejected() {
            assertThatThrownBy(
                            () -> lesson("Математика", LocalTime.of(8, 30), LocalTime.of(8, 30)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class WhenItHappens {

        /** Расписание — правило: конкретный день получается наложением на дату. */
        @Test
        void aTuesdayLessonHappensOnTuesday() {
            Lesson lesson = lesson("Математика", LocalTime.of(8, 30), LocalTime.of(9, 15));

            assertThat(lesson.occursOn(FIRST_OF_SEPTEMBER)).isTrue();
            assertThat(lesson.occursOn(FIRST_OF_SEPTEMBER.plusDays(1))).isFalse();
        }

        @Test
        void theMomentIsCountedInTheFamilyZone() {
            Lesson lesson = lesson("Математика", LocalTime.of(8, 30), LocalTime.of(9, 15));

            assertThat(lesson.startOf(FIRST_OF_SEPTEMBER, MOSCOW))
                    .isEqualTo(Instant.parse("2026-09-01T05:30:00Z"));
            assertThat(lesson.endOf(FIRST_OF_SEPTEMBER, MOSCOW))
                    .isEqualTo(Instant.parse("2026-09-01T06:15:00Z"));
        }

        /** ⚠️ До начала учебного года уроков нет: правило не действует задним числом. */
        @Test
        void nothingBeforeTheScheduleStarts() {
            Lesson lesson = lesson("Математика", LocalTime.of(8, 30), LocalTime.of(9, 15));

            assertThat(lesson.occursOn(LocalDate.of(2026, 8, 25))).isFalse();
        }

        /** И ничего после последнего дня: летом расписание не показывается, но и не стирается. */
        @Test
        void nothingAfterTheScheduleEnds() {
            Lesson lesson =
                    Lesson.create(
                            1L,
                            FAMILY,
                            KID,
                            DayOfWeek.TUESDAY,
                            LocalTime.of(8, 30),
                            LocalTime.of(9, 15),
                            "Математика",
                            FIRST_OF_SEPTEMBER,
                            LocalDate.of(2027, 5, 31),
                            NOW);

            assertThat(lesson.occursOn(LocalDate.of(2027, 6, 2))).isFalse();
            assertThat(lesson.occursOn(LocalDate.of(2027, 5, 25))).isTrue();
        }

        @Test
        void withoutAnEndDateItRunsIndefinitely() {
            Lesson lesson = lesson("Математика", LocalTime.of(8, 30), LocalTime.of(9, 15));

            assertThat(lesson.occursOn(LocalDate.of(2030, 9, 3))).isTrue();
        }
    }

    private static Lesson lesson(String subject, LocalTime from, LocalTime to) {
        return Lesson.create(
                1L,
                FAMILY,
                KID,
                DayOfWeek.TUESDAY,
                from,
                to,
                subject,
                FIRST_OF_SEPTEMBER,
                null,
                NOW);
    }
}

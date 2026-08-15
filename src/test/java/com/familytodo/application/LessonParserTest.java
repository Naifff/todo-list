package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Разбор расписания, написанного одним сообщением.
 *
 * <p>Ввод — главный риск этой фичи: экран, на котором тридцать уроков заводятся по одному, до
 * пользователя не доходит. Поэтому расписание пишется как список покупок, целиком, и принимается
 * <b>целиком или отвергается целиком</b>: «добавлено 27» при тридцати строках оставляет человека
 * гадать, какие три пропали.
 *
 * <p>⚠️ Звонки нигде не хранятся. Они живут в самом сообщении и нужны только чтобы расставить
 * предметы по позициям; в разобранный урок уезжает уже конкретное время.
 */
class LessonParserTest {

    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    private final LessonParser parser = new LessonParser();

    @Nested
    class TheGrid {

        @Test
        void subjectsAreLaidOutOverTheBells() {
            LessonParser.Schedule schedule =
                    parser.parse(
                                    """
                                    Звонки: 08:30, 09:25, 10:30
                                    Пн: математика, русский, физра
                                    """,
                                    SEPTEMBER)
                            .orElseThrow();

            assertThat(schedule.lessons()).hasSize(3);
            assertThat(schedule.lessons().getFirst().subject()).isEqualTo("математика");
            assertThat(schedule.lessons().getFirst().day()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(schedule.lessons().getFirst().startsAt()).isEqualTo(LocalTime.of(8, 30));
            assertThat(schedule.lessons().getFirst().endsAt()).isEqualTo(LocalTime.of(9, 15));
        }

        /** Звонок можно задать интервалом — тогда длительность не угадывается. */
        @Test
        void aBellMayCarryItsOwnEnd() {
            LessonParser.Schedule schedule =
                    parser.parse(
                                    """
                                    Звонки: 08:30-09:15, 09:25-11:10
                                    Пн: математика, трудЫ
                                    """,
                                    SEPTEMBER)
                            .orElseThrow();

            assertThat(schedule.lessons().get(1).endsAt()).isEqualTo(LocalTime.of(11, 10));
        }

        /** Прочерк — окно: звонок пропускается, а не сдвигает остальные. */
        @Test
        void aDashSkipsTheBell() {
            LessonParser.Schedule schedule =
                    parser.parse(
                                    """
                                    Звонки: 08:30, 09:25, 10:30
                                    Пн: -, русский, физра
                                    """,
                                    SEPTEMBER)
                            .orElseThrow();

            assertThat(schedule.lessons()).hasSize(2);
            assertThat(schedule.lessons().getFirst().subject()).isEqualTo("русский");
            assertThat(schedule.lessons().getFirst().startsAt()).isEqualTo(LocalTime.of(9, 25));
        }

        /** Предметов больше, чем звонков, — не молчаливая обрезка, а отказ. */
        @Test
        void moreSubjectsThanBellsIsRefused() {
            assertThat(
                            parser.parse(
                                    """
                                    Звонки: 08:30
                                    Пн: математика, русский
                                    """,
                                    SEPTEMBER))
                    .isEmpty();
        }

        /** ⚠️ Сетка без звонков неразрешима — и молчать об этом нельзя. */
        @Test
        void aDayLineWithoutBellsIsRefused() {
            assertThat(parser.parse("Пн: математика, русский", SEPTEMBER)).isEmpty();
        }
    }

    @Nested
    class ExplicitTime {

        @Test
        void aLineWithItsOwnTimeNeedsNoBells() {
            LessonParser.Schedule schedule =
                    parser.parse("Вт 07:40 Нулевой английский", SEPTEMBER).orElseThrow();

            assertThat(schedule.lessons()).hasSize(1);
            assertThat(schedule.lessons().getFirst().day()).isEqualTo(DayOfWeek.TUESDAY);
            assertThat(schedule.lessons().getFirst().startsAt()).isEqualTo(LocalTime.of(7, 40));
            assertThat(schedule.lessons().getFirst().subject()).isEqualTo("Нулевой английский");
        }

        @Test
        void anIntervalIsTakenAsIs() {
            LessonParser.Schedule schedule =
                    parser.parse("Ср 09:25-11:10 Трудовое обучение", SEPTEMBER).orElseThrow();

            assertThat(schedule.lessons().getFirst().endsAt()).isEqualTo(LocalTime.of(11, 10));
        }

        /** Оба формата в одном сообщении — ровно то, ради чего выбран этот вариант. */
        @Test
        void bothFormsLiveInOneMessage() {
            LessonParser.Schedule schedule =
                    parser.parse(
                                    """
                                    Звонки: 08:30, 09:25

                                    Пн: математика, русский
                                    Вт 07:40 Нулевой английский
                                    Вт: математика, физра
                                    """,
                                    SEPTEMBER)
                            .orElseThrow();

            assertThat(schedule.lessons()).hasSize(5);
            assertThat(schedule.lessons())
                    .extracting(lesson -> lesson.day() + " " + lesson.startsAt())
                    .contains("TUESDAY 07:40", "TUESDAY 08:30");
        }
    }

    @Nested
    class Boundaries {

        @Test
        void theLastDayCanBeNamed() {
            LessonParser.Schedule schedule =
                    parser.parse(
                                    """
                                    До: 31.05.2027
                                    Пн 08:30 Математика
                                    """,
                                    SEPTEMBER)
                            .orElseThrow();

            assertThat(schedule.validTo()).isEqualTo(LocalDate.of(2027, 5, 31));
        }

        @Test
        void withoutThatLineTheScheduleHasNoEnd() {
            LessonParser.Schedule schedule =
                    parser.parse("Пн 08:30 Математика", SEPTEMBER).orElseThrow();

            assertThat(schedule.validTo()).isNull();
        }
    }

    @Nested
    class AllOrNothing {

        /** ⚠️ Одна испорченная строка отвергает сообщение целиком: частичный успех непроверяем. */
        @Test
        void oneBadLineRefusesTheWholeMessage() {
            assertThat(
                            parser.parse(
                                    """
                                    Звонки: 08:30, 09:25
                                    Пн: математика, русский
                                    Кaskdjf
                                    """,
                                    SEPTEMBER))
                    .isEmpty();
        }

        @Test
        void anEmptyMessageIsRefused() {
            assertThat(parser.parse("   ", SEPTEMBER)).isEmpty();
        }

        @Test
        void aMessageWithoutASingleLessonIsRefused() {
            assertThat(parser.parse("Звонки: 08:30, 09:25", SEPTEMBER)).isEmpty();
        }

        @Test
        void anUnknownWeekdayIsRefused() {
            assertThat(parser.parse("Понедельникус 08:30 Математика", SEPTEMBER)).isEmpty();
        }

        /** Длинный предмет отвергается разбором, а не обрезается молча. */
        @Test
        void aSubjectLongerThanTheDomainAllowsIsRefused() {
            assertThat(parser.parse("Пн 08:30 " + "я".repeat(61), SEPTEMBER)).isEmpty();
        }
    }

    @Nested
    class DayNames {

        @Test
        void everyWeekdayIsUnderstoodShortAndLong() {
            assertThat(day("Пн")).isEqualTo(DayOfWeek.MONDAY);
            assertThat(day("вт")).isEqualTo(DayOfWeek.TUESDAY);
            assertThat(day("Ср")).isEqualTo(DayOfWeek.WEDNESDAY);
            assertThat(day("чт")).isEqualTo(DayOfWeek.THURSDAY);
            assertThat(day("Пт")).isEqualTo(DayOfWeek.FRIDAY);
            assertThat(day("сб")).isEqualTo(DayOfWeek.SATURDAY);
            assertThat(day("Вс")).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(day("понедельник")).isEqualTo(DayOfWeek.MONDAY);
            assertThat(day("Суббота")).isEqualTo(DayOfWeek.SATURDAY);
        }

        private DayOfWeek day(String written) {
            return parser.parse(written + " 08:30 Математика", SEPTEMBER)
                    .orElseThrow()
                    .lessons()
                    .getFirst()
                    .day();
        }
    }
}

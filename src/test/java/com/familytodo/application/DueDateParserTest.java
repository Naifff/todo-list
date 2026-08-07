package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Самые тонкие правила времени во всём проекте. Всё на фиксированных часах, без {@code
 * Thread.sleep}.
 *
 * <p>Опорная точка: пятница 7 августа 2026, 12:00 по Москве (09:00 UTC).
 */
class DueDateParserTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow"); // UTC+3, без перехода на лето
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin"); // с переходом

    /** Пятница, 12:00 по Москве. */
    private static final Instant NOON_FRIDAY = Instant.parse("2026-08-07T09:00:00Z");

    private final DueDateParser parser = new DueDateParser(Clock.fixed(NOON_FRIDAY, ZoneOffset.UTC));

    /** Ночной интервал в правке дела — та же история, что и при создании. */
    @Nested
    class NightSlot {

        @Test
        void endBeforeStartMeansTheNextMorning() {
            DueDateParser.Slot slot =
                    parser.parseSlot("22:40-8:00 кровать", MOSCOW, LocalDate.of(2026, 8, 7))
                            .orElseThrow();

            assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-08-07T19:40:00Z"));
            assertThat(slot.endsAt()).isEqualTo(Instant.parse("2026-08-08T05:00:00Z"));
            assertThat(slot.location()).isEqualTo("кровать");
        }

        @Test
        void equalStartAndEndIsRejected() {
            assertThat(parser.parseSlot("22:40-22:40", MOSCOW, LocalDate.of(2026, 8, 7))).isEmpty();
        }
    }

    @Nested
    class Shortcuts {

        /**
         * Полночь сделала бы задачу мгновенно просроченной, 23:59 будило бы семью ночью. 19:00 —
         * вечер, когда дела ещё можно сделать.
         */
        @Test
        void todayMeansSevenInTheEveningLocal() {
            Instant due = parser.today(MOSCOW);

            assertThat(due).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }

        /** Граница суток — типовое место ошибки: в UTC+3 «завтра» начинается на три часа раньше. */
        @Test
        void tomorrowCrossesTheDayBoundaryCorrectly() {
            Instant due = parser.tomorrow(MOSCOW);

            assertThat(due).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
        }

        /** В пятницу «в выходные» — это ближайшая суббота. */
        @Test
        void weekendFromFridayIsSaturday() {
            Instant due = parser.weekend(MOSCOW);

            assertThat(due).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
        }

        /** А в субботу и воскресенье выходные уже идут — переносить на следующие незачем. */
        @Test
        void weekendOnAWeekendIsToday() {
            DueDateParser saturday =
                    new DueDateParser(
                            Clock.fixed(Instant.parse("2026-08-08T09:00:00Z"), ZoneOffset.UTC));
            DueDateParser sunday =
                    new DueDateParser(
                            Clock.fixed(Instant.parse("2026-08-09T09:00:00Z"), ZoneOffset.UTC));

            assertThat(saturday.weekend(MOSCOW)).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
            assertThat(sunday.weekend(MOSCOW)).isEqualTo(Instant.parse("2026-08-09T16:00:00Z"));
        }

        @Test
        void shortcutsRespectTheFamilyTimezone() {
            Instant moscow = parser.today(MOSCOW);
            Instant utc = parser.today(ZoneOffset.UTC);

            assertThat(moscow).isBefore(utc);
            assertThat(utc).isEqualTo(Instant.parse("2026-08-07T19:00:00Z"));
        }
    }

    @Nested
    class ManualInput {

        @Test
        void parsesDayAndMonth() {
            assertThat(parser.parse("15.08", MOSCOW))
                    .contains(Instant.parse("2026-08-15T16:00:00Z"));
        }

        @Test
        void parsesDayMonthAndTime() {
            assertThat(parser.parse("15.08 18:30", MOSCOW))
                    .contains(Instant.parse("2026-08-15T15:30:00Z"));
        }

        @Test
        void parsesFullDate() {
            assertThat(parser.parse("15.08.2027", MOSCOW))
                    .contains(Instant.parse("2027-08-15T16:00:00Z"));
        }

        /** Дата без года в прошлом означает следующий год, а не просрочку годичной давности. */
        @Test
        void rollsPastDateToNextYear() {
            assertThat(parser.parse("01.03", MOSCOW))
                    .contains(Instant.parse("2027-03-01T16:00:00Z"));
        }

        @Test
        void parsesTimeOnlyAsToday() {
            assertThat(parser.parse("18:30", MOSCOW))
                    .contains(Instant.parse("2026-08-07T15:30:00Z"));
        }

        /** Время, которое уже прошло, значит завтра — иначе задача просрочена в момент создания. */
        @Test
        void timeAlreadyPassedMeansTomorrow() {
            assertThat(parser.parse("09:00", MOSCOW))
                    .contains(Instant.parse("2026-08-08T06:00:00Z"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "", "  ", "когда-нибудь", "32.13", "15.08 25:00", "99:99", "15/08", "-1"
                })
        void rejectsGarbage(String input) {
            assertThat(parser.parse(input, MOSCOW)).isEmpty();
        }
    }

    @Nested
    class Slots {

        private static final java.time.LocalDate DAY = java.time.LocalDate.of(2026, 9, 1);

        @Test
        void parsesIntervalWithLocation() {
            DueDateParser.Slot slot =
                    parser.parseSlot("08:00-08:40 школа", MOSCOW, DAY).orElseThrow();

            assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-09-01T05:00:00Z"));
            assertThat(slot.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
            assertThat(slot.location()).isEqualTo("школа");
        }

        @Test
        void parsesOpenEndedTime() {
            DueDateParser.Slot slot = parser.parseSlot("19:00 дом", MOSCOW, DAY).orElseThrow();

            assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-09-01T16:00:00Z"));
            assertThat(slot.endsAt()).isNull();
            assertThat(slot.location()).isEqualTo("дом");
        }

        /** Место без времени — обычный случай: «Zoom» без расписания. */
        @Test
        void parsesLocationOnly() {
            DueDateParser.Slot slot = parser.parseSlot("Zoom", MOSCOW, DAY).orElseThrow();

            assertThat(slot.startsAt()).isNull();
            assertThat(slot.location()).isEqualTo("Zoom");
        }

        @Test
        void parsesTimeOnly() {
            DueDateParser.Slot slot = parser.parseSlot("08:00-08:40", MOSCOW, DAY).orElseThrow();

            assertThat(slot.location()).isNull();
            assertThat(slot.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
        }

        @Test
        void acceptsEnDashAsSeparator() {
            assertThat(parser.parseSlot("08:00 – 08:40 школа", MOSCOW, DAY)).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"08:70-09:00 школа", "25:00 школа", "08:00-08:00"})
        void rejectsImpossibleIntervals(String input) {
            assertThat(parser.parseSlot(input, MOSCOW, DAY)).isEmpty();
        }

        /**
         * Размен, принятый сознательно: отличить опечатку от длинного интервала нельзя, и
         * «09:00-08:00» становится делом до следующего утра.
         *
         * <p>Раньше такое отвергалось — и вместе с ним отвергалось «22:40-8:00 кровать», обычное
         * семейное дело. Из двух зол предсказуемое правило лучше догадки: длинный блок человек
         * увидит на календаре и поправит, а отказ вводить ночной сон исправить нечем.
         */
        @Test
        void endBeforeStartIsAcceptedAsRunningIntoTheNextDay() {
            DueDateParser.Slot slot = parser.parseSlot("09:00-08:00 школа", MOSCOW, DAY).orElseThrow();

            assertThat(slot.endsAt()).isEqualTo(slot.startsAt().plus(java.time.Duration.ofHours(23)));
        }

        @Test
        void rejectsEmptyInput() {
            assertThat(parser.parseSlot("   ", MOSCOW, DAY)).isEmpty();
        }
    }

    @Nested
    class DaylightSaving {

        /**
         * В зонах России перехода на летнее время нет с 2014 года, и клавиатура предлагает только
         * их. Правило всё равно проверяется на Берлине: парсер не должен зависеть от того, какие
         * зоны мы сегодня показываем.
         */
        @Test
        void tomorrowAtSixCrossesTheSpringTransition() {
            DueDateParser beforeTransition =
                    new DueDateParser(
                            Clock.fixed(Instant.parse("2026-03-28T12:00:00Z"), ZoneOffset.UTC));

            Instant due = beforeTransition.tomorrow(BERLIN);

            // 29 марта Берлин переходит на UTC+2, значит 19:00 местного — это 17:00 UTC
            assertThat(due).isEqualTo(Instant.parse("2026-03-29T17:00:00Z"));
        }

        @Test
        void tomorrowAtSixCrossesTheAutumnTransition() {
            DueDateParser beforeTransition =
                    new DueDateParser(
                            Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC));

            Instant due = beforeTransition.tomorrow(BERLIN);

            // 25 октября Берлин возвращается на UTC+1, значит 19:00 местного — это 18:00 UTC
            assertThat(due).isEqualTo(Instant.parse("2026-10-25T18:00:00Z"));
        }

        /** Час, которого в этот день не существует, не должен ронять разбор. */
        @Test
        void survivesTheHourThatDoesNotExist() {
            DueDateParser parser =
                    new DueDateParser(
                            Clock.fixed(Instant.parse("2026-03-29T00:30:00Z"), ZoneOffset.UTC));

            assertThat(parser.parse("29.03 02:30", BERLIN)).isNotEmpty();
        }
    }

    @Nested
    class QuietHours {

        @Test
        void reminderInTheNightIsMovedToEight() {
            Instant lateEvening = Instant.parse("2026-08-07T20:30:00Z"); // 23:30 по Москве

            Instant reminder = parser.reminderTimeFor(lateEvening, MOSCOW);

            // переносится на 8 августа, 08:00 по Москве
            assertThat(reminder).isEqualTo(Instant.parse("2026-08-08T05:00:00Z"));
        }

        @Test
        void reminderBeforeDawnIsMovedToEightTheSameDay() {
            Instant beforeDawn = Instant.parse("2026-08-07T02:00:00Z"); // 05:00 по Москве

            Instant reminder = parser.reminderTimeFor(beforeDawn, MOSCOW);

            assertThat(reminder).isEqualTo(Instant.parse("2026-08-07T05:00:00Z"));
        }

        @Test
        void reminderDuringTheDayIsNotMoved() {
            Instant afternoon = Instant.parse("2026-08-07T13:00:00Z"); // 16:00 по Москве

            assertThat(parser.reminderTimeFor(afternoon, MOSCOW)).isEqualTo(afternoon);
        }

        /** Границы окна включаются и исключаются явно: 22:00 уже тишина, 08:00 уже нет. */
        @Test
        void windowBoundariesAreExplicit() {
            Instant tenPm = Instant.parse("2026-08-07T19:00:00Z"); // 22:00 по Москве
            Instant eightAm = Instant.parse("2026-08-07T05:00:00Z"); // 08:00 по Москве

            assertThat(parser.reminderTimeFor(tenPm, MOSCOW)).isNotEqualTo(tenPm);
            assertThat(parser.reminderTimeFor(eightAm, MOSCOW)).isEqualTo(eightAm);
        }
    }
}

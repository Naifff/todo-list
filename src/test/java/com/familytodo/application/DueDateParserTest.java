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
            DueDateParser.Plan plan =
                    parser.parsePlan("22:40-8:00 кровать", MOSCOW, LocalDate.of(2026, 8, 7))
                            .orElseThrow();

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-07T19:40:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-08T05:00:00Z"));
            assertThat(plan.location()).isEqualTo("кровать");
        }

        @Test
        void equalStartAndEndIsRejected() {
            assertThat(parser.parsePlan("22:40-22:40", MOSCOW, LocalDate.of(2026, 8, 7))).isEmpty();
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

    /**
     * Правка «когда и где» разбирается тем же {@code parsePlan}, что и создание, — с одной
     * разницей: день, когда дату не назвали, берётся у самого дела, а не «сегодня».
     */
    @Nested
    class PlanWithADefaultDay {

        private static final LocalDate TASK_DAY = LocalDate.of(2026, 9, 1);

        /** ⚠️ Ровно то, что не работало: голая дата уезжала в место целиком. */
        @Test
        void aBareDateBecomesTheDeadline() {
            DueDateParser.Plan plan = parser.parsePlan("27.08", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-08-27T16:00:00Z"));
            assertThat(plan.location()).isNull();
            assertThat(plan.startsAt()).isNull();
        }

        @Test
        void aDateWithTimeBecomesTheDeadlineToo() {
            DueDateParser.Plan plan =
                    parser.parsePlan("27.08 18:00", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-08-27T15:00:00Z"));
            assertThat(plan.location()).isNull();
        }

        @Test
        void aDateWithAnIntervalOccupiesThatDay() {
            DueDateParser.Plan plan =
                    parser.parsePlan("27.08 18:00-19:00 парк", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-27T15:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-27T16:00:00Z"));
            assertThat(plan.location()).isEqualTo("парк");
        }

        /**
         * Без даты берётся день дела — и <b>без</b> правила «прошедшее время значит завтра».
         * Правило нужно при создании, где дня ещё нет; здесь день назвал не парсер, а задача, и
         * сдвигать его на сутки значило бы переносить чужое дело.
         */
        @Test
        void withoutADateTheTasksOwnDayIsUsedAsIs() {
            DueDateParser.Plan plan =
                    parser.parsePlan("08:00-08:40 школа", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-09-01T05:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
        }

        @Test
        void aLoneTimeOnTheTasksDayIsADeadlineNotAnInterval() {
            DueDateParser.Plan plan = parser.parsePlan("19:00", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-09-01T16:00:00Z"));
            assertThat(plan.startsAt()).isNull();
        }

        @Test
        void placeOnlyStaysPlaceOnly() {
            DueDateParser.Plan plan = parser.parsePlan("Zoom", MOSCOW, TASK_DAY).orElseThrow();

            assertThat(plan.location()).isEqualTo("Zoom");
            assertThat(plan.dueAt()).isNull();
            assertThat(plan.startsAt()).isNull();
        }
    }

    /**
     * Дело одной строкой: «сходить на ролики 14.08 18:30-20:00 цирк». Название спереди, когда и где
     * — сзади, ровно как это пишется человеком.
     */
    @Nested
    class WrittenInOneLine {

        @Test
        void splitsTitleFromDateTimeAndPlace() {
            DueDateParser.Titled written =
                    parser.parseTitled("сходить на ролики 14.08 18:30-20:00 цирк", MOSCOW)
                            .orElseThrow();

            assertThat(written.title()).isEqualTo("сходить на ролики");
            assertThat(written.plan().startsAt()).isEqualTo(Instant.parse("2026-08-14T15:30:00Z"));
            assertThat(written.plan().endsAt()).isEqualTo(Instant.parse("2026-08-14T17:00:00Z"));
            assertThat(written.plan().location()).isEqualTo("цирк");
        }

        @Test
        void aTimeAloneIsEnough() {
            DueDateParser.Titled written =
                    parser.parseTitled("вынести мусор 19:00", MOSCOW).orElseThrow();

            assertThat(written.title()).isEqualTo("вынести мусор");
            assertThat(written.plan().dueAt()).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }

        /**
         * ⚠️ «к 19:00» — как это и говорится по-русски, и предлог обязан остаться за границей
         * названия: дело называется «вынести мусор», а не «вынести мусор к».
         */
        @Test
        void aTrailingPrepositionDoesNotStickToTheTitle() {
            assertThat(parser.parseTitled("вынести мусор к 19:00", MOSCOW).orElseThrow().title())
                    .isEqualTo("вынести мусор");
            assertThat(parser.parseTitled("забрать Петю в 14.08", MOSCOW).orElseThrow().title())
                    .isEqualTo("забрать Петю");
        }

        /** Без даты и времени разбирать нечего — это обычное название, спросим срок кнопками. */
        @Test
        void aPlainTitleIsNotTaken() {
            assertThat(parser.parseTitled("вынести мусор", MOSCOW)).isEmpty();
        }

        /** Одна дата без названия — не дело: названием оно не обзавелось. */
        @Test
        void aDateWithoutATitleIsNotTaken() {
            assertThat(parser.parseTitled("14.08 18:30 цирк", MOSCOW)).isEmpty();
        }

        @Test
        void theTitleKeepsItsOwnDigits() {
            DueDateParser.Titled written =
                    parser.parseTitled("оплатить счёт 2 за август 20.08", MOSCOW).orElseThrow();

            assertThat(written.title()).isEqualTo("оплатить счёт 2 за август");
        }
    }

    @Nested
    class Slots {

        private static final java.time.LocalDate DAY = java.time.LocalDate.of(2026, 9, 1);

        @Test
        void parsesIntervalWithLocation() {
            DueDateParser.Plan plan =
                    parser.parsePlan("08:00-08:40 школа", MOSCOW, DAY).orElseThrow();

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-09-01T05:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
            assertThat(plan.location()).isEqualTo("школа");
        }

        /** ⚠️ Одно время — это срок, а не начало занятого времени. Прежний разбор считал иначе. */
        @Test
        void parsesOpenEndedTime() {
            DueDateParser.Plan plan = parser.parsePlan("19:00 дом", MOSCOW, DAY).orElseThrow();

            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-09-01T16:00:00Z"));
            assertThat(plan.startsAt()).isNull();
            assertThat(plan.location()).isEqualTo("дом");
        }

        /** Место без времени — обычный случай: «Zoom» без расписания. */
        @Test
        void parsesLocationOnly() {
            DueDateParser.Plan plan = parser.parsePlan("Zoom", MOSCOW, DAY).orElseThrow();

            assertThat(plan.startsAt()).isNull();
            assertThat(plan.location()).isEqualTo("Zoom");
        }

        @Test
        void parsesTimeOnly() {
            DueDateParser.Plan plan = parser.parsePlan("08:00-08:40", MOSCOW, DAY).orElseThrow();

            assertThat(plan.location()).isNull();
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-09-01T05:40:00Z"));
        }

        @Test
        void acceptsEnDashAsSeparator() {
            assertThat(parser.parsePlan("08:00 – 08:40 школа", MOSCOW, DAY)).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"08:70-09:00 школа", "25:00 школа", "08:00-08:00"})
        void rejectsImpossibleIntervals(String input) {
            assertThat(parser.parsePlan(input, MOSCOW, DAY)).isEmpty();
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
            DueDateParser.Plan plan =
                    parser.parsePlan("09:00-08:00 школа", MOSCOW, DAY).orElseThrow();

            assertThat(plan.endsAt())
                    .isEqualTo(plan.startsAt().plus(java.time.Duration.ofHours(23)));
        }

        @Test
        void rejectsEmptyInput() {
            assertThat(parser.parsePlan("   ", MOSCOW, DAY)).isEmpty();
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

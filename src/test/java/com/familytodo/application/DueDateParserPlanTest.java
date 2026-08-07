package com.familytodo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Разбор «когда и где» одной строкой при создании дела.
 *
 * <p>Шаг заменяет прежнюю «Свою дату» и обязан быть её надмножеством: всё, что понималось раньше,
 * должно пониматься и теперь. Добавляется интервал и место — без них макеты не наполнить, а
 * отдельными шагами это два лишних нажатия на каждом деле, включая «вынести мусор».
 *
 * <p>Разделение смыслов: одно время — это <b>срок</b>, интервал — это <b>занятое время</b>. Дело
 * «с 18:00 до 19:00» не то же самое, что «к 19:00», и складывать их в одно поле значило бы врать
 * напоминаниям.
 */
class DueDateParserPlanTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 7 августа 2026, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private final DueDateParser parser = new DueDateParser(Clock.fixed(NOW, ZoneOffset.UTC));

    @Nested
    class BackwardCompatible {

        @Test
        void dateAloneKeepsTheDefaultTime() {
            DueDateParser.Plan plan = parse("15.08");

            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-08-15T16:00:00Z"));
            assertThat(plan.startsAt()).isNull();
            assertThat(plan.location()).isNull();
        }

        @Test
        void dateWithTimeIsADeadline() {
            assertThat(parse("15.08 18:30").dueAt())
                    .isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
        }

        @Test
        void dateWithYear() {
            assertThat(parse("15.08.2027").dueAt())
                    .isEqualTo(Instant.parse("2027-08-15T16:00:00Z"));
        }

        /** Время, которое сегодня уже прошло, значит завтра — иначе дело просрочено сразу. */
        @Test
        void timeAlreadyPassedRollsToTomorrow() {
            assertThat(parse("09:00").dueAt()).isEqualTo(Instant.parse("2026-08-08T06:00:00Z"));
        }

        @Test
        void timeStillAheadStaysToday() {
            assertThat(parse("18:30").dueAt()).isEqualTo(Instant.parse("2026-08-07T15:30:00Z"));
        }

        /** Дата без года в прошлом — это следующий год, а не просрочка годичной давности. */
        @Test
        void pastDateWithoutYearMeansNextYear() {
            assertThat(parse("01.03").dueAt()).isEqualTo(Instant.parse("2027-03-01T16:00:00Z"));
        }
    }

    @Nested
    class Intervals {

        @Test
        void intervalTodayBecomesOccupiedTimeNotADeadline() {
            DueDateParser.Plan plan = parse("18:00-19:00");

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-07T15:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
            assertThat(plan.dueAt())
                    .describedAs("интервал — это занятое время, а не срок")
                    .isNull();
        }

        @Test
        void intervalOnAGivenDate() {
            DueDateParser.Plan plan = parse("15.08 08:00-08:40");

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-15T05:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-15T05:40:00Z"));
        }

        @Test
        void enDashWorksLikeAHyphen() {
            assertThat(parse("18:00–19:00").endsAt()).isEqualTo(parse("18:00-19:00").endsAt());
        }

        @Test
        void spacesAroundTheDashAreAllowed() {
            assertThat(parse("18:00 - 19:00").endsAt()).isEqualTo(parse("18:00-19:00").endsAt());
        }

        /**
         * Конец раньше начала — это ночь, а не опечатка.
         *
         * <p>«22:40-8:00 кровать» — обычное дело в семье с детьми, и отвергать его значит требовать
         * от человека разбить сон на два дела. Так же считают все календари.
         */
        @Test
        void endBeforeStartMeansTheNextMorning() {
            DueDateParser.Plan plan = parse("22:40-8:00 кровать");

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-07T19:40:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-08T05:00:00Z"));
            assertThat(plan.location()).isEqualTo("кровать");
        }

        @Test
        void nightIntervalOnAGivenDate() {
            DueDateParser.Plan plan = parse("15.08 23:00-06:30");

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-15T20:00:00Z"));
            assertThat(plan.endsAt()).isEqualTo(Instant.parse("2026-08-16T03:30:00Z"));
        }

        /** Час без ведущего нуля — то же самое время. */
        @Test
        void singleDigitHourIsAccepted() {
            assertThat(parse("8:00-9:30").startsAt()).isEqualTo(parse("08:00-09:30").startsAt());
        }

        /** Равные концы — ноль или сутки, понять нельзя. Отказ честнее догадки. */
        @Test
        void equalStartAndEndIsRejected() {
            assertThat(parser.parsePlan("22:40-22:40", MOSCOW)).isEmpty();
        }

        @Test
        void intervalAlreadyPassedTodayRollsToTomorrow() {
            DueDateParser.Plan plan = parse("08:00-09:00");

            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-08T05:00:00Z"));
        }
    }

    @Nested
    class Location {

        @Test
        void placeAfterAnInterval() {
            assertThat(parse("18:00-19:00 парк").location()).isEqualTo("парк");
        }

        @Test
        void placeAfterASingleTime() {
            DueDateParser.Plan plan = parse("19:00 дом");

            assertThat(plan.location()).isEqualTo("дом");
            assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }

        @Test
        void placeAfterADateAndInterval() {
            DueDateParser.Plan plan = parse("15.08 08:00-08:40 школа");

            assertThat(plan.location()).isEqualTo("школа");
            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-08-15T05:00:00Z"));
        }

        /** Место без времени: «где» известно, «когда» — нет, и выдумывать срок незачем. */
        @Test
        void placeAloneLeavesTheTaskUndated() {
            DueDateParser.Plan plan = parse("Zoom");

            assertThat(plan.location()).isEqualTo("Zoom");
            assertThat(plan.dueAt()).isNull();
            assertThat(plan.startsAt()).isNull();
        }

        @Test
        void multiWordPlace() {
            assertThat(parse("11:00 перег. «Ока»").location()).isEqualTo("перег. «Ока»");
        }
    }

    @Nested
    class Rejected {

        @Test
        void emptyInput() {
            assertThat(parser.parsePlan("", MOSCOW)).isEmpty();
            assertThat(parser.parsePlan("   ", MOSCOW)).isEmpty();
            assertThat(parser.parsePlan(null, MOSCOW)).isEmpty();
        }

        @Test
        void impossibleTime() {
            assertThat(parser.parsePlan("25:00", MOSCOW)).isEmpty();
            assertThat(parser.parsePlan("18:99", MOSCOW)).isEmpty();
        }

        @Test
        void impossibleDate() {
            assertThat(parser.parsePlan("32.13", MOSCOW)).isEmpty();
            assertThat(parser.parsePlan("30.02 10:00", MOSCOW)).isEmpty();
        }

        /** Место длиннее допустимого не молчит: обрезать чужой текст мы не вправе. */
        @Test
        void locationLongerThanTheLimit() {
            assertThat(parser.parsePlan("18:00 " + "ш".repeat(200), MOSCOW)).isEmpty();
        }
    }

    private DueDateParser.Plan parse(String input) {
        Optional<DueDateParser.Plan> plan = parser.parsePlan(input, MOSCOW);
        assertThat(plan).describedAs("разобрано: %s", input).isPresent();
        return plan.get();
    }
}

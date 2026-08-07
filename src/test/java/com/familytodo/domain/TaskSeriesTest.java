package com.familytodo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Серия повторяющихся дел.
 *
 * <p>Серия — это только правило. Сами дела живут обычными строками {@code task}: у каждого своя
 * судьба, одно сделано, другое отклонено с причиной, третьему сменили исполнителя. Виртуальному
 * вхождению статус приписать некуда.
 *
 * <p>Ограничение v1, записанное сознательно: правка применяется к <b>одному вхождению</b>, а серию
 * можно только остановить. «Изменить это или всю серию» — самый дорогой узел в календарях, и для
 * семейного списка он не окупается.
 */
class TaskSeriesTest {

    private static final long FAMILY = 1L;
    private static final long OTHER_FAMILY = 2L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    /** Понедельник. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private static final long MOM_ID = 10L;
    private static final Actor MOM = Actor.member(MOM_ID, FAMILY, Role.PARENT);
    private static final Actor DAD = Actor.member(11L, FAMILY, Role.PARENT);
    private static final Actor KID = Actor.member(12L, FAMILY, Role.CHILD);
    private static final Actor STRANGER = Actor.member(90L, OTHER_FAMILY, Role.PARENT);

    @Nested
    class Occurrences {

        @Test
        void weekdaySeriesGivesFiveDaysOutOfSeven() {
            TaskSeries series = series(Recurrence.weekdays(), MONDAY, null);

            List<LocalDate> dates = series.occurrencesBetween(MONDAY, MONDAY.plusDays(6));

            assertThat(dates).hasSize(5).startsWith(MONDAY).endsWith(MONDAY.plusDays(4));
        }

        /** До первого дня серии вхождений нет: правило не действует задним числом. */
        @Test
        void nothingBeforeTheStartDate() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            assertThat(series.occurrencesBetween(MONDAY.minusDays(5), MONDAY.minusDays(1))).isEmpty();
        }

        @Test
        void nothingAfterTheEndDate() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, MONDAY.plusDays(2));

            assertThat(series.occurrencesBetween(MONDAY, MONDAY.plusDays(10)))
                    .containsExactly(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2));
        }

        @Test
        void endDateIsIncluded() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, MONDAY);

            assertThat(series.occurrencesBetween(MONDAY, MONDAY.plusDays(10))).containsExactly(MONDAY);
        }

        /** Остановленная серия новых вхождений не даёт — в этом весь смысл остановки. */
        @Test
        void stoppedSeriesProducesNothing() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);
            series.stop(MOM, NOW);

            assertThat(series.occurrencesBetween(MONDAY, MONDAY.plusDays(10))).isEmpty();
        }

        @Test
        void backwardsWindowGivesNothingRatherThanThrowing() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            assertThat(series.occurrencesBetween(MONDAY.plusDays(5), MONDAY)).isEmpty();
        }
    }

    @Nested
    class Timing {

        /** Момент вхождения считается в зоне семьи: 08:00 — это её восемь утра, а не UTC. */
        @Test
        void occurrenceStartsAtTheSeriesTimeInTheFamilyZone() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            Instant start = series.startOf(MONDAY, java.time.ZoneId.of("Europe/Moscow"));

            assertThat(start).isEqualTo(Instant.parse("2026-08-31T05:00:00Z"));
        }

        @Test
        void seriesWithoutDurationHasNoEnd() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            assertThat(series.endOf(MONDAY, java.time.ZoneId.of("Europe/Moscow"))).isNull();
        }

        @Test
        void durationGivesTheEndOfTheInterval() {
            TaskSeries series =
                    TaskSeries.create(
                            5L,
                            FAMILY,
                            "Отвезти детей в школу",
                            MOM_ID,
                            new Assignee(12L, Role.CHILD),
                            Recurrence.daily(),
                            LocalTime.of(8, 0),
                            Duration.ofMinutes(40),
                            "школа",
                            MONDAY,
                            null,
                            NOW);

            assertThat(series.endOf(MONDAY, java.time.ZoneId.of("Europe/Moscow")))
                    .isEqualTo(Instant.parse("2026-08-31T05:40:00Z"));
        }
    }

    @Nested
    class Permissions {

        @Test
        void authorMayStopTheirOwnSeries() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            series.stop(MOM, NOW);

            assertThat(series.isStopped()).isTrue();
            assertThat(series.stoppedAt()).isEqualTo(NOW);
        }

        /** Родитель распоряжается сериями, которые исполняет ребёнок. */
        @Test
        void parentMayStopASeriesAssignedToAChild() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            series.stop(DAD, NOW);

            assertThat(series.isStopped()).isTrue();
        }

        @Test
        void childMayNotStopSomeoneElsesSeries() {
            TaskSeries series =
                    TaskSeries.create(
                            5L,
                            FAMILY,
                            "Вынести мусор",
                            MOM_ID,
                            new Assignee(99L, Role.PARENT),
                            Recurrence.daily(),
                            LocalTime.of(8, 0),
                            null,
                            null,
                            MONDAY,
                            null,
                            NOW);

            assertThatThrownBy(() -> series.stop(KID, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
        }

        /** Чужая семья — не «нельзя», а «нет такой серии»: существование тоже не разглашается. */
        @Test
        void strangerIsRefused() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);

            assertThatThrownBy(() -> series.stop(STRANGER, NOW))
                    .isInstanceOf(DomainException.NotPermitted.class);
            assertThat(series.isStopped()).isFalse();
        }

        @Test
        void stoppingTwiceKeepsTheFirstMoment() {
            TaskSeries series = series(Recurrence.daily(), MONDAY, null);
            series.stop(MOM, NOW);

            series.stop(MOM, NOW.plusSeconds(3600));

            assertThat(series.stoppedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    class Validation {

        @Test
        void titleIsRequired() {
            assertThatThrownBy(() -> withTitle(" ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void titleLongerThanATaskTitleIsRejected() {
            assertThatThrownBy(() -> withTitle("Ф".repeat(Task.MAX_TITLE_LENGTH + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void endBeforeStartIsRejected() {
            assertThatThrownBy(() -> series(Recurrence.daily(), MONDAY, MONDAY.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static TaskSeries withTitle(String title) {
            return TaskSeries.create(
                    5L,
                    FAMILY,
                    title,
                    MOM_ID,
                    new Assignee(12L, Role.CHILD),
                    Recurrence.daily(),
                    LocalTime.of(8, 0),
                    null,
                    null,
                    MONDAY,
                    null,
                    NOW);
        }
    }

    private static TaskSeries series(Recurrence rule, LocalDate startsOn, LocalDate endsOn) {
        return TaskSeries.create(
                5L,
                FAMILY,
                "Отвезти детей в школу",
                MOM_ID,
                new Assignee(12L, Role.CHILD),
                rule,
                LocalTime.of(8, 0),
                null,
                null,
                startsOn,
                endsOn,
                NOW);
    }
}

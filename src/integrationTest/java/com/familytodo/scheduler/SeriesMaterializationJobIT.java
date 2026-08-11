package com.familytodo.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.adapter.persistence.JdbcTaskSeriesRepository;
import com.familytodo.application.SeriesService;
import com.familytodo.application.TaskQuery;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Recurrence;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskSeries;
import com.familytodo.persistence.AbstractSqliteIT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Материализация повторяющихся дел на реальной базе.
 *
 * <p>Идемпотентность здесь не свойство кода, а свойство схемы: уникальный индекс по паре
 * {@code (series_id, occurrence_on)} делает дубль невозможным. Проверять её надо там, где этот
 * индекс существует, — то есть в базе, а не на фейке.
 */
class SeriesMaterializationJobIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Пятница, 7 августа 2026, 12:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    private MutableClock clock;
    private JdbcFamilyRepository families;
    private JdbcMemberRepository members;
    private JdbcTaskRepository tasks;
    private JdbcTaskSeriesRepository series;
    private SeriesService job;

    private Family family;
    private Member mom;
    private Member kid;

    @BeforeEach
    void wire() {
        clock = new MutableClock(NOW);
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        families = new JdbcFamilyRepository(jdbc, sequence);
        members = new JdbcMemberRepository(jdbc, sequence);
        tasks = new JdbcTaskRepository(jdbc, sequence);
        series = new JdbcTaskSeriesRepository(jdbc, sequence);
        job = new SeriesService(families, series, tasks, members, clock);

        family = families.save(Family.create(families.nextId(), "Румянцевы", MOSCOW, NOW));
        mom = join(100000001L, "Мама", Role.PARENT);
        kid = join(512034877L, "Петя", Role.CHILD);
    }

    @Nested
    class Materialisation {

        @Test
        void dailySeriesFillsTheWholeHorizon() {
            daily(LocalTime.of(8, 0));

            job.materialiseAll();

            assertThat(occurrences()).hasSize(SeriesService.HORIZON_DAYS);
        }

        @Test
        void weekdaySeriesSkipsWeekends() {
            weekdays(LocalTime.of(8, 0));

            job.materialiseAll();

            assertThat(occurrences())
                    .allSatisfy(
                            task -> {
                                LocalDate day =
                                        LocalDate.ofInstant(task.startsAt(), MOSCOW);
                                assertThat(day.getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
                            });
        }

        @Test
        void occurrenceCarriesTitleAssigneeAndLocation() {
            series.save(
                    TaskSeries.create(
                            series.nextId(),
                            family.id(),
                            "Отвезти детей в школу",
                            mom.id(),
                            new Assignee(kid.id(), kid.role()),
                            Recurrence.daily(),
                            LocalTime.of(8, 0),
                            Duration.ofMinutes(40),
                            "школа",
                            TODAY,
                            null,
                            NOW));

            job.materialiseAll();

            Task first = occurrences().getFirst();
            assertThat(first.title()).isEqualTo("Отвезти детей в школу");
            assertThat(first.assignments().getFirst().memberId()).isEqualTo(kid.id());
            assertThat(first.location()).isEqualTo("школа");
            assertThat(first.creatorId()).isEqualTo(mom.id());
        }

        /** Есть длительность — есть интервал; нет — только срок, как у обычного дела. */
        @Test
        void durationBecomesAnIntervalAndItsAbsenceBecomesADeadline() {
            series.save(withDuration(Duration.ofMinutes(40)));
            job.materialiseAll();
            assertThat(occurrences().getFirst().endsAt()).isNotNull();

            jdbc.sql("delete from task").update();
            jdbc.sql("delete from task_series").update();
            series.save(withDuration(null));
            job.materialiseAll();

            Task deadlineOnly = occurrences().getFirst();
            assertThat(deadlineOnly.startsAt()).isNull();
            assertThat(deadlineOnly.endsAt()).isNull();
            assertThat(deadlineOnly.dueAt()).isNotNull();
        }

        @Test
        void seriesEndDateStopsTheFilling() {
            series.save(
                    TaskSeries.create(
                            series.nextId(),
                            family.id(),
                            "Курс из трёх занятий",
                            mom.id(),
                            new Assignee(kid.id(), kid.role()),
                            Recurrence.daily(),
                            LocalTime.of(8, 0),
                            null,
                            null,
                            TODAY,
                            TODAY.plusDays(2),
                            NOW));

            job.materialiseAll();

            assertThat(occurrences()).hasSize(3);
        }
    }

    @Nested
    class Idempotency {

        /** Главное свойство джобы: её прогоняют каждый час, и дублей быть не должно. */
        @Test
        void repeatedRunsDoNotCreateDuplicates() {
            daily(LocalTime.of(8, 0));

            job.materialiseAll();
            int afterFirst = occurrences().size();
            job.materialiseAll();
            job.materialiseAll();

            assertThat(occurrences()).hasSize(afterFirst);
        }

        /** Закрытое вхождение не воскресает следующим прогоном. */
        @Test
        void closedOccurrenceIsNotRecreated() {
            daily(LocalTime.of(8, 0));
            job.materialiseAll();
            Task first = occurrences().getFirst();
            first.complete(kid.asActor(), NOW);
            tasks.save(first);

            job.materialiseAll();

            assertThat(countOn(LocalDate.ofInstant(first.startsAt(), MOSCOW))).isEqualTo(1);
        }

        /**
         * Барьер: дубль невозможен на уровне схемы, а не благодаря проверке в джобе.
         *
         * <p>Проверено отключением проверки в коде — тесты выше остались зелёными, потому что
         * вставку отбивает уникальный индекс. Этот тест закрепляет, что индекс есть и работает:
         * без него «идемпотентность» держалась бы только на аккуратности следующего правщика.
         */
        @Test
        void theSchemaItselfRefusesASecondOccurrenceForTheSameDay() {
            TaskSeries rule = daily(LocalTime.of(8, 0));
            job.materialiseAll();
            int before = occurrences().size();

            int inserted =
                    jdbc.sql(
                                    """
                                    insert into task (id, family_id, title, creator_id,
                                                      status, due_at, created_at, series_id, occurrence_on)
                                    values (?, ?, 'Дубль', ?, 'OPEN', 0, 0, ?, ?)
                                    on conflict (series_id, occurrence_on) where series_id is not null
                                    do nothing
                                    """)
                            .params(9999L, family.id(), mom.id(), rule.id(), TODAY.toString())
                            .update();

            assertThat(inserted).describedAs("вставка отбита индексом").isZero();
            assertThat(occurrences()).hasSize(before);
        }

        /** Горизонт едет вместе с календарём: назавтра появляется ровно один новый день. */
        @Test
        void horizonMovesForwardWithTime() {
            daily(LocalTime.of(8, 0));
            job.materialiseAll();
            int before = occurrences().size();

            clock.set(NOW.plus(Duration.ofDays(1)));
            job.materialiseAll();

            assertThat(occurrences()).hasSize(before + 1);
        }
    }

    @Nested
    class Stopping {

        @Test
        void stoppingRemovesFutureOpenOccurrences() {
            TaskSeries daily = daily(LocalTime.of(8, 0));
            job.materialiseAll();

            job.stop(mom, daily.id());

            assertThat(occurrences()).isEmpty();
        }

        /** История остаётся: остановка правила не повод стирать то, что уже случилось. */
        @Test
        void closedOccurrencesSurviveTheStop() {
            TaskSeries daily = daily(LocalTime.of(8, 0));
            job.materialiseAll();
            Task done = occurrences().getFirst();
            done.complete(kid.asActor(), NOW);
            tasks.save(done);
            Task declined = occurrences().get(1);
            declined.decline(kid.asActor(), "заболел", NOW);
            tasks.save(declined);

            job.stop(mom, daily.id());

            assertThat(tasks.findById(family.id(), done.id())).isPresent();
            assertThat(tasks.findById(family.id(), declined.id())).isPresent();
        }

        @Test
        void stoppedSeriesIsNotRefilled() {
            TaskSeries daily = daily(LocalTime.of(8, 0));
            job.materialiseAll();
            job.stop(mom, daily.id());

            job.materialiseAll();

            assertThat(occurrences()).isEmpty();
        }
    }

    /**
     * Смена таймзоны семьи не двигает то, что уже материализовано.
     *
     * <p>Момент дела абсолютен, и переписывать его задним числом значило бы переносить встречи,
     * о которых люди уже договорились. Новые вхождения считаются по новой зоне — это и есть смысл
     * смены зоны.
     */
    @Test
    void changingTheFamilyTimezoneDoesNotShiftExistingOccurrences() {
        daily(LocalTime.of(8, 0));
        job.materialiseAll();
        List<Instant> before = occurrences().stream().map(Task::startsAt).toList();

        family.changeTimezone(mom.asActor(), ZoneId.of("Asia/Vladivostok"));
        families.save(family);
        job.materialiseAll();

        List<Instant> after =
                occurrences().stream().map(Task::startsAt).limit(before.size()).toList();
        assertThat(after).isEqualTo(before);
    }

    // --- вспомогательное ---

    private TaskSeries daily(LocalTime at) {
        return series.save(
                TaskSeries.create(
                        series.nextId(),
                        family.id(),
                        "Отвезти детей в школу",
                        mom.id(),
                        new Assignee(kid.id(), kid.role()),
                        Recurrence.daily(),
                        at,
                        Duration.ofMinutes(40),
                        "школа",
                        TODAY,
                        null,
                        NOW));
    }

    private TaskSeries weekdays(LocalTime at) {
        return series.save(
                TaskSeries.create(
                        series.nextId(),
                        family.id(),
                        "Daily standup",
                        mom.id(),
                        new Assignee(mom.id(), mom.role()),
                        Recurrence.weekdays(),
                        at,
                        Duration.ofMinutes(30),
                        "Zoom",
                        TODAY,
                        null,
                        NOW));
    }

    private TaskSeries withDuration(Duration duration) {
        return TaskSeries.create(
                series.nextId(),
                family.id(),
                "Отвезти детей в школу",
                mom.id(),
                new Assignee(kid.id(), kid.role()),
                Recurrence.daily(),
                LocalTime.of(8, 0),
                duration,
                "школа",
                TODAY,
                null,
                NOW);
    }

    private Member join(long telegramId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(), family.id(), telegramId, telegramId, name, role, NOW));
    }

    private List<Task> occurrences() {
        return tasks.find(TaskQuery.visibleTo(mom)).stream()
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    private int countOn(LocalDate day) {
        return jdbc.sql("select count(*) from task where occurrence_on = ?")
                .param(day.toString())
                .query(Integer.class)
                .single();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

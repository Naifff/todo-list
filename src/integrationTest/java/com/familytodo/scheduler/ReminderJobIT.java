package com.familytodo.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.adapter.persistence.JdbcReminderRepository;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.adapter.scheduler.ReminderJob;
import com.familytodo.application.DueDateParser;
import com.familytodo.support.NoOpNotifier;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.persistence.AbstractSqliteIT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Напоминания на реальной базе.
 *
 * <p>Опорная точка: пятница 7 августа 2026, 12:00 по Москве.
 */
class ReminderJobIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Instant NOON = Instant.parse("2026-08-07T09:00:00Z");

    private MutableClock clock;
    private JdbcTaskRepository tasks;
    private JdbcMemberRepository members;
    private RecordingNotifier notifier;
    private ReminderJob job;

    private Family family;
    private Member mom;
    private Member kid;

    @BeforeEach
    void wire() {
        clock = new MutableClock(NOON);
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        JdbcFamilyRepository familyRepository = new JdbcFamilyRepository(jdbc, sequence);
        members = new JdbcMemberRepository(jdbc, sequence);
        tasks = new JdbcTaskRepository(jdbc, sequence);
        notifier = new RecordingNotifier();
        job =
                new ReminderJob(
                        new JdbcReminderRepository(jdbc),
                        tasks,
                        members,
                        notifier,
                        new DueDateParser(clock),
                        clock);

        family =
                familyRepository.save(
                        Family.create(familyRepository.nextId(), "Румянцевы", MOSCOW, NOON));
        mom = join(100000001L, "Мама", Role.PARENT);
        kid = join(512034877L, "Петя", Role.CHILD);
    }

    @Nested
    class Delivery {

        @Test
        void sendsWhenTheDueTimeArrives() {
            Task task = task("Вынести мусор", NOON.minus(Duration.ofMinutes(1)));

            job.run();

            assertThat(notifier.sent).containsExactly(task.id());
            assertThat(remindedAt(task)).isNotNull();
        }

        /** Повторный прогон обязан молчать: пометка для того и ставится. */
        @Test
        void secondRunIsSilent() {
            task("Вынести мусор", NOON.minus(Duration.ofMinutes(1)));
            job.run();
            notifier.sent.clear();

            job.run();

            assertThat(notifier.sent).isEmpty();
        }

        @Test
        void futureTaskIsNotTouched() {
            Task task = task("Вынести мусор", NOON.plus(Duration.ofHours(1)));

            job.run();

            assertThat(notifier.sent).isEmpty();
            assertThat(remindedAt(task)).isNull();
        }

        @Test
        void tasksWithoutDueDateAreNeverSelected() {
            Task task = task("Разобрать шкаф", null);

            job.run();

            assertThat(notifier.sent).isEmpty();
            assertThat(remindedAt(task)).isNull();
        }

        @Test
        void closedTasksAreNeverSelected() {
            Task task = task("Вынести мусор", NOON.minus(Duration.ofMinutes(1)));
            task.complete(kid.asActor(), NOON);
            tasks.save(task);

            job.run();

            assertThat(notifier.sent).isEmpty();
        }

        @Test
        void blockedAssigneeGetsNothingButIsStillMarked() {
            kid.markBotBlocked();
            members.save(kid);
            Task task = task("Вынести мусор", NOON.minus(Duration.ofMinutes(1)));

            job.run();

            assertThat(notifier.sent).isEmpty();
            assertThat(remindedAt(task)).isNotNull();
        }
    }

    @Nested
    class LateReminders {

        /** Порог — два часа. Полчаса опоздания это ещё «сейчас». */
        @Test
        void thirtyMinutesLateIsStillSent() {
            Task task = task("Вынести мусор", NOON.minus(Duration.ofMinutes(30)));

            job.run();

            assertThat(notifier.sent).containsExactly(task.id());
        }

        /** Приложение простояло — не вываливаем пачку сообщений задним числом. */
        @Test
        void fiveHoursLateIsMarkedButNotSent() {
            Task task = task("Вынести мусор", NOON.minus(Duration.ofHours(5)));

            job.run();

            assertThat(notifier.sent).isEmpty();
            assertThat(remindedAt(task)).isNotNull();
        }

        /** И на следующем прогоне тоже молчит: пометка стоит. */
        @Test
        void tooLateTaskStaysSilentAfterwards() {
            task("Вынести мусор", NOON.minus(Duration.ofHours(5)));
            job.run();

            clock.advance(Duration.ofMinutes(1));
            job.run();

            assertThat(notifier.sent).isEmpty();
        }
    }

    @Nested
    class QuietHours {

        /** Дело на 23:30 не должно будить семью — напоминание уезжает на утро. */
        @Test
        void nightTaskIsNotSentAtNight() {
            // 23:30 по Москве
            Task task = task("Вынести мусор", Instant.parse("2026-08-07T20:30:00Z"));
            clock.set(Instant.parse("2026-08-07T20:31:00Z"));

            job.run();

            assertThat(notifier.sent).isEmpty();
            assertThat(remindedAt(task))
                    .as("не помечаем: вернёмся к этому утром")
                    .isNull();
        }

        @Test
        void nightTaskIsSentInTheMorning() {
            Task task = task("Вынести мусор", Instant.parse("2026-08-07T20:30:00Z"));
            clock.set(Instant.parse("2026-08-07T20:31:00Z"));
            job.run();

            // 08:00 по Москве следующего дня
            clock.set(Instant.parse("2026-08-08T05:00:00Z"));
            job.run();

            assertThat(notifier.sent).containsExactly(task.id());
        }

        /**
         * Самое опасное место: если бы порог опоздания считался от срока, а не от момента
         * напоминания, отложенное на утро дело в восемь часов уже считалось бы просроченным на
         * восемь с половиной часов и не отправилось бы никогда.
         */
        @Test
        void deferredTaskIsNotConsideredLateInTheMorning() {
            Task task = task("Вынести мусор", Instant.parse("2026-08-07T20:30:00Z"));

            clock.set(Instant.parse("2026-08-08T05:00:00Z"));
            job.run();

            assertThat(notifier.sent).containsExactly(task.id());
        }
    }

    @Nested
    class Ordering {

        /**
         * Пометка коммитится до отправки. Падение доставки не должно возвращать задачу в очередь —
         * иначе на следующем тике придёт дубль.
         */
        @Test
        void failedDeliveryDoesNotResurrectTheReminder() {
            Task task = task("Вынести мусор", NOON.minus(Duration.ofMinutes(1)));
            notifier.explode = true;

            assertThatCode(job::run).doesNotThrowAnyException();

            assertThat(remindedAt(task)).as("пометка уже в базе").isNotNull();

            notifier.explode = false;
            job.run();
            assertThat(notifier.sent).isEmpty();
        }
    }

    // --- вспомогательное ---

    private Task task(String title, Instant dueAt) {
        return tasks.save(
                Task.create(
                        tasks.nextId(),
                        family.id(),
                        title,
                        mom.id(),
                        new Assignee(kid.id(), Role.CHILD),
                        dueAt,
                        NOON));
    }

    private Member join(long telegramId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(), family.id(), telegramId, telegramId, name, role, NOON));
    }

    private Long remindedAt(Task task) {
        return jdbc.sql("select reminded_at from task where id = ?")
                .param(task.id())
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private static final class RecordingNotifier extends NoOpNotifier {
        private final List<Long> sent = new ArrayList<>();
        private boolean explode;

        @Override
        public void taskDue(Member recipient, Task task) {
            if (explode) {
                throw new IllegalStateException("телеграм недоступен");
            }
            sent.add(task.id());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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

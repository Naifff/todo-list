package com.familytodo.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.familytodo.adapter.persistence.JdbcFamilyRepository;
import com.familytodo.adapter.persistence.JdbcIdSequence;
import com.familytodo.adapter.persistence.JdbcMemberRepository;
import com.familytodo.adapter.persistence.JdbcTaskRepository;
import com.familytodo.adapter.scheduler.DigestJob;
import com.familytodo.support.NoOpNotifier;
import com.familytodo.domain.Assignee;
import com.familytodo.domain.Family;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.persistence.AbstractSqliteIT;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Утренний дайджест на реальной базе. Всё на подвижных часах, без ожиданий. */
class DigestJobIT extends AbstractSqliteIT {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow"); // UTC+3
    private static final ZoneId VLADIVOSTOK = ZoneId.of("Asia/Vladivostok"); // UTC+10
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Четверг, 6 августа 2026, 12:00 по Москве — накануне «сегодня» тестов. */
    private static final Instant YESTERDAY_NOON = Instant.parse("2026-08-06T09:00:00Z");

    private MutableClock clock;
    private JdbcFamilyRepository familyRepository;
    private JdbcMemberRepository members;
    private JdbcTaskRepository tasks;
    private RecordingNotifier notifier;
    private DigestJob job;

    @BeforeEach
    void wire() {
        clock = new MutableClock(YESTERDAY_NOON);
        JdbcIdSequence sequence = new JdbcIdSequence(jdbc);
        familyRepository = new JdbcFamilyRepository(jdbc, sequence);
        members = new JdbcMemberRepository(jdbc, sequence);
        tasks = new JdbcTaskRepository(jdbc, sequence);
        notifier = new RecordingNotifier();
        job = new DigestJob(familyRepository, members, tasks, notifier, clock);
    }

    @Nested
    class Timing {

        /** Восемь утра по местному времени, а не по серверному. */
        @Test
        void moscowFamilyGetsItAtEightLocal() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");

            clock.set(Instant.parse("2026-08-07T04:59:00Z")); // 07:59 по Москве
            job.run();
            assertThat(notifier.recipients).isEmpty();

            clock.set(Instant.parse("2026-08-07T05:00:00Z")); // 08:00 по Москве
            job.run();
            assertThat(notifier.recipients).containsExactly(mom.id());
        }

        /** Одна джоба, две семьи, разное утро — вся суть выбранной схемы. */
        @Test
        void twoZonesGetItAtDifferentMoments() {
            Family west = family("Румянцевы", MOSCOW);
            Family east = family("Дальние", VLADIVOSTOK);
            Member momWest = join(west, 100000001L, "Мама", Role.PARENT);
            Member momEast = join(east, 512034877L, "Тётя", Role.PARENT);
            task(west, momWest, "Вынести мусор");
            task(east, momEast, "Полить огород");

            // 08:00 во Владивостоке — в Москве ещё час ночи
            clock.set(Instant.parse("2026-08-06T22:00:00Z"));
            job.run();
            assertThat(notifier.recipients).containsExactly(momEast.id());

            // 08:00 в Москве
            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();
            assertThat(notifier.recipients).containsExactly(momEast.id(), momWest.id());
        }

        @Test
        void secondRunTheSameDaySendsNothing() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();
            notifier.recipients.clear();

            clock.set(Instant.parse("2026-08-07T05:15:00Z"));
            job.run();

            assertThat(notifier.recipients).isEmpty();
        }

        /** Иначе регистрация днём немедленно вызывает дайджест ближайшим тиком. */
        @Test
        void familyCreatedAfterDigestTimeGetsNothingThatDay() {
            clock.set(Instant.parse("2026-08-07T07:00:00Z")); // 10:00 по Москве
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");

            job.run();

            assertThat(notifier.recipients).isEmpty();
        }
    }

    @Nested
    class CatchingUp {

        /** Приложение лежало всё утро: в десять дайджест ещё имеет смысл. */
        @Test
        void restartedAtTenStillSends() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");

            clock.set(Instant.parse("2026-08-07T07:00:00Z")); // 10:00 по Москве
            job.run();

            assertThat(notifier.recipients).containsExactly(mom.id());
        }

        /** А в три часа дня «дела на сегодня» уже не новость. */
        @Test
        void restartedAtThreeInTheAfternoonDoesNot() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");

            clock.set(Instant.parse("2026-08-07T12:00:00Z")); // 15:00 по Москве
            job.run();

            assertThat(notifier.recipients).isEmpty();
        }
    }

    @Nested
    class Contents {

        /** Сообщение «дел нет» это шум, а не польза. */
        @Test
        void emptyDigestIsNotSent() {
            Family family = family("Румянцевы", MOSCOW);
            join(family, 100000001L, "Мама", Role.PARENT);

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();

            assertThat(notifier.recipients).isEmpty();
        }

        /** Но день всё равно считается отработанным: дайджест — одна попытка в сутки. */
        @Test
        void emptyDigestStillMarksTheDay() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();
            task(family, mom, "Появилось позже");
            clock.set(Instant.parse("2026-08-07T06:00:00Z"));
            job.run();

            assertThat(notifier.recipients).isEmpty();
            assertThat(familyRepository.findById(family.id()).orElseThrow().lastDigestDate())
                    .isEqualTo(LocalDate.of(2026, 8, 7));
        }

        /** Список персональный: ребёнок не должен увидеть дела родителей. */
        @Test
        void childSeesOnlyTheirOwn() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            Member kid = join(family, 512034877L, "Петя", Role.CHILD);
            task(family, mom, mom, "Забрать посылку");
            task(family, mom, kid, "Вынести мусор");

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(2);
            assertThat(notifier.sizeFor(kid.id())).isEqualTo(1);
        }

        @Test
        void blockedMemberIsSkipped() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Вынести мусор");
            mom.markBotBlocked();
            members.save(mom);

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            job.run();

            assertThat(notifier.recipients).isEmpty();
        }

        @Test
        void oneFailedDeliveryDoesNotStopTheRest() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            Member kid = join(family, 512034877L, "Петя", Role.CHILD);
            task(family, mom, kid, "Вынести мусор");
            notifier.failing.add(mom.id());

            clock.set(Instant.parse("2026-08-07T05:00:00Z"));
            assertThatCode(job::run).doesNotThrowAnyException();

            assertThat(notifier.recipients).contains(kid.id());
        }
    }

    /**
     * Переход на летнее время: 29 марта в Берлине час с 02:00 до 03:00 не существует. Дайджест,
     * назначенный внутрь этого часа, не должен ронять джобу — он просто случается позже.
     */
    @Test
    void survivesTheHourThatDoesNotExist() {
        clock.set(Instant.parse("2026-03-28T12:00:00Z"));
        Family family = family("Немцы", BERLIN);
        family.changeDigestTime(
                Member.restore(
                                1L,
                                family.id(),
                                1L,
                                1L,
                                "Кто-то",
                                Role.PARENT,
                                com.familytodo.domain.MemberStatus.ACTIVE,
                                false,
                                YESTERDAY_NOON)
                        .asActor(),
                LocalTime.of(2, 30));
        familyRepository.save(family);
        Member parent = join(family, 100000001L, "Ганс", Role.PARENT);
        task(family, parent, "Вынести мусор");

        // 03:05 по Берлину 29 марта — час 02:30 в этот день не наступал вовсе
        clock.set(Instant.parse("2026-03-29T02:05:00Z"));
        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(notifier.recipients).containsExactly(parent.id());
    }

    // --- вспомогательное ---

    private Family family(String name, ZoneId zone) {
        return familyRepository.save(
                Family.create(familyRepository.nextId(), name, zone, clock.instant()));
    }

    private Member join(Family family, long telegramId, String name, Role role) {
        return members.save(
                Member.join(
                        members.nextId(),
                        family.id(),
                        telegramId,
                        telegramId,
                        name,
                        role,
                        clock.instant()));
    }

    private void task(Family family, Member who, String title) {
        task(family, who, who, title);
    }

    private void task(Family family, Member creator, Member assignee, String title) {
        tasks.save(
                Task.create(
                        tasks.nextId(),
                        family.id(),
                        title,
                        creator.id(),
                        new Assignee(assignee.id(), assignee.role()),
                        null,
                        clock.instant()));
    }

    private static final class RecordingNotifier extends NoOpNotifier {
        private final List<Long> recipients = new ArrayList<>();
        private final List<Integer> sizes = new ArrayList<>();
        private final List<Long> failing = new ArrayList<>();

        int sizeFor(long memberId) {
            return sizes.get(recipients.indexOf(memberId));
        }

        @Override
        public void digest(Member recipient, List<Task> tasks, List<Member> family, ZoneId zone) {
            if (failing.contains(recipient.id())) {
                throw new IllegalStateException("телеграм недоступен");
            }
            recipients.add(recipient.id());
            sizes.add(tasks.size());
        }

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

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

    /**
     * Горизонт дайджеста: на сколько дней вперёд собирать список.
     *
     * <p>Вперёд горизонт ограничивает, назад — нет. Просроченное с ростом срока не становится менее
     * важным, а дела без даты не с чем сравнивать: «купить хлеб» не обещано ни на какой день, но
     * из семейного списка от этого не исчезает.
     */
    @Nested
    class Horizon {

        /** 08:00 по Москве 7 августа 2026 — момент рассылки во всех тестах ниже. */
        private static final Instant DIGEST_MOMENT = Instant.parse("2026-08-07T05:00:00Z");

        @Test
        void oneDayShowsTodayButNotTomorrow() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            dated(family, mom, "Сегодняшнее", "2026-08-07T16:00:00Z");
            dated(family, mom, "Завтрашнее", "2026-08-08T16:00:00Z");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(1);
        }

        @Test
        void sevenDaysReachTheWholeWeek() {
            Family family = withHorizon("Румянцевы", 7);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            dated(family, mom, "Сегодняшнее", "2026-08-07T16:00:00Z");
            dated(family, mom, "Завтрашнее", "2026-08-08T16:00:00Z");
            dated(family, mom, "Через шесть дней", "2026-08-13T16:00:00Z");
            dated(family, mom, "Через неделю с лишним", "2026-08-15T16:00:00Z");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(3);
        }

        @Test
        void thirtyDaysReachTheMonth() {
            Family family = withHorizon("Румянцевы", 30);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            dated(family, mom, "Сегодняшнее", "2026-08-07T16:00:00Z");
            dated(family, mom, "Через неделю с лишним", "2026-08-15T16:00:00Z");
            dated(family, mom, "Через два месяца", "2026-10-07T16:00:00Z");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(2);
        }

        @Test
        void overdueIsInTheDigestAtEveryHorizon() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            dated(family, mom, "Просрочено на два дня", "2026-08-05T16:00:00Z");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(1);
        }

        /** Дело без срока не с чем сравнивать — горизонт его не касается. */
        @Test
        void undatedIsInTheDigestAtEveryHorizon() {
            Family family = family("Румянцевы", MOSCOW);
            Member mom = join(family, 100000001L, "Мама", Role.PARENT);
            task(family, mom, "Когда-нибудь разобрать гараж");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.sizeFor(mom.id())).isEqualTo(1);
        }

        @Test
        void horizonIsReadFromTheFamilyNotHardcoded() {
            Family narrow = family("Узкие", MOSCOW);
            Member one = join(narrow, 100000001L, "Мама", Role.PARENT);
            dated(narrow, one, "Завтрашнее", "2026-08-08T16:00:00Z");

            Family wide = withHorizon("Широкие", 7);
            Member two = join(wide, 512034877L, "Тётя", Role.PARENT);
            dated(wide, two, "Завтрашнее", "2026-08-08T16:00:00Z");

            clock.set(DIGEST_MOMENT);
            job.run();

            assertThat(notifier.recipients).containsExactly(two.id());
        }

        /** Горизонт переживает перезапуск: он в базе, а не в памяти джобы. */
        @Test
        void horizonSurvivesReload() {
            Family family = withHorizon("Румянцевы", 7);

            assertThat(familyRepository.findById(family.id()).orElseThrow().digestHorizonDays())
                    .isEqualTo(7);
        }

        private Family withHorizon(String name, int days) {
            Family family = family(name, MOSCOW);
            family.changeDigestHorizon(anyParentOf(family), days);
            return familyRepository.save(family);
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
                                com.familytodo.domain.MemberColor.BLUE,
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

    private void dated(Family family, Member who, String title, String dueAt) {
        tasks.save(
                Task.create(
                        tasks.nextId(),
                        family.id(),
                        title,
                        who.id(),
                        new Assignee(who.id(), who.role()),
                        Instant.parse(dueAt),
                        clock.instant()));
    }

    /** Настройки семьи меняет родитель — здесь важна не проверка прав, а сама смена значения. */
    private com.familytodo.domain.Actor anyParentOf(Family family) {
        return Member.restore(
                        -1L,
                        family.id(),
                        -1L,
                        -1L,
                        "Родитель",
                        Role.PARENT,
                        com.familytodo.domain.MemberStatus.ACTIVE,
                        false,
                        com.familytodo.domain.MemberColor.BLUE,
                        YESTERDAY_NOON)
                .asActor();
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
        private final List<Integer> horizons = new ArrayList<>();

        int sizeFor(long memberId) {
            return sizes.get(recipients.indexOf(memberId));
        }

        @Override
        public void digest(
                Member recipient,
                List<Task> tasks,
                List<Member> family,
                ZoneId zone,
                int horizonDays) {
            horizons.add(horizonDays);
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

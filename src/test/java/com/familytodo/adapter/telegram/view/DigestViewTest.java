package com.familytodo.adapter.telegram.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.familytodo.domain.Assignee;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Утренний дайджест.
 *
 * <p>Своя вёрстка, а не список дел: у дайджеста нет кнопок, поэтому нумерация строк в нём — шум, зато
 * дни нужны заголовками. Плоский список повторял дату на каждой строке, и пять дел на 13.08 читались
 * как пять разных дней.
 */
class DigestViewTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Четверг, 13 августа 2026, 08:00 по Москве. */
    private static final Instant NOW = Instant.parse("2026-08-13T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    private static final long FAMILY = 1L;
    private final AtomicLong ids = new AtomicLong();

    private final Member mom =
            Member.join(10L, FAMILY, 100L, 100L, "Мама", Role.PARENT, NOW);
    private final Member kid =
            Member.join(12L, FAMILY, 102L, 102L, "Петя", Role.CHILD, NOW);
    private final Map<Long, Member> byId = Map.of(mom.id(), mom, kid.id(), kid);

    @org.junit.jupiter.api.Nested
    class Grouping {

        @org.junit.jupiter.api.Test
        void tasksOfOneDayShareASingleHeading() {
            String text =
                    DigestView.render(
                            "Дела на неделю",
                            List.of(
                                    due(mom, "Вынести мусор", LocalDate.of(2026, 8, 13), LocalTime.of(19, 0)),
                                    due(mom, "Забрать посылку", LocalDate.of(2026, 8, 13), LocalTime.of(20, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Вынести мусор").contains("Забрать посылку");
            assertThat(text.split("Сегодня", -1)).describedAs("заголовок дня один").hasSize(2);
            assertThat(text).doesNotContain("13.08 Вынести");
        }

        /** «Сегодня» и «завтра» читаются быстрее даты — их и оставляем словами. */
        @org.junit.jupiter.api.Test
        void todayAndTomorrowKeepTheirNames() {
            String text =
                    DigestView.render(
                            "Дела на неделю",
                            List.of(
                                    due(mom, "Сегодняшнее", LocalDate.of(2026, 8, 13), LocalTime.of(19, 0)),
                                    due(mom, "Завтрашнее", LocalDate.of(2026, 8, 14), LocalTime.of(19, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Сегодня").contains("Завтра");
        }

        /** Дальше — день недели: «15.08» не отвечает на вопрос «это суббота или понедельник». */
        @org.junit.jupiter.api.Test
        void furtherDaysAreNamedByWeekday() {
            String text =
                    DigestView.render(
                            "Дела на неделю",
                            List.of(due(mom, "Тренировка", LocalDate.of(2026, 8, 15), LocalTime.of(19, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Суббота").contains("15.08");
        }

        @org.junit.jupiter.api.Test
        void undatedTasksGetTheirOwnHeading() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(undated(mom, "Разобрать шкаф")),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Без срока").contains("Разобрать шкаф");
        }
    }

    @org.junit.jupiter.api.Nested
    class Lines {

        /** Дайджест персональный: «Мама → Мама» на каждой строке ничего не сообщает. */
        @org.junit.jupiter.api.Test
        void ownTaskDoesNotRepeatTheRecipientsName() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(due(mom, "Вынести мусор", LocalDate.of(2026, 8, 13), LocalTime.of(19, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).doesNotContain("Мама");
        }

        @org.junit.jupiter.api.Test
        void aTaskFromSomeoneElseNamesTheAsker() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(due(kid, "Купить корм коту", LocalDate.of(2026, 8, 13), LocalTime.of(19, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("от Петя");
        }

        /** ⚠️ Название — пользовательский текст в HTML-сообщении: неэкранированное даёт HTTP 400. */
        @org.junit.jupiter.api.Test
        void titlesAreEscaped() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(due(mom, "<b>мусор</b> & хлеб", LocalDate.of(2026, 8, 13), LocalTime.of(19, 0))),
                            List.of(),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("&lt;b&gt;мусор&lt;/b&gt; &amp; хлеб").doesNotContain("<b>мусор");
        }
    }

    /**
     * Уроки в дайджесте — строками вперемешку с делами, а не блоком: человек читает утро подряд,
     * «в 07:30 портфель, в 08:30 математика».
     */
    @org.junit.jupiter.api.Nested
    class Lessons {

        /**
         * ⚠️ Уроки стоят <b>своим блоком после дел</b>, а не вперемешку с ними по времени.
         *
         * <p>Прежде было наоборот, и довод звучал разумно: человек читает утро подряд — «в 07:30
         * портфель, в 08:30 математика». С телефона 18 августа выяснилось, чего это стоит: шесть
         * уроков среди двух дел — это список, в котором дела не найти. У школьного дня свой ритм,
         * и он не про «что мне сделать».
         */
        @org.junit.jupiter.api.Test
        void lessonsStandInTheirOwnBlockAfterTheTasks() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(due(mom, "Вынести мусор", TODAY, LocalTime.of(19, 0))),
                            List.of(lesson(kid, "Математика", LocalTime.of(8, 30))),
                            kid,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Уроки").contains("Математика").contains("08:30–09:15");
            // урок раньше по времени, но ниже по сообщению: это отдельная сущность, а не дело
            assertThat(text.indexOf("мусор")).isLessThan(text.indexOf("Математика"));
        }

        /** Уроков нет — блока тоже нет: пустой заголовок «Уроки» читается как поломка. */
        @org.junit.jupiter.api.Test
        void withoutLessonsThereIsNoBlock() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(due(mom, "Вынести мусор", TODAY, LocalTime.of(19, 0))),
                            List.of(),
                            kid,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).doesNotContain("Уроки");
        }

        /** Родителю имя обязательно: своих уроков у него нет, а школьников в семье бывает двое. */
        @org.junit.jupiter.api.Test
        void aParentSeesWhoseLessonItIs() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(),
                            List.of(lesson(kid, "Математика", LocalTime.of(8, 30))),
                            mom,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).contains("Петя");
        }

        /** Школьнику своё имя в каждой строке — шум: он и так знает, чей это урок. */
        @org.junit.jupiter.api.Test
        void aPupilDoesNotSeeTheirOwnNameOnEveryLine() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(),
                            List.of(lesson(kid, "Математика", LocalTime.of(8, 30))),
                            kid,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).doesNotContain("Петя");
        }

        /** ⚠️ Урок идёт только в свой день недели: расписание — правило, а не список дат. */
        @org.junit.jupiter.api.Test
        void aLessonOfAnotherWeekdayDoesNotAppear() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(),
                            List.of(
                                    com.familytodo.domain.Lesson.create(
                                            1L,
                                            FAMILY,
                                            kid.id(),
                                            java.time.DayOfWeek.MONDAY,
                                            LocalTime.of(8, 30),
                                            LocalTime.of(9, 15),
                                            "Математика",
                                            LocalDate.of(2026, 8, 1),
                                            null,
                                            NOW)),
                            kid,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).doesNotContain("Математика");
        }

        /** Расписание вне срока действия — лето — не показывается вовсе. */
        @org.junit.jupiter.api.Test
        void aScheduleThatHasEndedDoesNotAppear() {
            String text =
                    DigestView.render(
                            "Дела на сегодня",
                            List.of(),
                            List.of(
                                    com.familytodo.domain.Lesson.create(
                                            1L,
                                            FAMILY,
                                            kid.id(),
                                            TODAY.getDayOfWeek(),
                                            LocalTime.of(8, 30),
                                            LocalTime.of(9, 15),
                                            "Математика",
                                            LocalDate.of(2025, 9, 1),
                                            LocalDate.of(2026, 5, 31),
                                            NOW)),
                            kid,
                            byId,
                            MOSCOW,
                            TODAY,
                            1,
                            NOW);

            assertThat(text).doesNotContain("Математика");
        }
    }

    private com.familytodo.domain.Lesson lesson(Member pupil, String subject, LocalTime at) {
        return com.familytodo.domain.Lesson.create(
                ids.incrementAndGet(),
                FAMILY,
                pupil.id(),
                TODAY.getDayOfWeek(),
                at,
                at.plusMinutes(45),
                subject,
                LocalDate.of(2026, 8, 1),
                null,
                NOW);
    }

    private Task due(Member creator, String title, LocalDate day, LocalTime time) {
        return Task.create(
                ids.incrementAndGet(),
                FAMILY,
                title,
                creator.id(),
                List.of(new Assignee(mom.id(), Role.PARENT)),
                day.atTime(time).atZone(MOSCOW).toInstant(),
                NOW);
    }

    private Task undated(Member creator, String title) {
        return Task.create(
                ids.incrementAndGet(),
                FAMILY,
                title,
                creator.id(),
                new ArrayList<>(List.of(new Assignee(mom.id(), Role.PARENT))),
                null,
                NOW);
    }
}

package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.familytodo.domain.Assignee;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Расписание HTML-файлом.
 *
 * <p>Второй вид рядом с картинкой: он подстраивается под экран, листается и вмещает то, чего в
 * картинку не влезает — место, исполнителя, статус, причину отказа.
 *
 * <p>Проверяем не вёрстку (она будет меняться), а то, что действительно ломается: файл
 * самодостаточен, ни одно дело не потерялось, ночное дело есть в обоих днях, и пользовательский
 * текст не может выполниться при открытии файла.
 */
class CalendarHtmlRendererTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Member> roster = roster();

    @Nested
    class SelfContained {

        /** Файл открывается локально: любая внешняя ссылка на телефоне без сети — пустое место. */
        @Test
        void makesNoExternalRequests() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html)
                    .doesNotContain("http://")
                    .doesNotContain("https://")
                    .doesNotContain("src=")
                    .doesNotContain("@import");
        }

        /**
         * ⚠️ Ни строки JavaScript — и это не про вкусы, а про безопасность: заголовок дела это
         * пользовательский текст, а файл открывается браузером.
         */
        @Test
        void containsNoScripts() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html).doesNotContain("<script").doesNotContain("javascript:");
            assertThat(html.toLowerCase()).doesNotContain("onclick");
        }

        @Test
        void isAWholeDocumentThatFitsAPhone() {
            String html = render(List.of(), List.of(), 1);

            assertThat(html).startsWith("<!doctype html>");
            assertThat(html).contains("<meta name=\"viewport\"");
            assertThat(html).contains("charset=\"utf-8\"");
        }

        /** Тёмная тема — не украшение: расписание чаще открывают вечером. */
        @Test
        void stylesBothThemes() {
            assertThat(render(List.of(), List.of(), 1)).contains("prefers-color-scheme: dark");
        }

        @Test
        void isValidUtf8WithCyrillicIntact() {
            byte[] bytes =
                    CalendarHtmlRenderer.render(
                            List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)),
                            List.of(),
                            roster,
                            MOSCOW,
                            MONDAY,
                            1);

            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("Школа");
        }
    }

    @Nested
    class Escaping {

        /**
         * ⚠️ Главная проверка файла. В сообщении Telegram неэкранированный текст даёт HTTP 400 —
         * неприятно, но безвредно. В HTML-документе, который человек открывает браузером, он
         * выполняется.
         */
        @Test
        void aTitleThatLooksLikeAScriptStaysText() {
            String html =
                    render(
                            List.of(scheduled("<script>alert(1)</script>", MONDAY, 8, 0, 8, 40)),
                            List.of(),
                            1);

            assertThat(html).doesNotContain("<script>alert(1)</script>");
            assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        }

        @Test
        void ampersandsAndAnglesInLocationAndReasonAreEscaped() {
            Task declined =
                    task(
                            "Забрать",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(9).toInstant(),
                            MONDAY.atStartOfDay(MOSCOW).plusHours(10).toInstant(),
                            "«Ока» & <b>склад</b>",
                            TaskStatus.DECLINED,
                            "не успею <по пробкам>");

            String html = render(List.of(declined), List.of(), 1);

            assertThat(html).contains("&amp;").contains("&lt;b&gt;склад&lt;/b&gt;");
            assertThat(html).doesNotContain("<b>склад</b>");
            assertThat(html).contains("&lt;по пробкам&gt;");
        }

        @Test
        void aMemberNameWithMarkupIsEscaped() {
            Map<Long, Member> tricky = new HashMap<>();
            tricky.put(
                    11L,
                    Member.join(11L, 1L, 111L, 111L, "<i>Петя</i>", Role.CHILD, Instant.EPOCH));

            byte[] bytes =
                    CalendarHtmlRenderer.render(
                            List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)),
                            List.of(),
                            tricky,
                            MOSCOW,
                            MONDAY,
                            1);

            assertThat(new String(bytes, StandardCharsets.UTF_8))
                    .contains("&lt;i&gt;Петя&lt;/i&gt;")
                    .doesNotContain("<i>Петя</i>");
        }
    }

    @Nested
    class Completeness {

        @Test
        void everyTaskOfTheWindowIsThere() {
            String html =
                    render(
                            List.of(
                                    scheduled("Школа", MONDAY, 8, 0, 8, 40),
                                    scheduled("Бассейн", MONDAY.plusDays(1), 18, 0, 19, 0),
                                    scheduled("Врач", MONDAY.plusDays(2), 11, 0, 11, 30)),
                            List.of(),
                            7);

            assertThat(html).contains("Школа").contains("Бассейн").contains("Врач");
        }

        /** То, чего нет на картинке: место, исполнитель, причина отказа. */
        @Test
        void showsWhatThePictureCannotFit() {
            Task declined =
                    task(
                            "Забрать детей",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(16).toInstant(),
                            MONDAY.atStartOfDay(MOSCOW).plusHours(17).toInstant(),
                            "садик",
                            TaskStatus.DECLINED,
                            "я на работе до восьми");

            String html = render(List.of(declined), List.of(), 1);

            assertThat(html)
                    .contains("садик")
                    .contains("Петя")
                    .contains("я на работе до восьми");
        }

        @Test
        void aTaskWithOnlyADeadlineShowsTheDeadline() {
            String html = render(List.of(withDueDate("Оплатить", MONDAY, 19, 0)), List.of(), 1);

            assertThat(html).contains("Оплатить").contains("19:00");
        }

        /**
         * ⚠️ Порядок внутри дня — по времени, а не по подписи.
         *
         * <p>Подпись срока начинается с «к », и сортировка строк уводила бы «к 19:00» в конец дня,
         * за «22:40». Тот же класс ошибки, что и текстовое хранение моментов времени.
         */
        @Test
        void itemsOfADayGoInTimeOrderEvenWhenDeadlinesMixWithIntervals() {
            String html =
                    render(
                            List.of(
                                    scheduled("Утро", MONDAY, 8, 0, 8, 40),
                                    scheduled("Ночь", MONDAY, 22, 40, 23, 30),
                                    withDueDate("Вечер", MONDAY, 19, 0)),
                            List.of(),
                            1);

            assertThat(html.indexOf("Утро"))
                    .isLessThan(html.indexOf("Вечер"));
            assertThat(html.indexOf("Вечер"))
                    .isLessThan(html.indexOf("Ночь"));
        }

        @Test
        void undatedTasksGetTheirOwnBlock() {
            String html =
                    render(List.of(), List.of(withDueDate("Купить хлеб", null, 0, 0)), 1);

            assertThat(html).contains("Купить хлеб");
        }

        @Test
        void anEmptyWindowStillProducesAReadablePage() {
            String html = render(List.of(), List.of(), 1);

            assertThat(html).contains("</html>");
            assertThat(html.length()).isGreaterThan(200);
        }

        /** Пустой день внутри окна не пропадает: иначе непонятно, свободен он или потерялся. */
        @Test
        void anEmptyDayInsideTheWindowStillGetsItsColumn() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 3);

            assertThat(html).contains(">1 Вт<").contains(">2 Ср<");
        }

        /**
         * ⚠️ Числа в CSS не зависят от локали машины.
         *
         * <p>У формата по умолчанию берётся локаль системы, и в русской «top:8.000%» превращается
         * в «top:8,000%» — правило, которое браузер молча отбросит, а блоки съедут в угол.
         */
        @Test
        void cssNumbersUseADotWhateverTheMachineLocale() {
            Locale previous = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.of("ru", "RU"));
                String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

                assertThat(html).contains("top:").doesNotContain(",000%");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, previous);
            }
        }
    }

    /**
     * Сетка по часам — форма из макетов `docs/с*.pdf`: ось времени слева, дни колонками,
     * пересекающиеся дела рядом.
     *
     * <p>Список (`docs/л*.pdf`) уже есть в самом сообщении расписания; файл повторял бы его
     * другими словами.
     */
    @Nested
    class TimeGrid {

        @Test
        void everyDayOfTheWindowGetsItsColumn() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 3);

            assertThat(countOf(html, "class=\"col\"")).isEqualTo(3);
        }

        @Test
        void theHourAxisIsThere() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html).contains("class=\"hours\"").contains(">08<").contains(">12<");
        }

        /** Блок стоит на своём месте по времени, а не просто идёт следом за предыдущим. */
        @Test
        void aBlockIsPositionedByItsTime() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html).containsPattern("class=\"block[^\"]*\" style=\"[^\"]*top:[^\"]*height:");
        }

        /** Пересекающиеся дела встают рядом, а не друг на друга — это и делает {@code Lanes}. */
        @Test
        void overlappingTasksSitSideBySide() {
            String html =
                    render(
                            List.of(
                                    scheduled("Линейка", MONDAY, 8, 0, 10, 0),
                                    scheduled("Отвезти", MONDAY, 8, 0, 8, 40)),
                            List.of(),
                            1);

            assertThat(countOf(html, "left:0.000%")).isEqualTo(1);
            assertThat(html).contains("left:50.000%");
        }

        /**
         * ⚠️ Ось растягивается под дело за её пределами.
         *
         * <p>Ровно здесь картинка однажды теряла ночное дело целиком: границы оси считались не теми
         * величинами, которыми потом рисовались блоки.
         */
        @Test
        void theAxisStretchesToCoverATaskOutsideTheDefaultHours() {
            String html = render(List.of(scheduled("Рано", MONDAY, 5, 0, 6, 0)), List.of(), 1);

            assertThat(html).contains(">05<").contains("Рано");
        }

        /** Широкая сетка листается внутри себя, а не растягивает страницу. */
        @Test
        void aWideGridScrollsInsideItsOwnContainer() {
            assertThat(render(List.of(), List.of(), 7)).contains("overflow-x: auto");
        }
    }

    /** Тридцать дней — месячная сетка, как `docs/с30.pdf`: недели строками, а не ось на месяц. */
    @Nested
    class MonthGrid {

        @Test
        void aMonthUsesWeekRowsInsteadOfAnHourAxis() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 30);

            assertThat(html).contains("class=\"month\"").doesNotContain("class=\"hours\"");
        }

        @Test
        void aMonthCellCarriesTheTimeBesideTheTitle() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 30);

            assertThat(html).contains("08:00").contains("Школа");
        }

        @Test
        void weekdayHeadersAreThere() {
            String html = render(List.of(), List.of(), 30);

            assertThat(html).contains("Пн").contains("Вс");
        }
    }

    @Nested
    class AcrossMidnight {

        /**
         * Дело через полночь есть в обоих днях, обрезанное по границам суток.
         *
         * <p>Ровно тот случай, на котором картинка однажды потеряла «спать 22:40–08:00».
         */
        @Test
        void aNightTaskAppearsInBothDays() {
            Task sleep =
                    task(
                            "Спать",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(22).plusMinutes(40).toInstant(),
                            MONDAY.plusDays(1).atStartOfDay(MOSCOW).plusHours(8).toInstant(),
                            "кровать",
                            TaskStatus.OPEN,
                            null);

            String html = render(List.of(sleep), List.of(), 2);

            assertThat(countOf(html, "Спать")).isEqualTo(2);
            assertThat(html).contains("22:40").contains("08:00");
        }

        /** Дело, кончающееся ровно в полночь, следующему дню не принадлежит. */
        @Test
        void aTaskEndingExactlyAtMidnightBelongsToOneDayOnly() {
            Task evening =
                    task(
                            "Уборка",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(23).toInstant(),
                            MONDAY.plusDays(1).atStartOfDay(MOSCOW).toInstant(),
                            null,
                            TaskStatus.OPEN,
                            null);

            assertThat(countOf(render(List.of(evening), List.of(), 2), "Уборка")).isEqualTo(1);
        }
    }

    @Nested
    class Robustness {

        @Test
        void aTaskWithoutLocationOrReasonDoesNotBreakRendering() {
            assertThatCode(
                            () ->
                                    render(
                                            List.of(withDueDate("Без места", MONDAY, 12, 0)),
                                            List.of(),
                                            1))
                    .doesNotThrowAnyException();
        }

        /** Исполнитель мог быть исключён из семьи — в составе его уже нет. */
        @Test
        void anUnknownAssigneeDoesNotBreakRendering() {
            assertThatCode(
                            () ->
                                    CalendarHtmlRenderer.render(
                                            List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)),
                                            List.of(),
                                            Map.of(),
                                            MOSCOW,
                                            MONDAY,
                                            1))
                    .doesNotThrowAnyException();
        }

        @Test
        void aMonthHorizonRendersWithoutFalling() {
            assertThatCode(() -> render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 30))
                    .doesNotThrowAnyException();
        }
    }

    // --- вспомогательное ---

    private String render(List<Task> dated, List<Task> undated, int days) {
        return new String(
                CalendarHtmlRenderer.render(dated, undated, roster, MOSCOW, MONDAY, days),
                StandardCharsets.UTF_8);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static Map<Long, Member> roster() {
        Map<Long, Member> byId = new HashMap<>();
        byId.put(10L, Member.join(10L, 1L, 110L, 110L, "Мама", Role.PARENT, Instant.EPOCH));
        byId.put(11L, Member.join(11L, 1L, 111L, 111L, "Петя", Role.CHILD, Instant.EPOCH));
        return byId;
    }

    private Task scheduled(String title, LocalDate day, int fromH, int fromM, int toH, int toM) {
        Instant from = day.atStartOfDay(MOSCOW).plusHours(fromH).plusMinutes(fromM).toInstant();
        Instant to = day.atStartOfDay(MOSCOW).plusHours(toH).plusMinutes(toM).toInstant();
        return task(title, null, from, to, "школа", TaskStatus.OPEN, null);
    }

    private Task withDueDate(String title, LocalDate day, int hour, int minute) {
        Instant due =
                day == null
                        ? null
                        : day.atStartOfDay(MOSCOW).plusHours(hour).plusMinutes(minute).toInstant();
        return task(title, due, null, null, null, TaskStatus.OPEN, null);
    }

    private Task task(
            String title,
            Instant dueAt,
            Instant startsAt,
            Instant endsAt,
            String location,
            TaskStatus status,
            String declineReason) {
        return Task.restore(
                ids.incrementAndGet(),
                1L,
                title,
                10L,
                new Assignee(11L, Role.CHILD),
                status,
                dueAt,
                declineReason,
                MONDAY.atStartOfDay(MOSCOW).toInstant(),
                null,
                startsAt,
                endsAt,
                location);
    }
}

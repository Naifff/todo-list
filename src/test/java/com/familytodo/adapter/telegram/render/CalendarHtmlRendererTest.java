package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.familytodo.domain.Assignee;
import com.familytodo.domain.Assignment;
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

    /**
     * Уроки на странице расписания.
     *
     * <p>⚠️ Урок не превращается в {@link com.familytodo.domain.Task}: у него нет ни исполнителя, ни
     * статуса, и поддельное дело утекло бы туда, где делам место, — в списки. На странице общая
     * только геометрия.
     */
    @Nested
    class Lessons {

        @Test
        void aLessonTakesItsPlaceInTheGrid() {
            String html =
                    new String(
                            CalendarHtmlRenderer.render(
                                    List.of(),
                                    List.of(),
                                    List.of(lesson("Математика", MONDAY.getDayOfWeek(), 8, 30)),
                                    roster,
                                    MOSCOW,
                                    MONDAY,
                                    1,
                                    MONDAY),
                            java.nio.charset.StandardCharsets.UTF_8);

            assertThat(html).contains("Математика").contains("lesson");
        }

        /** Отличать урок от дела нужно глазами: у своего класса своя заливка и пунктир. */
        @Test
        void aLessonIsMarkedOffFromTasks() {
            String html =
                    new String(
                            CalendarHtmlRenderer.render(
                                    List.of(scheduled("Тренировка", MONDAY, 18, 0, 19, 0)),
                                    List.of(),
                                    List.of(lesson("Математика", MONDAY.getDayOfWeek(), 8, 30)),
                                    roster,
                                    MOSCOW,
                                    MONDAY,
                                    1,
                                    MONDAY),
                            java.nio.charset.StandardCharsets.UTF_8);

            assertThat(html).contains("block lesson").contains(".block.lesson");
        }

        /** Урок другого дня недели в окно не попадает: расписание — правило, а не список дат. */
        @Test
        void aLessonOfAnotherWeekdayStaysOut() {
            String html =
                    new String(
                            CalendarHtmlRenderer.render(
                                    List.of(),
                                    List.of(),
                                    List.of(
                                            lesson(
                                                    "Математика",
                                                    MONDAY.plusDays(1).getDayOfWeek(),
                                                    8,
                                                    30)),
                                    roster,
                                    MOSCOW,
                                    MONDAY,
                                    1,
                                    MONDAY),
                            java.nio.charset.StandardCharsets.UTF_8);

            assertThat(html).doesNotContain("Математика");
        }

        /** ⚠️ Предмет — пользовательский текст: в документе неэкранированный не ломает, а исполняется. */
        @Test
        void aSubjectWithMarkupIsEscaped() {
            String html =
                    new String(
                            CalendarHtmlRenderer.render(
                                    List.of(),
                                    List.of(),
                                    List.of(
                                            lesson(
                                                    "<script>alert(1)</script>",
                                                    MONDAY.getDayOfWeek(),
                                                    8,
                                                    30)),
                                    roster,
                                    MOSCOW,
                                    MONDAY,
                                    1,
                                    MONDAY),
                            java.nio.charset.StandardCharsets.UTF_8);

            assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;");
        }

        private com.familytodo.domain.Lesson lesson(
                String subject, java.time.DayOfWeek day, int hour, int minute) {
            return com.familytodo.domain.Lesson.create(
                    ids.incrementAndGet(),
                    1L,
                    11L,
                    day,
                    java.time.LocalTime.of(hour, minute),
                    java.time.LocalTime.of(hour, minute).plusMinutes(45),
                    subject,
                    MONDAY.minusDays(30),
                    null,
                    Instant.EPOCH);
        }
    }

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
                            List.of(),
                            roster,
                            MOSCOW,
                            MONDAY,
                            1,
                            MONDAY);

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
                            List.of(), List.of(),
                            tricky,
                            MOSCOW,
                            MONDAY,
                            1,
                            MONDAY);

            assertThat(new String(bytes, StandardCharsets.UTF_8))
                    .contains("&lt;i&gt;Петя&lt;/i&gt;")
                    .doesNotContain("<i>Петя</i>");
        }
    }

    @Nested
    class MonthGridStatus {

        /**
         * ⚠️ В истории закрыты все дела, а закрытая плашка серая независимо от статуса: без
         * пометки месяц выходит ровным серым полем, где «сделано» и «отказ» неотличимы — ровно то,
         * ради чего историю и открывают.
         */
        @Test
        void closedTasksAreMarkedInTheMonthGrid() {
            Task done =
                    task(
                            "Школа",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(8).toInstant(),
                            MONDAY.atStartOfDay(MOSCOW).plusHours(14).toInstant(),
                            null,
                            TaskStatus.DONE,
                            null);
            Task refused =
                    task(
                            "Забрать посылку",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(16).toInstant(),
                            MONDAY.atStartOfDay(MOSCOW).plusHours(17).toInstant(),
                            null,
                            TaskStatus.DECLINED,
                            "работал до восьми");

            String html = render(List.of(done, refused), List.of(), 30);

            assertThat(html).contains("✓ Школа").contains("✕ Забрать посылку");
        }

        /** Открытому делу помечать нечего: расписание вперёд состоит из них целиком. */
        @Test
        void openTasksCarryNoMark() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 30);

            assertThat(html).contains("Школа").doesNotContain("✓").doesNotContain("✕");
        }
    }

    @Nested
    class TodayInTheMonthGrid {

        /** В череде одинаковых квадратиков сегодняшний день иначе не найти. */
        @Test
        void todaysCellIsMarked() {
            String html = render(List.of(), List.of(), MONDAY, 30, MONDAY.plusDays(3));

            assertThat(html).contains("cell today");
        }

        /** У истории окно кончается вчера — отмечать в нём нечего. */
        @Test
        void aPastMonthMarksNothing() {
            String html = render(List.of(), List.of(), MONDAY.minusDays(30), 30, MONDAY);

            assertThat(html).doesNotContain("cell today");
        }
    }

    @Nested
    class RelativeDayNames {

        /**
         * ⚠️ «Сегодня» считается от настоящего сегодня, а не от начала окна.
         *
         * <p>У расписания вперёд это одно и то же, и параметр назывался {@code today}, а приходило
         * в него начало окна. У истории окно кончается вчера — и первый её день подписывался
         * «сегодня», а второй «завтра». Нашлось не тестом, а взглядом на скачанный файл.
         */
        @Test
        void aPastWindowNamesNoDayTodayOrTomorrow() {
            LocalDate from = MONDAY.minusDays(7);
            String html =
                    renderList(
                            List.of(scheduled("Школа", from.plusDays(1), 8, 0, 8, 40)),
                            List.of(),
                            from,
                            7,
                            MONDAY);

            assertThat(html).doesNotContain("сегодня").doesNotContain("завтра");
        }

        @Test
        void aForwardWindowStillNamesTodayAndTomorrow() {
            String html =
                    renderList(
                            List.of(
                                    scheduled("Школа", MONDAY, 8, 0, 8, 40),
                                    scheduled("Бассейн", MONDAY.plusDays(1), 18, 0, 19, 0)),
                            List.of(),
                            3);

            assertThat(html).contains("сегодня").contains("завтра");
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
     * Сетка по часам — форма из макетов «сеткой»: ось времени слева, дни колонками,
     * пересекающиеся дела рядом.
     *
     * <p>Список («списком») уже есть в самом сообщении расписания; файл повторял бы его
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

        /**
         * ⚠️ Ось подписывает и закрывающий час.
         *
         * <p>Подписи по умолчанию идут 07..19, потому что подписывается начало часа. Строка до
         * 20:00 при этом есть, но день читается как обрезанный на семи вечера — на это и пожаловались
         * с телефона.
         */
        @Test
        void theAxisLabelsItsClosingHour() {
            String html = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html).contains(">20<");
        }

        /**
         * ⚠️ Короткое дело не обрезается текстом.
         *
         * <p>Получасовой блок на телефоне — это около двадцати пикселей, а внутри время, название,
         * место и исполнитель. С {@code overflow: hidden} название срезало на половине буквы: так
         * «побегать» и превратилось в «побегат». Блок обязан растягиваться под своё содержимое.
         */
        @Test
        void aShortBlockGrowsToFitItsText() {
            assertThat(render(List.of(withDueDate("Побегать", MONDAY, 19, 0)), List.of(), 1))
                    .contains("min-height: min-content");
        }

        /**
         * ⚠️ Под последним делом остаётся запас, иначе растянувшийся блок срежет край сетки.
         *
         * <p>Дело «к 19:00» кончается в 19:30, ось по умолчанию — до 20:00, и блок, выросший под
         * текст, упирался в границу контейнера.
         */
        @Test
        void theAxisKeepsAnHourOfHeadroomBelowTheLastTask() {
            String html = render(List.of(withDueDate("Побегать", MONDAY, 19, 0)), List.of(), 1);

            assertThat(html).contains(">21<");
        }

        /**
         * ⚠️ Ширину колонки задаёт число дней, а не только ширина экрана.
         *
         * <p>Один день занимает экран целиком, но у недели колонка обязана быть узкой. Правило
         * {@code --col: 100%} в медиазапросе делало каждую из семи колонок во весь экран: на
         * телефоне была видна ровно первая, и неделя выглядела как один день.
         */
        @Test
        void aMultiDayGridDoesNotGiveEveryColumnTheWholeScreen() {
            String week = render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 7);

            assertThat(week).doesNotContain("--col:100%");
            assertThat(week).containsPattern("--col:\\d+px");
        }

        @Test
        void aSingleDayFillsTheScreen() {
            assertThat(render(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1))
                    .contains("--col:100%");
        }

        /** На узком экране час выше: иначе в блок не помещается ни строки. */
        @Test
        void narrowScreensGetTallerHours() {
            assertThat(render(List.of(), List.of(), 1)).contains("@media (max-width:");
        }

        /** Широкая сетка листается внутри себя, а не растягивает страницу. */
        @Test
        void aWideGridScrollsInsideItsOwnContainer() {
            assertThat(render(List.of(), List.of(), 7)).contains("overflow-x: auto");
        }
    }

    /** Тридцать дней — месячная сетка, как месячный макет: недели строками, а не ось на месяц. */
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

    /**
     * Второй вид того же файла — список, форма из списка.
     *
     * <p>Он занял место картинки: у сетки день упирается в ось и её границы, у списка границ нет
     * вовсе. Выбирает человек, а не мы.
     */
    @Nested
    class ListView {

        @Test
        void listHasNoHourAxisAndNoGrid() {
            String html = renderList(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 1);

            assertThat(html).doesNotContain("class=\"hours\"").doesNotContain("class=\"grid\"");
            assertThat(html).contains("Школа");
        }

        @Test
        void everyDayOfTheWindowGetsItsHeadingIncludingEmptyOnes() {
            String html = renderList(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 3);

            assertThat(countOf(html, "<h2>")).isEqualTo(3);
            assertThat(html).contains("свободно");
        }

        @Test
        void itemsOfADayGoInTimeOrder() {
            String html =
                    renderList(
                            List.of(
                                    scheduled("Утро", MONDAY, 8, 0, 8, 40),
                                    scheduled("Ночь", MONDAY, 22, 40, 23, 30),
                                    withDueDate("Вечер", MONDAY, 19, 0)),
                            List.of(),
                            1);

            assertThat(html.indexOf("Утро")).isLessThan(html.indexOf("Вечер"));
            assertThat(html.indexOf("Вечер")).isLessThan(html.indexOf("Ночь"));
        }

        @Test
        void listCarriesWhatTheGridCarries() {
            Task declined =
                    task(
                            "Забрать детей",
                            null,
                            MONDAY.atStartOfDay(MOSCOW).plusHours(16).toInstant(),
                            MONDAY.atStartOfDay(MOSCOW).plusHours(17).toInstant(),
                            "садик",
                            TaskStatus.DECLINED,
                            "я на работе до восьми");

            String html = renderList(List.of(declined), List.of(), 1);

            assertThat(html).contains("садик").contains("Петя").contains("я на работе до восьми");
        }

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

            assertThat(countOf(renderList(List.of(sleep), List.of(), 2), "Спать")).isEqualTo(2);
        }

        @Test
        void undatedTasksGetTheirOwnBlock() {
            String html = renderList(List.of(), List.of(withDueDate("Купить хлеб", null, 0, 0)), 1);

            assertThat(html).contains("Купить хлеб").contains("Без даты");
        }

        /** Экранирование общее с сеткой, но проверяется и здесь: это отдельный вывод. */
        @Test
        void aTitleThatLooksLikeAScriptStaysText() {
            String html =
                    renderList(
                            List.of(scheduled("<script>alert(1)</script>", MONDAY, 8, 0, 8, 40)),
                            List.of(),
                            1);

            assertThat(html).doesNotContain("<script>alert(1)</script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        void aMonthHorizonIsAListTooWithoutAMonthGrid() {
            String html = renderList(List.of(scheduled("Школа", MONDAY, 8, 0, 8, 40)), List.of(), 30);

            assertThat(html).doesNotContain("class=\"month\"").contains("Школа");
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
                                            List.of(),
                Map.of(),
                                            MOSCOW,
                                            MONDAY,
                                            1,
                                            MONDAY))
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
        return render(dated, undated, MONDAY, days, MONDAY);
    }

    private String render(
            List<Task> dated, List<Task> undated, LocalDate from, int days, LocalDate today) {
        return new String(
                CalendarHtmlRenderer.render(dated, undated, List.of(), roster, MOSCOW, from, days, today),
                StandardCharsets.UTF_8);
    }

    private String renderList(List<Task> dated, List<Task> undated, int days) {
        return renderList(dated, undated, MONDAY, days, MONDAY);
    }

    private String renderList(
            List<Task> dated, List<Task> undated, LocalDate from, int days, LocalDate today) {
        return new String(
                CalendarHtmlRenderer.renderList(dated, undated, List.of(), roster, MOSCOW, from, days, today),
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
        return task(
                title,
                dueAt,
                startsAt,
                endsAt,
                location,
                status,
                declineReason,
                List.of(new Assignment(11L, Role.CHILD, null, null)));
    }

    /**
     * Палитра пастельная, а текст на плашке — тёмный, и это <b>связка</b>: поменять одно и забыть
     * второе значит получить белым по светло-голубому или чёрным по густо-синему.
     *
     * <p>Проверяем не оттенок, а следствие — читаемость. Порог 4.5:1 — тот же, что у WCAG AA для
     * обычного текста; в плашке он мелкий, и запас тут не роскошь.
     */
    @Nested
    class PlateContrast {

        @Test
        void everyMemberColourIsReadableUnderThePlateInk() {
            String css = new String(
                    CalendarHtmlRenderer.render(
                            List.of(), List.of(), List.of(), Map.of(), MOSCOW, MONDAY, 1, MONDAY),
                    StandardCharsets.UTF_8);
            String ink = value(css, "--on-plate");

            for (com.familytodo.domain.MemberColor color : com.familytodo.domain.MemberColor.values()) {
                assertThat(contrast(color.hex(), ink))
                        .describedAs("контраст текста на плашке %s", color.title())
                        .isGreaterThanOrEqualTo(4.5);
            }
        }

        /** Закрытая плашка серая — но теми же чернилами, и читаться обязана так же. */
        @Test
        void theClosedPlateIsReadableToo() {
            String css = new String(
                    CalendarHtmlRenderer.render(
                            List.of(), List.of(), List.of(), Map.of(), MOSCOW, MONDAY, 1, MONDAY),
                    StandardCharsets.UTF_8);

            assertThat(contrast(value(css, "--plate-done"), value(css, "--on-plate")))
                    .isGreaterThanOrEqualTo(4.5);
            assertThat(contrast(value(css, "--plate-none"), value(css, "--on-plate")))
                    .isGreaterThanOrEqualTo(4.5);
        }

        private String value(String css, String name) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile(name + ":\\s*(#[0-9a-fA-F]{6})").matcher(css);
            assertThat(m.find()).describedAs("в стиле нет %s", name).isTrue();
            return m.group(1);
        }

        /** WCAG: (L1 + 0.05) / (L2 + 0.05) по относительной яркости. */
        private double contrast(String first, String second) {
            double a = luminance(first);
            double b = luminance(second);
            return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        }

        private double luminance(String hex) {
            double[] channel = new double[3];
            for (int i = 0; i < 3; i++) {
                double raw = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
                channel[i] = raw <= 0.03928 ? raw / 12.92 : Math.pow((raw + 0.055) / 1.055, 2.4);
            }
            return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
        }
    }

    /** Форма с явными исполнителями: нужна там, где проверяется дело на нескольких. */
    private Task task(
            String title,
            Instant dueAt,
            Instant startsAt,
            Instant endsAt,
            String location,
            TaskStatus status,
            String declineReason,
            List<Assignment> assignments) {
        List<Assignment> stored =
                declineReason == null
                        ? assignments
                        : assignments.stream()
                                .map(
                                        assignment ->
                                                new Assignment(
                                                        assignment.memberId(),
                                                        assignment.role(),
                                                        MONDAY.atStartOfDay(MOSCOW).toInstant(),
                                                        declineReason))
                                .toList();
        return Task.restore(
                ids.incrementAndGet(),
                1L,
                title,
                10L,
                stored,
                status,
                dueAt,
                MONDAY.atStartOfDay(MOSCOW).toInstant(),
                null,
                startsAt,
                endsAt,
                location,
                null,
                null);
    }
}

package com.familytodo.adapter.telegram.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.familytodo.domain.Assignee;
import com.familytodo.domain.Role;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Календарь картинкой.
 *
 * <p>Проверять пиксель за пикселем бессмысленно — вёрстка будет меняться. Проверяем то, что
 * действительно ломается: PNG собирается и разбирается обратно, картинка не пустая, дела на ней
 * есть, а вырожденные случаи (пересечения, дело без времени, пустой день, дело за границей суток)
 * не роняют отрисовку. Точную логику раскладки держит {@link LanesTest}.
 */
class CalendarRendererTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private final AtomicLong ids = new AtomicLong();

    @Nested
    class Shape {

        @Test
        void dayGridIsAReadablePng() throws IOException {
            BufferedImage image = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 1));

            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isBetween(600, 1600);
            assertThat(image.getHeight()).isBetween(400, 4000);
        }

        @Test
        void weekGridIsWiderThanDayGrid() throws IOException {
            int day = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 1)).getWidth();
            int week = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 7)).getWidth();

            assertThat(week).isGreaterThan(day);
        }

        @Test
        void monthHorizonGivesTheWideGridWithSevenColumns() throws IOException {
            BufferedImage month = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 30));
            BufferedImage week = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 7));

            // месяц — сетка чисел, а не ось времени: он ниже недели при той же ширине колонок
            assertThat(month.getWidth()).isPositive();
            assertThat(month.getHeight()).isNotEqualTo(week.getHeight());
        }

        /** Телеграм жмёт фото; мегабайты тут ни к чему, а совсем пустой ответ означал бы отказ. */
        @Test
        void pngIsSmallEnoughToSend() {
            byte[] png = CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 7);

            assertThat(png.length).isBetween(1_000, 5_000_000);
        }
    }

    @Nested
    class Contents {

        @Test
        void gridIsDrawnEvenWithoutTasks() throws IOException {
            BufferedImage image = read(CalendarRenderer.render(List.of(), MOSCOW, MONDAY, 1));

            assertThat(distinctColours(image))
                    .describedAs("сетка и подписи часов рисуются и на пустом дне")
                    .isGreaterThan(2);
        }

        @Test
        void taskIsPaintedWithTheAccentColour() throws IOException {
            BufferedImage withTask = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 1));
            BufferedImage empty = read(CalendarRenderer.render(List.of(), MOSCOW, MONDAY, 1));

            assertThat(hasAccent(withTask)).isTrue();
            assertThat(hasAccent(empty))
                    .describedAs("без дел акцентного цвета быть не должно")
                    .isFalse();
        }

        /** Ось растягивается под данные: дело в 22:00 иначе просто не попало бы на картинку. */
        @Test
        void lateTaskStretchesTheTimeAxis() throws IOException {
            List<Task> late = List.of(scheduled("Ночной рейс", MONDAY, 22, 0, 23, 30));

            int normal = read(CalendarRenderer.render(oneMorningTask(), MOSCOW, MONDAY, 1)).getHeight();
            int stretched = read(CalendarRenderer.render(late, MOSCOW, MONDAY, 1)).getHeight();

            assertThat(stretched).isGreaterThan(normal);
        }
    }

    @Nested
    class DegenerateCases {

        @Test
        void overlappingIntervalsDoNotThrow() {
            List<Task> overlapping =
                    List.of(
                            scheduled("Линейка", MONDAY, 8, 0, 10, 0),
                            scheduled("Отвезти детей", MONDAY, 8, 0, 8, 40),
                            scheduled("Daily standup", MONDAY, 9, 30, 10, 0));

            assertThatCode(() -> CalendarRenderer.render(overlapping, MOSCOW, MONDAY, 1))
                    .doesNotThrowAnyException();
        }

        /** Дело со сроком, но без интервала: времени начала у него нет, а показать надо. */
        @Test
        void deadlineWithoutAnIntervalIsDrawn() throws IOException {
            List<Task> deadline = List.of(withDueDate("Сдать документы", MONDAY, 18, 0));

            assertThat(hasAccent(read(CalendarRenderer.render(deadline, MOSCOW, MONDAY, 1)))).isTrue();
        }

        @Test
        void undatedTaskDoesNotThrowAndDoesNotAppear() throws IOException {
            List<Task> undated = List.of(withDueDate("Когда-нибудь", null, 0, 0));

            BufferedImage image = read(CalendarRenderer.render(undated, MOSCOW, MONDAY, 1));

            assertThat(hasAccent(image))
                    .describedAs("делу без даты не место на календаре — его показывает список")
                    .isFalse();
        }

        @Test
        void taskOutsideTheWindowIsSkipped() throws IOException {
            List<Task> far = List.of(scheduled("Через месяц", MONDAY.plusDays(40), 9, 0, 10, 0));

            assertThat(hasAccent(read(CalendarRenderer.render(far, MOSCOW, MONDAY, 7)))).isFalse();
        }

        @Test
        void veryLongTitleDoesNotThrow() {
            List<Task> wordy =
                    List.of(scheduled("Ф".repeat(200), MONDAY, 9, 0, 10, 0));

            assertThatCode(() -> CalendarRenderer.render(wordy, MOSCOW, MONDAY, 1))
                    .doesNotThrowAnyException();
        }

        /** Дело через полночь: у него нет одного дня, и наивная арифметика даёт отрицательную высоту. */
        @Test
        void taskCrossingMidnightDoesNotThrow() {
            List<Task> nightShift = List.of(scheduled("Ночная смена", MONDAY, 23, 0, 25, 0));

            assertThatCode(() -> CalendarRenderer.render(nightShift, MOSCOW, MONDAY, 3))
                    .doesNotThrowAnyException();
        }

        @Test
        void aBusyMonthDoesNotThrow() {
            List<Task> many = new ArrayList<>();
            for (int day = 0; day < 30; day++) {
                for (int hour = 8; hour < 20; hour++) {
                    many.add(scheduled("Дело " + hour, MONDAY.plusDays(day), hour, 0, hour, 45));
                }
            }

            assertThatCode(() -> CalendarRenderer.render(many, MOSCOW, MONDAY, 30))
                    .doesNotThrowAnyException();
        }
    }

    // --- вспомогательное ---

    private List<Task> oneMorningTask() {
        return List.of(scheduled("Отвезти детей в школу", MONDAY, 8, 0, 8, 40));
    }

    private Task scheduled(String title, LocalDate day, int fromH, int fromM, int toH, int toM) {
        Instant from = day.atStartOfDay(MOSCOW).plusHours(fromH).plusMinutes(fromM).toInstant();
        Instant to = day.atStartOfDay(MOSCOW).plusHours(toH).plusMinutes(toM).toInstant();
        return task(title, null, from, to, "школа");
    }

    private Task withDueDate(String title, LocalDate day, int hour, int minute) {
        Instant due =
                day == null ? null : day.atStartOfDay(MOSCOW).plusHours(hour).plusMinutes(minute).toInstant();
        return task(title, due, null, null, null);
    }

    private Task task(String title, Instant dueAt, Instant startsAt, Instant endsAt, String location) {
        return Task.restore(
                ids.incrementAndGet(),
                1L,
                title,
                10L,
                new Assignee(11L, Role.CHILD),
                TaskStatus.OPEN,
                dueAt,
                null,
                MONDAY.atStartOfDay(MOSCOW).toInstant(),
                null,
                startsAt,
                endsAt,
                location);
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int distinctColours(BufferedImage image) {
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < image.getWidth(); x += 2) {
            for (int y = 0; y < image.getHeight(); y += 2) {
                seen.add(image.getRGB(x, y));
            }
        }
        return seen.size();
    }

    /** Дела рисуются заливкой акцентного цвета — по нему и видно, что они на картинке есть. */
    private static boolean hasAccent(BufferedImage image) {
        int accent = CalendarRenderer.ACCENT.getRGB();
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) == accent) {
                    return true;
                }
            }
        }
        return false;
    }
}

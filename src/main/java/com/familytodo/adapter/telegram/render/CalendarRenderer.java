package com.familytodo.adapter.telegram.render;

import com.familytodo.domain.Task;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Календарь картинкой: сетка дней с осью времени либо сетка чисел на месяц.
 *
 * <p>Сетку Telegram не рисует — в сообщении нет ни таблиц, ни цвета, ни позиционирования, — поэтому
 * приблизиться к макетам можно только картинкой. Компромисс: <b>на картинке нет кнопок</b>.
 * Обзорный режим показывает расположение дел во времени, а действия остаются у списка.
 *
 * <p>Рисование через Java2D: оно есть в JDK, новых зависимостей не нужно. Шрифт — свой, см. {@link
 * Fonts}; системным на голой VPS доверять нельзя.
 */
public final class CalendarRenderer {

    /** Цвет дел из макетов. Публичен, потому что по нему тест и проверяет, что дела нарисовались. */
    public static final Color ACCENT = new Color(0xF2, 0x6B, 0x21);

    private static final Color INK = new Color(0x1C, 0x1C, 0x1E);
    private static final Color MUTED = new Color(0x8A, 0x8A, 0x8E);
    private static final Color LINE = new Color(0xD6, 0xD6, 0xDA);
    private static final Color BAND = new Color(0xF0, 0xF0, 0xF2);
    private static final Color PAPER = Color.WHITE;
    private static final Color ON_ACCENT = Color.WHITE;

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TITLE_DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", RU);

    private static final int PAD = 16;
    private static final int HEADER = 56;
    private static final int DAY_HEADER = 30;
    private static final int GUTTER = 52;

    /** Ниже 8:00 и выше 20:00 ось не сжимается: пустое утро понятнее, чем прыгающий масштаб. */
    private static final int DEFAULT_FROM_HOUR = 8;

    private static final int DEFAULT_TO_HOUR = 20;

    private CalendarRenderer() {}

    public static byte[] render(List<Task> tasks, ZoneId zone, LocalDate from, int days) {
        return days > 7 ? month(tasks, zone, from, days) : timeGrid(tasks, zone, from, days);
    }

    // --- сетка с осью времени: 1..7 дней ---

    private static byte[] timeGrid(List<Task> tasks, ZoneId zone, LocalDate from, int days) {
        List<LocalDate> columns = daysFrom(from, days);
        Map<LocalDate, List<Entry>> byDay = group(tasks, zone, columns);

        int fromHour = DEFAULT_FROM_HOUR;
        int toHour = DEFAULT_TO_HOUR;
        // границы считаются теми же величинами, которыми потом рисуются блоки. Разные
        // правила для оси и для блока дали отказ в проде: у дела через полночь `to.getHour()`
        // равен 8, ось не растягивалась, и ночное дело пропадало с картинки целиком
        for (List<Entry> day : byDay.values()) {
            for (Entry e : day) {
                fromHour = Math.min(fromHour, e.startSecond() / 3600);
                toHour = Math.max(toHour, Math.ceilDiv(e.endSecond(), 3600));
            }
        }
        toHour = Math.min(24, Math.max(toHour, fromHour + 1));

        int colWidth = days == 1 ? 700 : days <= 3 ? 300 : 150;
        int hourHeight = days == 1 ? 58 : 44;
        int width = PAD * 2 + GUTTER + days * colWidth;
        int gridTop = PAD + HEADER + DAY_HEADER;
        int height = gridTop + (toHour - fromHour) * hourHeight + PAD;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);

        banner(g, width, periodTitle(columns));

        int gridLeft = PAD + GUTTER;
        hourLines(g, gridLeft, gridTop, width - PAD, fromHour, toHour, hourHeight);

        for (int i = 0; i < columns.size(); i++) {
            LocalDate day = columns.get(i);
            int x = gridLeft + i * colWidth;
            columnHeader(g, x, PAD + HEADER, colWidth, day);
            if (i > 0) {
                g.setColor(LINE);
                g.drawLine(x, PAD + HEADER, x, height - PAD);
            }
            entries(g, byDay.get(day), x, colWidth, gridTop, fromHour, toHour, hourHeight);
        }

        g.setColor(LINE);
        g.drawRect(gridLeft, gridTop, days * colWidth, (toHour - fromHour) * hourHeight);
        g.dispose();
        return png(image);
    }

    private static void hourLines(
            Graphics2D g, int left, int top, int right, int fromHour, int toHour, int hourHeight) {
        g.setFont(Fonts.regular(11f));
        for (int hour = fromHour; hour <= toHour; hour++) {
            int y = top + (hour - fromHour) * hourHeight;
            g.setColor(LINE);
            g.drawLine(left, y, right, y);
            if (hour < toHour) {
                g.setColor(MUTED);
                g.drawString("%02d".formatted(hour % 24), PAD + 12, y + 12);
            }
        }
    }

    private static void columnHeader(Graphics2D g, int x, int y, int width, LocalDate day) {
        g.setColor(INK);
        g.setFont(Fonts.bold(12f));
        String label =
                day.getDayOfMonth()
                        + " "
                        + capitalise(day.getDayOfWeek().getDisplayName(TextStyle.SHORT, RU));
        g.drawString(label, x + 6, y + 19);
    }

    private static void entries(
            Graphics2D g,
            List<Entry> entries,
            int x,
            int colWidth,
            int gridTop,
            int fromHour,
            int toHour,
            int hourHeight) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int axisFrom = fromHour * 3600;
        int axisTo = toHour * 3600;

        List<Lanes.Span<Entry>> spans = new ArrayList<>();
        for (Entry e : entries) {
            // клип по оси: дело через полночь иначе даёт отрицательную высоту
            int start = Math.max(axisFrom, e.startSecond());
            int end = Math.min(axisTo, Math.max(e.endSecond(), e.startSecond() + 900));
            if (end > axisFrom && start < axisTo) {
                spans.add(new Lanes.Span<>(e, start, Math.max(end, start + 600)));
            }
        }

        for (Lanes.Placed<Entry> placed : Lanes.pack(spans)) {
            int laneWidth = (colWidth - 8) / placed.lanes();
            int bx = x + 4 + placed.lane() * laneWidth;
            int by = gridTop + (placed.fromSecond() - axisFrom) * hourHeight / 3600;
            int bh = Math.max(16, (placed.toSecond() - placed.fromSecond()) * hourHeight / 3600 - 2);

            block(g, bx, by, laneWidth - 3, bh, placed.value());
        }
    }

    private static void block(Graphics2D g, int x, int y, int width, int height, Entry entry) {
        g.setColor(ACCENT);
        g.fillRoundRect(x, y, width, height, 6, 6);

        int textWidth = width - 10;
        int baseline = y + 13;
        int bottom = y + height - 2;

        g.setColor(ON_ACCENT);
        g.setFont(Fonts.bold(11f));
        int room = Math.max(0, (bottom - baseline + 12) / 12);
        // место осталось хотя бы под одну строку — значит, у названия есть право на две
        List<String> title = TextFit.lines(entry.label(), g.getFontMetrics(), textWidth, Math.min(room, 3));
        for (String line : title) {
            g.drawString(line, x + 5, baseline);
            baseline += 12;
        }

        if (entry.location() == null || baseline > bottom) {
            return;
        }
        g.setFont(Fonts.regular(10f));
        for (String line :
                TextFit.lines(entry.location(), g.getFontMetrics(), textWidth, (bottom - baseline + 11) / 11)) {
            g.drawString(line, x + 5, baseline);
            baseline += 11;
        }
    }

    // --- сетка чисел: горизонт больше недели ---

    private static byte[] month(List<Task> tasks, ZoneId zone, LocalDate from, int days) {
        // начинаем с понедельника недели, в которую попадает первый день: иначе колонки
        // перестают совпадать с днями недели и сетка перестаёт читаться как календарь
        LocalDate start = from.minusDays((from.getDayOfWeek().getValue() + 6) % 7);
        long span = ChronoUnit.DAYS.between(start, from.plusDays(days));
        int weeks = Math.max(1, Math.min(6, (int) Math.ceil(span / 7.0)));

        List<LocalDate> columns = daysFrom(start, weeks * 7);
        Map<LocalDate, List<Entry>> byDay = group(tasks, zone, columns);

        int cellWidth = 150;
        int cellHeight = 132;
        int width = PAD * 2 + 7 * cellWidth;
        int gridTop = PAD + HEADER + DAY_HEADER;
        int height = gridTop + weeks * cellHeight + PAD;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);
        banner(g, width, periodTitle(List.of(from, from.plusDays(days - 1L))));

        g.setFont(Fonts.bold(12f));
        for (int c = 0; c < 7; c++) {
            g.setColor(INK);
            String name = capitalise(start.plusDays(c).getDayOfWeek().getDisplayName(TextStyle.SHORT, RU));
            g.drawString(name, PAD + c * cellWidth + 8, PAD + HEADER + 19);
        }

        for (int i = 0; i < columns.size(); i++) {
            LocalDate day = columns.get(i);
            int x = PAD + (i % 7) * cellWidth;
            int y = gridTop + (i / 7) * cellHeight;

            g.setColor(LINE);
            g.drawRect(x, y, cellWidth, cellHeight);
            g.setColor(day.isBefore(from) ? MUTED : INK);
            g.setFont(Fonts.regular(12f));
            g.drawString(String.valueOf(day.getDayOfMonth()), x + 7, y + 16);

            chips(g, byDay.get(day), x, y, cellWidth, cellHeight);
        }
        g.dispose();
        return png(image);
    }

    private static void chips(
            Graphics2D g, List<Entry> entries, int x, int y, int cellWidth, int cellHeight) {
        if (entries == null) {
            return;
        }
        int chipY = y + 24;
        int shown = 0;
        for (Entry e : entries) {
            if (chipY + 15 > y + cellHeight - 4) {
                g.setColor(MUTED);
                g.setFont(Fonts.regular(10f));
                g.drawString("…ещё " + (entries.size() - shown), x + 7, y + cellHeight - 6);
                return;
            }
            g.setColor(ACCENT);
            g.fillRoundRect(x + 5, chipY, cellWidth - 10, 14, 4, 4);
            g.setColor(ON_ACCENT);
            g.setFont(Fonts.regular(9.5f));
            List<String> chip =
                    TextFit.lines(
                            e.from().format(TIME) + " " + e.label(), g.getFontMetrics(), cellWidth - 18, 1);
            if (!chip.isEmpty()) {
                g.drawString(chip.getFirst(), x + 9, chipY + 11);
            }
            chipY += 17;
            shown++;
        }
    }

    // --- общее ---

    private static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(PAPER);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setStroke(new BasicStroke(1f));
        return g;
    }

    private static void banner(Graphics2D g, int width, String title) {
        g.setColor(BAND);
        g.fillRoundRect(PAD, PAD, width - PAD * 2, HEADER - 10, 8, 8);
        g.setColor(INK);
        g.setFont(Fonts.bold(19f));
        g.drawString(title, PAD + 16, PAD + 30);
    }

    private static String periodTitle(List<LocalDate> columns) {
        LocalDate first = columns.getFirst();
        LocalDate last = columns.getLast();
        return first.equals(last)
                ? capitalise(first.getDayOfWeek().getDisplayName(TextStyle.FULL, RU))
                        + ", "
                        + first.format(TITLE_DAY)
                : first.format(TITLE_DAY) + " — " + last.format(TITLE_DAY);
    }

    /**
     * Дело на картинке — это интервал. Дело только со сроком получает получасовой блок в момент
     * срока: показать его надо, а длительности у него нет. Дела без даты вовсе не попадают сюда —
     * им нет места на оси времени, их показывает список.
     */
    private static Map<LocalDate, List<Entry>> group(
            List<Task> tasks, ZoneId zone, List<LocalDate> columns) {
        Map<LocalDate, List<Entry>> byDay = new LinkedHashMap<>();
        for (LocalDate day : columns) {
            byDay.put(day, new ArrayList<>());
        }
        for (Task task : tasks) {
            Instant startsAt = task.startsAt() != null ? task.startsAt() : task.dueAt();
            if (startsAt == null) {
                continue;
            }
            Instant endsAt = task.endsAt() != null ? task.endsAt() : startsAt.plusSeconds(1800);
            LocalDateTime start = LocalDateTime.ofInstant(startsAt, zone);
            LocalDateTime end = LocalDateTime.ofInstant(endsAt, zone);

            // дело через полночь попадает в колонку каждого дня, который занимает, обрезанное
            // по границам суток. Иначе сон обрывается на полуночи и наутро его нет
            for (LocalDate day = start.toLocalDate();
                    !day.isAfter(end.toLocalDate());
                    day = day.plusDays(1)) {
                List<Entry> bucket = byDay.get(day);
                if (bucket == null) {
                    continue;
                }
                LocalDateTime dayStart = day.atStartOfDay();
                LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
                LocalDateTime from = start.isAfter(dayStart) ? start : dayStart;
                LocalDateTime to = end.isBefore(dayEnd) ? end : dayEnd;
                if (!to.isAfter(from)) {
                    // дело кончается ровно в полночь: следующему дню оно не принадлежит
                    continue;
                }
                bucket.add(new Entry(task.title(), task.location(), from, to));
            }
        }
        byDay.values().forEach(list -> list.sort((a, b) -> a.from().compareTo(b.from())));
        return byDay;
    }

    private record Entry(String label, String location, LocalDateTime from, LocalDateTime to) {

        int startSecond() {
            return from.toLocalTime().toSecondOfDay();
        }

        /** Конец за полночь считаем концом суток: блок принадлежит колонке своего дня. */
        int endSecond() {
            return to.toLocalDate().equals(from.toLocalDate())
                    ? to.toLocalTime().toSecondOfDay()
                    : 24 * 3600;
        }
    }

    private static List<LocalDate> daysFrom(LocalDate from, int count) {
        List<LocalDate> days = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            days.add(from.plusDays(i));
        }
        return days;
    }


    private static String capitalise(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            // писать в память и не смочь — это уже не сбой ввода-вывода
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}

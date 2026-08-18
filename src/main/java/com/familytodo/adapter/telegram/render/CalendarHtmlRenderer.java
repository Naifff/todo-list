package com.familytodo.adapter.telegram.render;

import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.domain.Assignment;
import com.familytodo.domain.Lesson;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberColor;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Расписание отдельным HTML-файлом — та же сетка, что и на картинке.
 *
 * <p>Форма взята из макетов сетки, а не списка: список уже есть в самом
 * сообщении расписания, и файл, повторяющий его другими словами, не добавлял бы ничего. Сетка
 * показывает то, чего в списке нет вовсе — сколько времени дело занимает, что с чем пересекается и
 * где в дне дыры.
 *
 * <p>Форм две, как и у картинки: до недели — ось часов с колонками дней, дальше — месячная сетка
 * неделями. Ось на тридцать дней была бы нечитаема, а месяц без оси читается.
 *
 * <p>⚠️ <b>Ни строки JavaScript и ни одного внешнего запроса.</b> Внешняя ссылка — пустое место в
 * файле, который скачали, чтобы посмотреть без сети. А заголовок дела это пользовательский текст: в
 * сообщении Telegram неэкранированный стоит HTTP 400, в документе, открытом браузером, он
 * <b>выполняется</b>. Поэтому весь текст проходит через {@link HtmlEscaper}, а в атрибуты попадают
 * только числа, посчитанные нами самими.
 */
public final class CalendarHtmlRenderer {

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** За этой границей ось часов нечитаема, и картинка тоже переходит на месячную сетку. */
    private static final int MAX_DAYS_ON_AXIS = 7;

    /** Рабочие часы по умолчанию: ось покрывает их, даже если дел в них нет. */
    private static final int DEFAULT_FROM_HOUR = 7;

    private static final int DEFAULT_TO_HOUR = 20;

    /** Дело без конца занимает столько же, сколько на картинке: иначе виды разошлись бы. */
    private static final int DEFAULT_SECONDS = 30 * 60;

    /** Совсем короткое дело всё равно должно быть видно и попадать под палец. */
    private static final int MIN_BLOCK_SECONDS = 15 * 60;

    /**
     * Ширина колонки, когда дней больше одного.
     *
     * <p>Уже — и название дела не читается, шире — и на телефоне в экран не влезает даже два дня.
     * Остальное добирается горизонтальным листанием внутри сетки.
     */
    private static final int MULTI_DAY_COLUMN = 128;

    private CalendarHtmlRenderer() {}

    /** Сетка: ось часов до недели, месячная сетка дальше. */
    public static byte[] render(
            List<Task> dated,
            List<Task> undated,
            List<Lesson> lessons,
            Map<Long, Member> byId,
            ZoneId zone,
            LocalDate from,
            int days,
            LocalDate today) {

        StringBuilder html = open(from, days);
        List<LocalDate> columns = columns(from, days);

        if (days > MAX_DAYS_ON_AXIS) {
            appendMonth(html, dated, lessons, byId, zone, from, days, today);
        } else {
            appendTimeGrid(html, dated, lessons, byId, zone, columns);
        }

        return close(html, undated, byId);
    }

    /**
     * Список: день, под ним дела по времени. Форма из списка.
     *
     * <p>Занял место картинки. У сетки день упирается в ось и её границы, у списка границ нет
     * вовсе — а какой вид удобнее, зависит от дня и от человека, и выбирает он.
     */
    public static byte[] renderList(
            List<Task> dated,
            List<Task> undated,
            List<Lesson> lessons,
            Map<Long, Member> byId,
            ZoneId zone,
            LocalDate from,
            int days,
            LocalDate today) {

        StringBuilder html = open(from, days);
        List<LocalDate> columns = columns(from, days);
        Map<LocalDate, List<Entry>> byDay = group(dated, lessons, zone, columns);

        for (LocalDate day : columns) {
            html.append("<section class=\"day\">\n<h2>")
                    .append(dayHeading(day, today))
                    .append("</h2>\n");

            List<Entry> entries = byDay.get(day);
            if (entries == null || entries.isEmpty()) {
                // пустой день показываем явно: иначе непонятно, свободен он или потерялся
                html.append("<p class=\"empty\">свободно</p>\n</section>\n");
                continue;
            }
            for (Entry entry : entries) {
                // ⚠️ Цвет исполнителя здесь не подставлялся никогда: CSS под него написан
                // (border-left: var(--own, ...)), а значение не приезжало, и полоска у всех
                // дел была одного цвета — цвета запасного варианта. Найдено взглядом на
                // страницу, тестом такое не ловится.
                html.append("<div class=\"loose ")
                        .append(cssClass(entry))
                        .append("\" style=\"--own:")
                        .append(colorOf(byId, entry))
                        .append(fillOf(byId, entry))
                        .append("\">\n<div class=\"time\">")
                        .append(entry.when())
                        .append("</div>\n<div class=\"title\">")
                        .append(escape(entry.title()))
                        .append("</div>\n");
                appendDetails(html, entry, byId);
                html.append("</div>\n");
            }
            html.append("</section>\n");
        }

        return close(html, undated, byId);
    }

    private static List<LocalDate> columns(LocalDate from, int days) {
        List<LocalDate> columns = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            columns.add(from.plusDays(i));
        }
        return columns;
    }

    private static StringBuilder open(LocalDate from, int days) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Расписание</title>\n")
                .append(style())
                .append("</head>\n<body>\n")
                .append("<h1>")
                .append(periodTitle(columns(from, days)))
                .append("</h1>\n");
        return html;
    }

    private static byte[] close(StringBuilder html, List<Task> undated, Map<Long, Member> byId) {
        appendUndated(html, undated, byId);
        html.append("</body>\n</html>\n");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Заголовок дня: «сегодня» и «завтра» вместо даты, когда это они.
     *
     * <p>⚠️ {@code today} — настоящее сегодня, а не начало окна. Раньше сюда приходило начало окна:
     * у расписания вперёд это одно и то же, а у истории окно кончается вчера — и первый её день
     * подписывался «сегодня», второй «завтра». Найдено взглядом на скачанный файл, тестом такое не
     * ловится: оба значения были осмысленными датами.
     */
    private static String dayHeading(LocalDate day, LocalDate today) {
        String weekday = day.getDayOfWeek().getDisplayName(TextStyle.FULL, RU);
        String prefix = "";
        if (day.equals(today)) {
            prefix = "сегодня, ";
        } else if (day.equals(today.plusDays(1))) {
            prefix = "завтра, ";
        }
        return escape(prefix + day.format(DATE) + ", " + weekday);
    }

    // --- сетка с осью времени: 1..7 дней ---

    private static void appendTimeGrid(
            StringBuilder html,
            List<Task> tasks,
            List<Lesson> lessons,
            Map<Long, Member> byId,
            ZoneId zone,
            List<LocalDate> columns) {

        Map<LocalDate, List<Entry>> byDay = group(tasks, lessons, zone, columns);

        // ⚠️ границы оси считаются теми же величинами, которыми потом позиционируются блоки.
        // Разные правила для оси и для блока однажды стоили картинке ночного дела целиком
        int fromHour = DEFAULT_FROM_HOUR;
        int toHour = DEFAULT_TO_HOUR;
        for (List<Entry> day : byDay.values()) {
            for (Entry entry : day) {
                fromHour = Math.min(fromHour, entry.startSecond() / 3600);
                toHour = Math.max(toHour, Math.ceilDiv(entry.endSecond(), 3600));
            }
        }
        // ⚠️ под последним делом остаётся час запаса: блок растягивается под свой текст и без
        // запаса упирается в край сетки. Так и срезало «побегать» у дела «к 19:00»
        int latestEnd = fromHour * 3600;
        for (List<Entry> day : byDay.values()) {
            for (Entry entry : day) {
                latestEnd =
                        Math.max(
                                latestEnd,
                                Math.max(entry.endSecond(), entry.startSecond() + DEFAULT_SECONDS));
            }
        }
        toHour = Math.max(toHour, Math.ceilDiv(latestEnd, 3600) + 1);
        toHour = Math.min(24, Math.max(toHour, fromHour + 1));

        int axisFrom = fromHour * 3600;
        int axisTo = toHour * 3600;
        int span = axisTo - axisFrom;

        // ⚠️ ширина колонки зависит от числа дней, а не только от ширины экрана. Один день
        // занимает экран целиком; у недели колонка обязана быть узкой, иначе каждая из семи
        // растянется во весь экран и видна будет ровно первая — неделя как один день
        html.append("<div class=\"scroll\">\n<div class=\"grid\" style=\"--days:")
                .append(columns.size())
                .append(";--hours:")
                .append(toHour - fromHour)
                .append(";--col:")
                .append(columns.size() == 1 ? "100%" : MULTI_DAY_COLUMN + "px")
                .append("\">\n");

        html.append("<div class=\"corner\"></div>\n");
        for (LocalDate day : columns) {
            html.append("<div class=\"head\">").append(dayHeader(day)).append("</div>\n");
        }

        html.append("<div class=\"hours\">\n");
        for (int hour = fromHour; hour < toHour; hour++) {
            html.append("<div class=\"hour\">")
                    .append("%02d".formatted(hour % 24))
                    .append("</div>\n");
        }
        // ⚠️ закрывающий час подписывается отдельно. Подписи метят начало часа, поэтому без
        // него ось по умолчанию кончалась на «19», строка до 20:00 при этом была, а день
        // читался как обрезанный на семи вечера
        html.append("<div class=\"hour end\">")
                .append("%02d".formatted(toHour % 24))
                .append("</div>\n</div>\n");

        for (LocalDate day : columns) {
            html.append("<div class=\"col\">\n");
            for (int hour = fromHour; hour < toHour; hour++) {
                html.append("<div class=\"slot\"></div>\n");
            }
            appendBlocks(html, byDay.get(day), byId, axisFrom, axisTo, span);
            html.append("</div>\n");
        }

        html.append("</div>\n</div>\n");
    }

    private static void appendBlocks(
            StringBuilder html,
            List<Entry> entries,
            Map<Long, Member> byId,
            int axisFrom,
            int axisTo,
            int span) {

        if (entries == null || entries.isEmpty()) {
            return;
        }

        List<Lanes.Span<Entry>> spans = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            // клип по оси: дело через полночь иначе даёт отрицательную высоту
            int start = Math.max(axisFrom, entry.startSecond());
            int end =
                    Math.min(
                            axisTo,
                            Math.max(entry.endSecond(), entry.startSecond() + DEFAULT_SECONDS));
            if (end > axisFrom && start < axisTo) {
                spans.add(new Lanes.Span<>(entry, start, Math.max(end, start + MIN_BLOCK_SECONDS)));
            }
        }

        for (Lanes.Placed<Entry> placed : Lanes.pack(spans)) {
            double top = 100.0 * (placed.fromSecond() - axisFrom) / span;
            double height = 100.0 * (placed.toSecond() - placed.fromSecond()) / span;
            double width = 100.0 / placed.lanes();
            double left = width * placed.lane();

            Entry entry = placed.value();
            html.append("<div class=\"block ")
                    .append(cssClass(entry))
                    // в атрибут идут только числа, посчитанные нами самими
                    .append("\" style=\"")
                    // ⚠️ Locale.ROOT обязателен: у формата по умолчанию берётся локаль машины, и
                    // в русской в CSS уехало бы «top:8,000%» — правило, которое браузер отбросит
                    .append(
                            String.format(
                                    Locale.ROOT,
                                    "top:%.3f%%;height:%.3f%%;left:%.3f%%;width:%.3f%%;--own:%s%s",
                                    top,
                                    height,
                                    left,
                                    width,
                                    colorOf(byId, entry),
                                    fillOf(byId, entry)))
                    .append("\">\n")
                    // время и название одной строкой: у получасового дела на телефоне высоты
                    // хватает строки на две, и каждая лишняя — это шанс не поместиться
                    .append("<div class=\"title\"><span class=\"time\">")
                    .append(entry.when())
                    .append("</span> ")
                    .append(escape(entry.title()))
                    .append("</div>\n");
            appendCompactDetails(html, entry, byId);
            html.append("</div>\n");
        }
    }

    // --- месячная сетка: больше недели ---

    private static void appendMonth(
            StringBuilder html,
            List<Task> tasks,
            List<Lesson> lessons,
            Map<Long, Member> byId,
            ZoneId zone,
            LocalDate from,
            int days,
            LocalDate today) {

        // сетка начинается с понедельника: неделя, разрезанная посередине, не читается
        LocalDate start = from.with(DayOfWeek.MONDAY);
        int span = (int) (from.plusDays(days).toEpochDay() - start.toEpochDay());
        int weeks = Math.max(1, Math.min(6, Math.ceilDiv(span, 7)));

        List<LocalDate> cells = new ArrayList<>(weeks * 7);
        for (int i = 0; i < weeks * 7; i++) {
            cells.add(start.plusDays(i));
        }
        Map<LocalDate, List<Entry>> byDay = group(tasks, lessons, zone, cells);

        html.append("<div class=\"scroll\">\n<div class=\"month\">\n");
        for (DayOfWeek weekday : DayOfWeek.values()) {
            html.append("<div class=\"head\">")
                    .append(escape(shortWeekday(weekday)))
                    .append("</div>\n");
        }

        for (LocalDate day : cells) {
            boolean outside = day.isBefore(from) || !day.isBefore(from.plusDays(days));
            // сегодняшний день метим: в череде одинаковых квадратиков он иначе теряется.
            // У истории окно кончается вчера, поэтому там не метится ничего
            html.append("<div class=\"cell")
                    .append(outside ? " outside" : "")
                    .append(day.equals(today) ? " today" : "")
                    .append("\">\n");
            html.append("<div class=\"date\">").append(day.getDayOfMonth()).append("</div>\n");
            for (Entry entry : byDay.getOrDefault(day, List.of())) {
                html.append("<div class=\"chip ")
                        .append(cssClass(entry))
                        .append("\" style=\"--own:")
                        .append(colorOf(byId, entry))
                        .append(fillOf(byId, entry))
                        .append("\"><span class=\"at\">")
                        .append(entry.startLabel())
                        .append("</span> ")
                        .append(entry.isLesson() ? "" : statusMark(entry.task().status()))
                        .append(escape(entry.title()))
                        .append("</div>\n");
            }
            html.append("</div>\n");
        }
        html.append("</div>\n</div>\n");
    }

    // --- общее ---

    /**
     * В блоке сетки место, исполнитель и статус идут <b>одной строкой</b>: у получасового дела на
     * высоту приходится строки три, и каждая отдельная строка — это ещё один шанс не поместиться.
     */
    private static void appendCompactDetails(
            StringBuilder html, Entry entry, Map<Long, Member> byId) {
        // у урока подробностей нет: место — школа, статуса не бывает. Остаётся чей он, и это
        // главное в семейном календаре: по колонке видно, кто в это время занят
        if (entry.isLesson()) {
            html.append("<div class=\"who\">")
                    .append(name(byId, entry.lesson().memberId()))
                    .append("</div>\n");
            return;
        }
        Task task = entry.task();
        StringBuilder meta = new StringBuilder();
        if (task.location() != null && !task.location().isBlank()) {
            meta.append(escape(task.location())).append(" · ");
        }
        meta.append(names(byId, task));
        String status = statusLabel(task.status());
        if (status != null) {
            meta.append(" · ").append(status);
        }
        html.append("<div class=\"who\">").append(meta).append("</div>\n");

        appendRefusals(html, task, byId);
    }

    private static void appendDetails(StringBuilder html, Entry entry, Map<Long, Member> byId) {
        if (entry.isLesson()) {
            html.append("<div class=\"who\">")
                    .append(name(byId, entry.lesson().memberId()))
                    .append("</div>\n");
            return;
        }
        Task task = entry.task();
        if (task.location() != null && !task.location().isBlank()) {
            html.append("<div class=\"where\">").append(escape(task.location())).append("</div>\n");
        }
        html.append("<div class=\"who\">").append(names(byId, task));
        String status = statusLabel(task.status());
        if (status != null) {
            html.append(" · ").append(status);
        }
        html.append("</div>\n");
        appendRefusals(html, task, byId);
    }

    /** Имена всех исполнителей: по цвету видно, чьё дело, но при двоих цвет уже не отвечает. */
    private static String names(Map<Long, Member> byId, Task task) {
        return task.assignments().stream()
                .map(assignment -> name(byId, assignment.memberId()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Отказы — по людям, с именем.
     *
     * <p>Одна строка «причина отказа» на дело отвечала бы неизвестно про кого: отказаться могли оба,
     * и причины у них разные.
     */
    private static void appendRefusals(StringBuilder html, Task task, Map<Long, Member> byId) {
        for (Assignment assignment : task.assignments()) {
            if (assignment.hasDeclined() && !isBlank(assignment.declineReason())) {
                html.append("<div class=\"reason\">")
                        .append(name(byId, assignment.memberId()))
                        .append(" — ")
                        .append(escape(assignment.declineReason()))
                        .append("</div>\n");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Дела без даты — то, чего на картинке нет и быть не может: на оси времени им негде стоять. На
     * странице для них есть место, и терять их молча нельзя.
     */
    private static void appendUndated(
            StringBuilder html, List<Task> undated, Map<Long, Member> byId) {
        if (undated == null || undated.isEmpty()) {
            return;
        }

        html.append("<section class=\"undated\">\n<h2>Без даты</h2>\n");
        for (Task task : undated) {
            html.append("<div class=\"loose ")
                    .append(statusClass(task.status()))
                    .append("\" style=\"--own:")
                    .append(colorOf(byId, task))
                    .append(fillOf(byId, task))
                    .append("\">\n")
                    .append("<div class=\"title\">")
                    .append(escape(task.title()))
                    .append("</div>\n");
            // дело без даты на оси стоять не может, поэтому и куска суток у него нет:
            // заворачиваем в Entry только ради общих подробностей
            appendCompactDetails(html, new Entry(task, null, null, null, true), byId);
            html.append("</div>\n");
        }
        html.append("</section>\n");
    }

    /**
     * Раскладка по дням повторяет картинку: дело через полночь попадает в каждый день, который
     * занимает, обрезанное по границам суток. Иначе сон обрывался бы на полуночи и наутро его не
     * было.
     */
    private static Map<LocalDate, List<Entry>> group(
            List<Task> tasks, List<Lesson> lessons, ZoneId zone, List<LocalDate> columns) {

        Map<LocalDate, List<Entry>> byDay = new LinkedHashMap<>();
        for (LocalDate day : columns) {
            byDay.put(day, new ArrayList<>());
        }

        // ⚠️ Уроки раскладываются здесь и только здесь: строк на конкретный день у них нет и не
        // будет — расписание это правило, и день получается наложением. Дальше по коду урок и дело
        // неразличимы, поэтому и сетка, и месяц, и список получают их одинаково.
        for (Lesson lesson : lessons) {
            for (LocalDate day : columns) {
                if (!lesson.occursOn(day)) {
                    continue;
                }
                byDay.get(day)
                        .add(
                                new Entry(
                                        null,
                                        lesson,
                                        LocalDateTime.of(day, lesson.startsAt()),
                                        LocalDateTime.of(day, lesson.endsAt()),
                                        false));
            }
        }

        for (Task task : tasks) {
            Instant startsAt = task.startsAt() != null ? task.startsAt() : task.dueAt();
            if (startsAt == null) {
                continue;
            }
            boolean deadlineOnly = task.startsAt() == null;
            Instant endsAt =
                    task.endsAt() != null ? task.endsAt() : startsAt.plusSeconds(DEFAULT_SECONDS);

            LocalDateTime start = LocalDateTime.ofInstant(startsAt, zone);
            LocalDateTime end = LocalDateTime.ofInstant(endsAt, zone);

            for (LocalDate day = start.toLocalDate();
                    !day.isAfter(end.toLocalDate());
                    day = day.plusDays(1)) {
                List<Entry> bucket = byDay.get(day);
                if (bucket == null) {
                    continue;
                }
                LocalDateTime dayStart = day.atStartOfDay();
                LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
                LocalDateTime pieceFrom = start.isAfter(dayStart) ? start : dayStart;
                LocalDateTime pieceTo = end.isBefore(dayEnd) ? end : dayEnd;
                if (!pieceTo.isAfter(pieceFrom)) {
                    // дело кончается ровно в полночь: следующему дню оно не принадлежит
                    continue;
                }
                bucket.add(new Entry(task, null, pieceFrom, pieceTo, deadlineOnly));
            }
        }

        // ⚠️ порядок по МОМЕНТУ, а не по подписи: «к 19:00» начинается с буквы, и сортировка
        // строк уводила бы срок в конец дня, за «22:40». Тот же класс ошибки, что и хранение
        // моментов времени текстом
        byDay.values().forEach(list -> list.sort(Comparator.comparing(Entry::from)));
        return byDay;
    }

    private static String dayHeader(LocalDate day) {
        return escape(day.getDayOfMonth() + " " + shortWeekday(day.getDayOfWeek()));
    }

    /** Java отдаёт «пн», в макетах «Пн» — разница видна в шапке, где это единственное слово. */
    private static String shortWeekday(DayOfWeek weekday) {
        String name = weekday.getDisplayName(TextStyle.SHORT, RU);
        return name.substring(0, 1).toUpperCase(RU) + name.substring(1);
    }

    private static String periodTitle(List<LocalDate> columns) {
        LocalDate first = columns.getFirst();
        LocalDate last = columns.getLast();
        if (first.equals(last)) {
            return escape(
                    first.getDayOfWeek().getDisplayName(TextStyle.FULL, RU)
                            + ", "
                            + first.format(DATE));
        }
        return escape(first.format(DATE) + " — " + last.format(DATE));
    }

    /**
     * Цвет исполнителя. В сетке на неделю колонка узкая, имя в блоке читается плохо — по цвету
     * видно с одного взгляда, чьё это дело.
     *
     * <p>Исполнителя могли исключить из семьи: тогда берём цвет по умолчанию, а не падаем.
     */
    /** Урок помечается своим классом: статуса у него нет, а отличать от дела глазами нужно. */
    private static String cssClass(Entry entry) {
        return entry.isLesson() ? "lesson" : statusClass(entry.task().status());
    }

    private static String colorOf(Map<Long, Member> byId, Entry entry) {
        return entry.isLesson()
                ? colorOf(byId, entry.lesson().memberId())
                : colorOf(byId, entry.task());
    }

    /** У урока хозяин один — делить заливку не между кем. */
    private static String fillOf(Map<Long, Member> byId, Entry entry) {
        return entry.isLesson() ? "" : fillOf(byId, entry.task());
    }

    private static String colorOf(Map<Long, Member> byId, Task task) {
        return colorOf(byId, task.assignments().getFirst().memberId());
    }

    private static String colorOf(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return (member == null ? MemberColor.forMember(memberId) : member.color()).hex();
    }

    /**
     * Заливка блока, поделённая поровну между исполнителями.
     *
     * <p>Делится <b>слева направо</b>: цвета стоят вертикальными полосами во всю высоту блока.
     *
     * <p>⚠️ Так уже было в первой версии, и 11 августа это отменили: раздел читался с телефона как
     * два соседних блока, а соседние блоки в сетке означают ровно одно — пересекающиеся дела.
     * Возвращено 18 августа по просьбе после приёмки: с пастельной палитрой прежняя путаница не
     * повторилась, а вертикальные полосы показывают «дело на двоих» понятнее. ⚠️ Если она вернётся,
     * лечится не сменой направления обратно, а разделителем между полосами: пересекающиеся дела в
     * сетке разделены зазором, а не границей цвета.
     *
     * <p>До горизонтальных полос была тонкая лента поверху; её просили заменить на равномерное
     * деление.
     *
     * <p>⚠️ Отдельной переменной, а не через {@code --own}. Тот же {@code --own} используется в
     * {@code border-left: 3px solid}, а градиент там невалиден: правило целиком отбрасывается, и
     * полоска у дела без даты просто исчезает. Такие ошибки браузер не сообщает никак.
     *
     * <p>Границы полос считаются целыми процентами: у форматирования дробей берётся локаль машины,
     * и в русской «50.0%» превратилось бы в «50,0%» — правило, которое браузер молча отбросит.
     *
     * @return готовый кусок стиля или пустая строка, если исполнитель один
     */
    private static String fillOf(Map<Long, Member> byId, Task task) {
        int count = task.assignments().size();
        if (count < 2) {
            return "";
        }

        StringBuilder gradient = new StringBuilder(";--ribbon:linear-gradient(to right");
        for (int i = 0; i < count; i++) {
            gradient.append(", ")
                    .append(colorOf(byId, task.assignments().get(i).memberId()))
                    .append(' ')
                    .append(Math.round(i * 100.0f / count))
                    .append("% ")
                    .append(Math.round((i + 1) * 100.0f / count))
                    .append('%');
        }
        return gradient.append(')').toString();
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        // исполнителя могли исключить из семьи: в составе его уже нет, а дело осталось
        return escape(member == null ? "кто-то" : member.displayName());
    }

    private static String statusClass(TaskStatus status) {
        return switch (status) {
            case OPEN -> "open";
            case DONE -> "done";
            case DECLINED -> "declined";
        };
    }

    /**
     * Пометка статуса в плашке месячной сетки.
     *
     * <p>⚠️ Нужна только истории и появилась из-за неё. У закрытых дел плашка серая независимо от
     * статуса, а в истории закрыты <b>все</b> — месяц выходил ровным серым полем, где «сделано» и
     * «отказ» неотличимы. Ровно то, ради чего историю и открывают.
     *
     * <p>Знаком, а не словом: в плашке месяца ширины хватает на время и название, и «сделано»
     * съело бы название целиком. В сетке с осью часов статус подписан словом — там место есть.
     */
    private static String statusMark(TaskStatus status) {
        return switch (status) {
            case OPEN -> "";
            case DONE -> "✓ ";
            case DECLINED -> "✕ ";
        };
    }

    private static String statusLabel(TaskStatus status) {
        return switch (status) {
            case OPEN -> null;
            case DONE -> "сделано";
            case DECLINED -> "отказ";
        };
    }

    private static String escape(String text) {
        return HtmlEscaper.escape(text);
    }

    /**
     * Стиль встроен целиком: внешний файл на телефоне без сети не загрузится, а расписание для того
     * и скачивают, чтобы посмотреть в дороге.
     *
     * <p>Широкая сетка листается внутри своего контейнера. Страница целиком горизонтально не едет:
     * иначе заголовок и блок «без даты» уезжали бы вместе с ней.
     */
    private static String style() {
        return """
               <style>
               :root {
                 --bg: #ffffff;
                 --fg: #1a1a1a;
                 --muted: #6b6b6b;
                 --line: #e0e0e0;
                 --slot: #fafafa;
                 --accent: #f26b21;
                 --on-accent: #ffffff;
                 /* ⚠️ Текст на плашке тёмный, а не белый: заливка пастельная и в тёмной теме
                    остаётся светлой, поэтому цвет чернил один на обе схемы. Белым по
                    светло-голубому не читается ничего. */
                 --on-plate: #22252a;
                 /* Закрытая плашка — бледно-серая, чтобы теми же чернилами и читалась, и
                    отличалась от живого дела с одного взгляда */
                 --plate-done: #e3e5e9;
                 /* Плашка без цвета участника: серо-голубая, а не оранжевый акцент —
                    акцент кричит громче любого цвета из палитры */
                 --plate-none: #dfe4ec;
                 --col: 150px;
                 --hour: 46px;
               }
               @media (prefers-color-scheme: dark) {
                 :root {
                   --bg: #16181c;
                   --fg: #e9e9e9;
                   --muted: #6f7480;
                   --line: #2c2f36;
                   --slot: #1b1e24;
                 }
               }
               /* на узком экране час выше — иначе в короткий блок не помещается ни строки */
               @media (max-width: 480px) {
                 :root { --hour: 62px; }
               }
               * { box-sizing: border-box; }
               body {
                 margin: 0;
                 padding: 14px;
                 background: var(--bg);
                 color: var(--fg);
                 font: 15px/1.35 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
               }
               h1 { font-size: 19px; margin: 0 0 12px; }
               h2 { font-size: 15px; margin: 0 0 8px; }
               .scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
               .grid {
                 display: grid;
                 grid-template-columns: 38px repeat(var(--days), minmax(var(--col), 1fr));
                 grid-template-rows: auto calc(var(--hours) * var(--hour));
                 min-width: min-content;
                 border: 1px solid var(--line);
                 border-radius: 8px;
                 overflow: hidden;
               }
               .corner { border-bottom: 1px solid var(--line); }
               .head {
                 padding: 6px 8px;
                 font-size: 12px;
                 font-weight: 600;
                 text-align: center;
                 border-left: 1px solid var(--line);
                 border-bottom: 1px solid var(--line);
                 white-space: nowrap;
               }
               .hours { display: flex; flex-direction: column; }
               .hour {
                 height: var(--hour);
                 font-size: 11px;
                 color: var(--muted);
                 text-align: right;
                 padding: 0 5px;
                 font-variant-numeric: tabular-nums;
               }
               .hours { position: relative; }
               .hour.end {
                 position: absolute;
                 bottom: 0;
                 right: 5px;
                 height: auto;
               }
               .col { position: relative; border-left: 1px solid var(--line); }
               .slot {
                 height: var(--hour);
                 border-bottom: 1px solid var(--line);
                 background: var(--slot);
               }
               .block {
                 position: absolute;
                 /* ⚠️ блок растягивается под своё содержимое: получасовое дело на телефоне это
                    около двадцати пикселей, а внутри время, название, место и исполнитель */
                 min-height: min-content;
                 overflow: hidden;
                 padding: 3px 5px;
                 border-radius: 4px;
                 /* --ribbon появляется, только когда исполнителей несколько: блок делится
                    поровну на их цвета, полосами сверху вниз. Не слева направо — вертикальный
                    раздел читался как два соседних блока, а это в сетке означает пересекающиеся
                    дела. Держать заливку отдельно от --own обязательно: тот же --own уходит в
                    border-left, где градиент невалиден и правило отбрасывается молча */
                 background: var(--ribbon, var(--own, var(--plate-none)));
                 color: var(--on-plate);
                 font-size: 11px;
                 line-height: 1.25;
               }
               .block.done, .block.declined { background: var(--plate-done); }
               .block.done .title { text-decoration: line-through; }
               .time { font-variant-numeric: tabular-nums; opacity: .85; }
               .title { font-weight: 600; overflow-wrap: anywhere; }
               .where, .who, .reason { opacity: .85; overflow-wrap: anywhere; }
               .month {
                 display: grid;
                 grid-template-columns: repeat(7, minmax(92px, 1fr));
                 min-width: min-content;
                 border: 1px solid var(--line);
                 border-radius: 8px;
                 overflow: hidden;
               }
               .cell {
                 min-height: 92px;
                 padding: 4px;
                 border-left: 1px solid var(--line);
                 border-top: 1px solid var(--line);
               }
               .cell.outside { opacity: .45; }
               /* inset, а не border: настоящая рамка сдвинула бы содержимое ячейки на пиксель
                  и разъехалась бы с соседями по строке */
               .cell.today { box-shadow: inset 0 0 0 2px var(--accent); }
               .cell.today .date { color: var(--accent); font-weight: 700; }
               .date { font-size: 11px; color: var(--muted); margin-bottom: 3px; }
               .chip {
                 background: var(--ribbon, var(--own, var(--plate-none)));
                 color: var(--on-plate);
                 border-radius: 4px;
                 padding: 2px 4px;
                 margin-bottom: 2px;
                 font-size: 11px;
                 overflow-wrap: anywhere;
               }
               .chip.done, .chip.declined { background: var(--plate-done); }
               .at { font-variant-numeric: tabular-nums; opacity: .85; }
               .day { margin-bottom: 18px; }
               h2 {
                 padding-bottom: 5px;
                 border-bottom: 1px solid var(--line);
               }
               .empty { margin: 0; color: var(--muted); font-size: 13px; }
               .loose .time { color: var(--muted); font-size: 12px; }
               .undated { margin-top: 18px; }
               .loose {
                 border-left: 3px solid var(--own, var(--accent));
                 padding: 8px 10px;
                 margin-bottom: 8px;
                 background: var(--slot);
                 border-radius: 6px;
                 font-size: 13px;
               }
               .loose.done, .loose.declined { border-left-color: var(--muted); opacity: .7; }
               /* Урок отличается от дела заливкой, а не цветом: цвет занят — он отвечает, чей это
                  урок. Полупрозрачная заливка того же цвета плюс пунктирная рамка читаются как
                  «здесь занято, но делать нечего»; сплошная не давала бы отличить школу от
                  просьбы, а серая потеряла бы ребёнка. */
               /* ⚠️ Доля цвета выросла с 26% до 45%, а рамка стала темнее самого цвета: на
                  насыщенной заливке четверти хватало, на пастельной урок пропадал со страницы. */
               .block.lesson, .chip.lesson {
                 background: color-mix(in srgb, var(--own, var(--plate-none)) 45%, transparent);
                 color: var(--fg);
                 border: 1px dashed color-mix(in srgb, var(--own, var(--plate-none)) 55%, var(--fg));
               }
               .loose.lesson {
                 border-left-style: dashed;
                 background: color-mix(in srgb, var(--own, var(--plate-none)) 22%, var(--slot));
               }
               </style>
               """;
    }

    /**
     * Кусок дела — или урок — в пределах одних суток.
     *
     * <p>⚠️ Ровно одно из двух полей заполнено. Урок <b>не превращается в {@link Task}</b>: у него
     * нет ни исполнителя, ни статуса, и поддельное дело неминуемо утекло бы туда, где делам место, —
     * в списки. Здесь же общая только геометрия: что во сколько занимает время.
     */
    private record Entry(
            Task task, Lesson lesson, LocalDateTime from, LocalDateTime to, boolean deadlineOnly) {

        boolean isLesson() {
            return lesson != null;
        }

        String title() {
            return isLesson() ? lesson.subject() : task.title();
        }

        int startSecond() {
            return from.toLocalTime().toSecondOfDay();
        }

        /** Конец за полночь считаем концом суток: блок принадлежит колонке своего дня. */
        int endSecond() {
            return to.toLocalDate().equals(from.toLocalDate())
                    ? to.toLocalTime().toSecondOfDay()
                    : 24 * 3600;
        }

        String startLabel() {
            return from.format(TIME);
        }

        /**
         * У дела с интервалом показываем интервал, у остального — срок. Смешивать нельзя:
         * «08:00–08:40» и «к 19:00» это разные обещания.
         */
        String when() {
            return deadlineOnly
                    ? "к " + from.format(TIME)
                    : from.format(TIME) + "–" + to.format(TIME);
        }
    }
}

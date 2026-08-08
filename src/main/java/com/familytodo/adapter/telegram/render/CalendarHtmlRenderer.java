package com.familytodo.adapter.telegram.render;

import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Расписание отдельным HTML-файлом — второй вид рядом с картинкой.
 *
 * <p>Он подстраивается под ширину экрана, листается и вмещает то, чего в картинку не влезает:
 * место, исполнителя, статус, причину отказа. Картинка обзорнее, файл подробнее — поэтому оба и
 * живут рядом.
 *
 * <p>⚠️ <b>Ни строки JavaScript и ни одного внешнего запроса.</b> Оба ограничения не косметические.
 * Внешняя ссылка на телефоне без сети — пустое место в файле, который для того и скачали. А
 * заголовок дела это пользовательский текст: в сообщении Telegram неэкранированный даёт HTTP 400,
 * неприятно, но безвредно, — в документе, который человек открывает браузером, он выполняется.
 * Поэтому весь текст проходит через {@link HtmlEscaper}, в атрибуты не попадает вовсе, а скриптов
 * нет как класса.
 */
public final class CalendarHtmlRenderer {

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** Дело без конца занимает столько же, сколько на картинке: иначе виды разошлись бы. */
    private static final int DEFAULT_MINUTES = 30;

    private CalendarHtmlRenderer() {}

    public static byte[] render(
            List<Task> dated,
            List<Task> undated,
            Map<Long, Member> byId,
            ZoneId zone,
            LocalDate from,
            int days) {

        List<LocalDate> columns = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            columns.add(from.plusDays(i));
        }

        Map<LocalDate, List<Entry>> byDay = group(dated, zone, columns);

        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Расписание</title>\n")
                .append(style())
                .append("</head>\n<body>\n");

        html.append("<h1>Расписание</h1>\n");
        html.append("<p class=\"range\">").append(rangeLabel(columns)).append("</p>\n");

        for (LocalDate day : columns) {
            appendDay(html, day, byDay.get(day), from, byId);
        }

        appendUndated(html, undated, byId);

        html.append("</body>\n</html>\n");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendDay(
            StringBuilder html,
            LocalDate day,
            List<Entry> entries,
            LocalDate today,
            Map<Long, Member> byId) {

        html.append("<section class=\"day\">\n<h2>").append(dayLabel(day, today)).append("</h2>\n");
        if (entries == null || entries.isEmpty()) {
            // пустой день показываем явно: иначе непонятно, свободен он или потерялся
            html.append("<p class=\"empty\">свободно</p>\n</section>\n");
            return;
        }

        html.append("<ul class=\"items\">\n");
        for (Entry entry : entries) {
            appendEntry(html, entry, byId);
        }
        html.append("</ul>\n</section>\n");
    }

    private static void appendEntry(StringBuilder html, Entry entry, Map<Long, Member> byId) {
        Task task = entry.task();
        html.append("<li class=\"item ").append(statusClass(task.status())).append("\">\n");
        html.append("<div class=\"when\">").append(entry.when()).append("</div>\n");
        html.append("<div class=\"what\">").append(escape(task.title())).append("</div>\n");

        html.append("<div class=\"meta\">");
        if (task.location() != null && !task.location().isBlank()) {
            html.append("<span class=\"where\">").append(escape(task.location())).append("</span>");
        }
        html.append("<span class=\"who\">").append(name(byId, task.assignee().memberId())).append("</span>");
        String status = statusLabel(task.status());
        if (status != null) {
            html.append("<span class=\"status\">").append(status).append("</span>");
        }
        html.append("</div>\n");

        if (task.declineReason() != null && !task.declineReason().isBlank()) {
            html.append("<div class=\"reason\">")
                    .append(escape(task.declineReason()))
                    .append("</div>\n");
        }
        html.append("</li>\n");
    }

    private static void appendUndated(
            StringBuilder html, List<Task> undated, Map<Long, Member> byId) {
        if (undated == null || undated.isEmpty()) {
            return;
        }

        html.append("<section class=\"day undated\">\n<h2>Без даты</h2>\n<ul class=\"items\">\n");
        for (Task task : undated) {
            html.append("<li class=\"item ").append(statusClass(task.status())).append("\">\n");
            html.append("<div class=\"what\">").append(escape(task.title())).append("</div>\n");
            html.append("<div class=\"meta\">");
            if (task.location() != null && !task.location().isBlank()) {
                html.append("<span class=\"where\">")
                        .append(escape(task.location()))
                        .append("</span>");
            }
            html.append("<span class=\"who\">")
                    .append(name(byId, task.assignee().memberId()))
                    .append("</span></div>\n</li>\n");
        }
        html.append("</ul>\n</section>\n");
    }

    /**
     * Раскладка по дням повторяет картинку намеренно: дело через полночь попадает в каждый день,
     * который занимает, обрезанное по границам суток. Иначе сон обрывался бы на полуночи и наутро
     * его не было.
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
            boolean deadlineOnly = task.startsAt() == null;
            Instant endsAt =
                    task.endsAt() != null
                            ? task.endsAt()
                            : startsAt.plusSeconds(DEFAULT_MINUTES * 60L);

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
                bucket.add(new Entry(task, pieceFrom, when(pieceFrom, pieceTo, deadlineOnly)));
            }
        }

        // ⚠️ порядок по МОМЕНТУ, а не по подписи: «к 19:00» начинается с буквы, и сортировка
        // строк уводила бы срок в конец дня, за «22:40». Тот же класс ошибки, что и хранение
        // моментов времени текстом
        byDay.values().forEach(list -> list.sort(java.util.Comparator.comparing(Entry::startsAt)));
        return byDay;
    }

    /**
     * У дела с интервалом показываем интервал, у остального — срок. Смешивать нельзя: «08:00–08:40»
     * и «к 19:00» это разные обещания.
     */
    private static String when(LocalDateTime from, LocalDateTime to, boolean deadlineOnly) {
        if (deadlineOnly) {
            return "к " + from.format(TIME);
        }
        return from.format(TIME) + "–" + to.format(TIME);
    }

    private static String dayLabel(LocalDate day, LocalDate today) {
        String weekday = day.getDayOfWeek().getDisplayName(TextStyle.FULL, RU);
        String prefix = "";
        if (day.equals(today)) {
            prefix = "сегодня, ";
        } else if (day.equals(today.plusDays(1))) {
            prefix = "завтра, ";
        }
        return escape(prefix + day.format(DATE) + ", " + weekday);
    }

    private static String rangeLabel(List<LocalDate> columns) {
        LocalDate first = columns.getFirst();
        LocalDate last = columns.getLast();
        return escape(
                first.equals(last)
                        ? first.format(DATE)
                        : first.format(DATE) + " — " + last.format(DATE));
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
     */
    private static String style() {
        return """
               <style>
               :root {
                 --bg: #ffffff;
                 --fg: #1a1a1a;
                 --muted: #6b6b6b;
                 --line: #e4e4e4;
                 --card: #f7f7f7;
                 --accent: #f26b21;
               }
               @media (prefers-color-scheme: dark) {
                 :root {
                   --bg: #16181c;
                   --fg: #e9e9e9;
                   --muted: #9a9a9a;
                   --line: #2c2f36;
                   --card: #1e2127;
                 }
               }
               * { box-sizing: border-box; }
               body {
                 margin: 0;
                 padding: 16px;
                 background: var(--bg);
                 color: var(--fg);
                 font: 16px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                 max-width: 720px;
                 margin-inline: auto;
               }
               h1 { font-size: 20px; margin: 0 0 2px; }
               .range { margin: 0 0 20px; color: var(--muted); font-size: 14px; }
               .day { margin-bottom: 22px; }
               h2 {
                 font-size: 15px;
                 margin: 0 0 8px;
                 padding-bottom: 6px;
                 border-bottom: 1px solid var(--line);
                 font-weight: 600;
               }
               .empty { margin: 0; color: var(--muted); font-size: 14px; }
               .items { list-style: none; margin: 0; padding: 0; }
               .item {
                 background: var(--card);
                 border-left: 3px solid var(--accent);
                 border-radius: 6px;
                 padding: 10px 12px;
                 margin-bottom: 8px;
               }
               .item.done { border-left-color: var(--muted); opacity: .65; }
               .item.declined { border-left-color: var(--muted); opacity: .65; }
               .item.done .what { text-decoration: line-through; }
               .when { font-variant-numeric: tabular-nums; font-size: 13px; color: var(--muted); }
               .what { font-weight: 600; overflow-wrap: anywhere; }
               .meta { font-size: 13px; color: var(--muted); margin-top: 2px; }
               .meta span + span::before { content: " · "; }
               .reason { font-size: 13px; margin-top: 4px; overflow-wrap: anywhere; }
               </style>
               """;
    }

    /**
     * Кусок дела в пределах одних суток.
     *
     * <p>{@code startsAt} хранится отдельно от подписи именно потому, что сортировать по подписи
     * нельзя: она человеческая, а не машинная.
     */
    private record Entry(Task task, LocalDateTime startsAt, String when) {}
}

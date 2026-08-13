package com.familytodo.adapter.telegram.view;

import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Утренний дайджест — с днями заголовками.
 *
 * <p>Своя вёрстка, а не {@link TaskListView}, и причин две. Кнопок под дайджестом нет, поэтому
 * нумерация строк в нём — шум: номер не на что нажать. А главное, у дайджеста другая ось: список дел
 * отвечает «что мне поручено», дайджест — «что когда». Плоский список повторял дату на каждой
 * строке, и пять дел на 13.08 читались как пять разных дней.
 *
 * <p>Дайджест <b>персональный</b>: в него попадает только то, что поручено получателю. Поэтому
 * исполнители в строках не печатаются вовсе — это всегда он сам, — а автор называется, только если
 * это кто-то другой.
 */
public final class DigestView {

    private static final Locale RU = Locale.of("ru");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** Тот же запас под хвост сообщения, что и у списка дел: Telegram режет на 4096 символах. */
    private static final int FOOTER_RESERVE = 40;

    private DigestView() {}

    public static String render(
            String header,
            List<Task> tasks,
            Member recipient,
            Map<Long, Member> byId,
            ZoneId zone,
            Instant now) {

        StringBuilder out = new StringBuilder("<b>").append(header).append("</b>\n");
        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;
        LocalDate today = LocalDate.ofInstant(now, zone);

        int shown = 0;
        for (Map.Entry<LocalDate, List<Task>> day : byDay(tasks, zone).entrySet()) {
            StringBuilder group = new StringBuilder("\n<b>").append(heading(day.getKey(), today));
            group.append("</b>\n");

            for (Task task : day.getValue()) {
                group.append(line(task, recipient, byId, zone, now)).append('\n');
            }
            if (out.length() + group.length() > budget) {
                break;
            }
            out.append(group);
            shown += day.getValue().size();
        }

        if (shown < tasks.size()) {
            out.append("\n…и ещё ").append(tasks.size() - shown);
        }
        return out.toString().stripTrailing();
    }

    /**
     * Группировка сохраняет порядок, в котором дела пришли из выборки: он уже по моменту времени.
     * Дела без срока идут последними — {@code null} как ключ, отсюда {@link LinkedHashMap}, а не
     * сортировка ключей.
     */
    private static Map<LocalDate, List<Task>> byDay(List<Task> tasks, ZoneId zone) {
        Map<LocalDate, List<Task>> byDay = new LinkedHashMap<>();
        for (Task task : tasks) {
            byDay.computeIfAbsent(day(task, zone), key -> new java.util.ArrayList<>()).add(task);
        }
        return byDay;
    }

    private static LocalDate day(Task task, ZoneId zone) {
        Instant moment = task.isScheduled() ? task.startsAt() : task.dueAt();
        return moment == null ? null : LocalDate.ofInstant(moment, zone);
    }

    /**
     * «Сегодня» и «завтра» словами, дальше — днём недели: «15.08» не отвечает на вопрос «это суббота
     * или понедельник», а именно он и решает, влезет ли дело в день.
     */
    private static String heading(LocalDate day, LocalDate today) {
        if (day == null) {
            return "Без срока";
        }
        if (day.equals(today)) {
            return "Сегодня, " + day.format(DATE);
        }
        if (day.equals(today.plusDays(1))) {
            return "Завтра, " + day.format(DATE);
        }
        return weekday(day) + ", " + day.format(DATE);
    }

    private static String weekday(LocalDate day) {
        String name = day.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, RU);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String line(
            Task task, Member recipient, Map<Long, Member> byId, ZoneId zone, Instant now) {

        StringBuilder line = new StringBuilder("• ");
        if (task.dueAt() != null && task.dueAt().isBefore(now)) {
            line.append("❗️");
        }
        line.append(HtmlEscaper.escape(task.title()));

        String when = when(task, zone);
        if (!when.isEmpty()) {
            line.append(" · ").append(when);
        }
        if (task.location() != null) {
            line.append(" · ").append(HtmlEscaper.escape(task.location()));
        }
        // автор называется, только если это кто-то другой: «от Мама» в списке самой мамы — шум
        if (task.creatorId() != recipient.id()) {
            line.append(" · от ").append(AssigneeNames.of(byId, task.creatorId()));
        }
        return line.toString();
    }

    /**
     * У дела с интервалом — интервал, у остальных — срок. Смешивать нельзя: «08:00–08:40» и «к
     * 19:00» это разные обещания. Дата не печатается: она в заголовке дня.
     */
    private static String when(Task task, ZoneId zone) {
        if (task.isScheduled()) {
            ZonedDateTime start = task.startsAt().atZone(zone);
            return task.endsAt() == null
                    ? start.format(TIME)
                    : start.format(TIME) + "–" + task.endsAt().atZone(zone).format(TIME);
        }
        return task.dueAt() == null ? "" : "к " + task.dueAt().atZone(zone).format(TIME);
    }
}

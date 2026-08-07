package com.familytodo.adapter.telegram.view;

import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Вёрстка списка дел — одним сообщением.
 *
 * <p>Список из N задач не должен превращаться в N сообщений: лента, в которой ничего не найти, —
 * ровно та проблема, ради которой бот и делается.
 */
public final class TaskListView {

    /**
     * Больше не показываем. Полноценной пагинации нет намеренно: в сообщение помещается 4096
     * символов, а курсор страницы в 64-байтном {@code callback_data} и устаревание страниц стоят
     * дороже пользы.
     */
    public static final int MAX_ITEMS = 20;

    /**
     * Лимита по количеству мало: двадцать задач по 200 символов дают сообщение вдвое длиннее
     * допустимого, а Telegram отвечает на такое HTTP 400 — список не приходит вовсе. Поэтому
     * строки добавляются, пока хватает бюджета символов.
     */
    private static final int FOOTER_RESERVE = 40;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    public enum Kind {
        /** {@code /my} — кто просил. */
        MINE,
        /** {@code /assigned} — кого просили. */
        REQUESTED,
        /** {@code /all} — и то, и другое. */
        ALL
    }

    private TaskListView() {}

    public static String render(
            String header,
            List<Task> tasks,
            Map<Long, Member> byId,
            Kind kind,
            ZoneId zone,
            Instant now) {

        StringBuilder out = new StringBuilder("<b>").append(header).append("</b>\n");
        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;

        int shown = 0;
        int limit = Math.min(tasks.size(), MAX_ITEMS);
        while (shown < limit) {
            String line = line(shown + 1, tasks.get(shown), byId, kind, zone, now);
            if (out.length() + line.length() + 1 > budget) {
                break;
            }
            out.append('\n').append(line);
            shown++;
        }

        if (shown < tasks.size()) {
            out.append("\n\n…и ещё ").append(tasks.size() - shown);
        }
        return out.toString();
    }

    private static String line(
            int number,
            Task task,
            Map<Long, Member> byId,
            Kind kind,
            ZoneId zone,
            Instant now) {

        StringBuilder line = new StringBuilder();
        if (isOverdue(task, now)) {
            line.append("❗️");
        }
        line.append(number).append(". ").append(HtmlEscaper.escape(task.title()));

        String who = who(task, byId, kind);
        if (!who.isEmpty()) {
            line.append(" — ").append(who);
        }

        line.append(" · ").append(due(task, zone, now));
        return line.toString();
    }

    private static String who(Task task, Map<Long, Member> byId, Kind kind) {
        return switch (kind) {
            case MINE -> "от " + name(byId, task.creatorId());
            case REQUESTED -> name(byId, task.assignee().memberId());
            case ALL ->
                    name(byId, task.creatorId()) + " → " + name(byId, task.assignee().memberId());
        };
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }

    /** Срок показываем относительно сегодняшнего дня семьи: «сегодня» читается быстрее даты. */
    private static String due(Task task, ZoneId zone, Instant now) {
        if (task.dueAt() == null) {
            return "без срока";
        }

        ZonedDateTime local = task.dueAt().atZone(zone);
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate date = local.toLocalDate();

        if (date.equals(today)) {
            return "сегодня " + local.format(TIME);
        }
        if (date.equals(today.plusDays(1))) {
            return "завтра " + local.format(TIME);
        }
        return local.format(DATE) + " " + local.format(TIME);
    }

    private static boolean isOverdue(Task task, Instant now) {
        return task.dueAt() != null && task.dueAt().isBefore(now);
    }
}

package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

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

    /**
     * @param text готовая разметка сообщения
     * @param shown сколько задач реально поместилось — по нему строится клавиатура, иначе кнопка
     *     указывала бы на строку, которой в сообщении нет
     */
    public record Rendered(String text, int shown) {}

    public static Rendered render(
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
        return new Rendered(out.toString(), shown);
    }

    /** Номерные кнопки под списком: подписи повторяют нумерацию строк, чтобы не искать глазами. */
    public static InlineKeyboardMarkup keyboard(List<Task> tasks, Kind kind, int shown) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();

        for (int i = 0; i < shown; i++) {
            row.add(
                    InlineKeyboardButton.builder()
                            .text(Integer.toString(i + 1))
                            .callbackData(
                                    new CallbackData(
                                                    TaskCardView.PREFIX,
                                                    TaskCardView.CARD,
                                                    TaskRef.format(kind, tasks.get(i).id()))
                                            .serialize())
                            .build());
            if (row.size() == 5) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
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

        line.append(" · ").append(when(task, zone, now));
        if (task.location() != null) {
            line.append(" · ").append(HtmlEscaper.escape(task.location()));
        }
        return line.toString();
    }

    private static String who(Task task, Map<Long, Member> byId, Kind kind) {
        return switch (kind) {
            case MINE -> "от " + name(byId, task.creatorId());
            case REQUESTED -> AssigneeNames.of(task, byId);
            case ALL -> name(byId, task.creatorId()) + " → " + AssigneeNames.of(task, byId);
        };
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }

    /**
     * У дела с интервалом показываем интервал, у остальных — срок. Смешивать нельзя: «08:00–08:40»
     * и «к 19:00» это разные обещания.
     */
    private static String when(Task task, ZoneId zone, Instant now) {
        if (!task.isScheduled()) {
            return due(task, zone, now);
        }
        ZonedDateTime start = task.startsAt().atZone(zone);
        String prefix = dayPrefix(start.toLocalDate(), LocalDate.ofInstant(now, zone));
        if (task.endsAt() == null) {
            return prefix + start.format(TIME);
        }
        return prefix + start.format(TIME) + "–" + task.endsAt().atZone(zone).format(TIME);
    }

    private static String dayPrefix(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "";
        }
        if (date.equals(today.plusDays(1))) {
            return "завтра ";
        }
        return date.format(DATE) + " ";
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

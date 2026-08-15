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
     * Сколько дел на странице.
     *
     * <p>⚠️ Десять, а не двадцать, и число выбрано не на глаз. Заголовок дела — до 200 символов;
     * двадцать таких строк дают сообщение длиннее допустимых 4096, Telegram отвечает HTTP 400, и
     * список не приходит вовсе. Десять помещаются при любых заголовках, поэтому страница никогда не
     * обрезается — а обрезанная страница означала бы, что часть дел недостижима ни с какой из них.
     *
     * <p>Прежнее решение «пагинации нет, показываем первые двадцать» отменено 15 августа: оно
     * держалось на посылке «дел мало», а с появлением у бота всей семьи двадцать первое дело стало
     * недостижимым — кнопки у него не было вовсе.
     */
    public static final int PAGE_SIZE = 10;

    /** Запас под хвост сообщения. Страховка: при десяти строках бюджет не может кончиться. */
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
     * @param from номер первого показанного дела в общем списке — по нему строится клавиатура
     * @param shown сколько дел на этой странице
     * @param page номер страницы с нуля
     * @param pages сколько страниц всего
     */
    public record Rendered(String text, int from, int shown, int page, int pages) {}

    public static int pagesFor(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public static Rendered render(
            String header,
            List<Task> tasks,
            Map<Long, Member> byId,
            Kind kind,
            ZoneId zone,
            Instant now,
            int page) {

        int pages = pagesFor(tasks.size());
        int current = Math.clamp(page, 0, pages - 1);
        int from = current * PAGE_SIZE;
        int to = Math.min(tasks.size(), from + PAGE_SIZE);

        StringBuilder out = new StringBuilder("<b>").append(header);
        // страница называется только когда их несколько: «стр. 1 из 1» сообщает ровно ничего
        if (pages > 1) {
            out.append(" · стр. ").append(current + 1).append(" из ").append(pages);
        }
        out.append("</b>\n");

        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;
        int shown = 0;
        for (int i = from; i < to; i++) {
            // ⚠️ нумерация сквозная: те же числа стоят на кнопках, и «1» на третьей странице
            // отправляла бы искать первое дело списка
            String line = line(i + 1, tasks.get(i), byId, kind, zone, now);
            if (out.length() + line.length() + 1 > budget) {
                break;
            }
            out.append('\n').append(line);
            shown++;
        }
        return new Rendered(out.toString(), from, shown, current, pages);
    }

    /** Номерные кнопки под списком: подписи повторяют нумерацию строк, чтобы не искать глазами. */
    public static InlineKeyboardMarkup keyboard(List<Task> tasks, Kind kind, Rendered rendered) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();

        for (int i = 0; i < rendered.shown(); i++) {
            int index = rendered.from() + i;
            row.add(
                    InlineKeyboardButton.builder()
                            .text(Integer.toString(index + 1))
                            .callbackData(
                                    new CallbackData(
                                                    TaskCardView.PREFIX,
                                                    TaskCardView.CARD,
                                                    TaskRef.format(kind, tasks.get(index).id()))
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

        // ⚠️ стрелка появляется, только если ей есть куда вести: кнопка, которая ничего не делает,
        // читается как сломанная — ответить «дальше некуда» отсюда нечем
        InlineKeyboardRow nav = new InlineKeyboardRow();
        if (rendered.page() > 0) {
            nav.add(pageButton("◀ Назад", kind, rendered.page() - 1));
        }
        if (rendered.page() < rendered.pages() - 1) {
            nav.add(pageButton("Вперёд ▶", kind, rendered.page() + 1));
        }
        if (!nav.isEmpty()) {
            rows.add(nav);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton pageButton(String label, Kind kind, int page) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(
                        new CallbackData(
                                        TaskCardView.PREFIX,
                                        TaskCardView.PAGE,
                                        TaskRef.letter(kind) + Integer.toString(page))
                                .serialize())
                .build();
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
        // ⚠️ у события своя пометка, а не восклицательный знак: «просрочено» значит «ещё можно
        // сделать», а прошедшее событие сделать уже нельзя — его переносят или закрывают
        if (isPastEvent(task, zone, now)) {
            line.append("⌛");
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

    /**
     * Событие, которое уже прошло. Граница по дню, а не по моменту: сегодняшнее событие прошедшим не
     * считается весь день — ровно та же граница, по которой такие дела уводятся в конец списка.
     */
    private static boolean isPastEvent(Task task, ZoneId zone, Instant now) {
        if (task.startsAt() == null) {
            return false;
        }
        Instant over = task.endsAt() != null ? task.endsAt() : task.startsAt();
        return over.isBefore(LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant());
    }

    private static boolean isOverdue(Task task, Instant now) {
        return task.dueAt() != null && task.dueAt().isBefore(now);
    }
}

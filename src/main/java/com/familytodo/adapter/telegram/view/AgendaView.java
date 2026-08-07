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
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Расписание на горизонт: дела, сгруппированные по дням.
 *
 * <p>Вёрстка повторяет бумажный список из макетов: заголовок дня, под ним дела по времени. Сетку
 * Telegram не рисует — в сообщении нет ни таблиц, ни цвета, ни позиционирования.
 */
public final class AgendaView {

    public static final String PREFIX = "a";
    public static final String DAYS = "days";

    /** Горизонты из макетов. */
    public static final List<Integer> HORIZONS = List.of(1, 3, 7, 30);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale RU_LOCALE = Locale.of("ru");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE);

    private static final int FOOTER_RESERVE = 60;

    private AgendaView() {}

    public record Rendered(String text, List<Task> shown) {}

    public static Rendered render(
            List<Task> dated,
            List<Task> undated,
            Map<Long, Member> byId,
            ZoneId zone,
            Instant now,
            int days) {

        StringBuilder out = new StringBuilder("<b>").append(header(days)).append("</b>\n");
        int budget = HtmlEscaper.MESSAGE_LIMIT - FOOTER_RESERVE;
        List<Task> shown = new ArrayList<>();

        LocalDate currentDay = null;
        for (Task task : dated) {
            ZonedDateTime moment = momentOf(task).atZone(zone);
            LocalDate day = moment.toLocalDate();

            StringBuilder chunk = new StringBuilder();
            if (!day.equals(currentDay)) {
                chunk.append('\n').append(dayHeader(day, LocalDate.ofInstant(now, zone)));
            }
            chunk.append('\n').append(line(task, byId, zone, now));

            if (out.length() + chunk.length() > budget) {
                break;
            }
            out.append(chunk);
            currentDay = day;
            shown.add(task);
        }

        if (shown.isEmpty() && undated.isEmpty()) {
            return new Rendered(Texts.AGENDA_EMPTY, List.of());
        }

        appendUndated(out, undated, byId, shown, budget);

        int hidden = dated.size() + undated.size() - shown.size();
        if (hidden > 0) {
            out.append("\n\n…и ещё ").append(hidden);
        }
        return new Rendered(out.toString(), shown);
    }

    /** Кнопки под расписанием: сначала переключение горизонта, потом номера дел. */
    public static InlineKeyboardMarkup keyboard(List<Task> shown, int days) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow horizons = new InlineKeyboardRow();
        for (int horizon : HORIZONS) {
            horizons.add(
                    InlineKeyboardButton.builder()
                            .text(horizon == days ? "· " + label(horizon) + " ·" : label(horizon))
                            .callbackData(
                                    new CallbackData(PREFIX, DAYS, Integer.toString(horizon))
                                            .serialize())
                            .build());
        }
        rows.add(horizons);

        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int i = 0; i < shown.size(); i++) {
            row.add(
                    InlineKeyboardButton.builder()
                            .text(Integer.toString(i + 1))
                            .callbackData(
                                    new CallbackData(
                                                    TaskCardView.PREFIX,
                                                    TaskCardView.CARD,
                                                    TaskRef.format(
                                                            TaskListView.Kind.ALL,
                                                            shown.get(i).id()))
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

    private static void appendUndated(
            StringBuilder out,
            List<Task> undated,
            Map<Long, Member> byId,
            List<Task> shown,
            int budget) {

        if (undated.isEmpty()) {
            return;
        }
        String header = "\n\n<b>Без срока</b>";
        if (out.length() + header.length() > budget) {
            return;
        }
        out.append(header);

        for (Task task : undated) {
            String chunk = "\n" + (shown.size() + 1) + ". " + HtmlEscaper.escape(task.title());
            if (out.length() + chunk.length() > budget) {
                return;
            }
            out.append(chunk);
            shown.add(task);
        }
    }

    private static String line(Task task, Map<Long, Member> byId, ZoneId zone, Instant now) {
        StringBuilder line = new StringBuilder();
        line.append(time(task, zone)).append("  ");
        line.append(HtmlEscaper.escape(task.title()));

        if (task.location() != null) {
            line.append(" — ").append(HtmlEscaper.escape(task.location()));
        }
        line.append("  ·  ").append(name(byId, task.assignee().memberId()));
        return line.toString();
    }

    /** У интервала показываем оба конца, у срока — «к 19:00»: это разные обещания. */
    private static String time(Task task, ZoneId zone) {
        if (task.isScheduled()) {
            String start = task.startsAt().atZone(zone).format(TIME);
            return task.endsAt() == null
                    ? start
                    : start + "–" + task.endsAt().atZone(zone).format(TIME);
        }
        return "к " + task.dueAt().atZone(zone).format(TIME);
    }

    private static String dayHeader(LocalDate day, LocalDate today) {
        String weekday = day.getDayOfWeek().getDisplayName(TextStyle.FULL, RU_LOCALE);
        String prefix =
                day.equals(today) ? "сегодня, " : day.equals(today.plusDays(1)) ? "завтра, " : "";
        return "\n<b>" + prefix + day.format(DAY) + ", " + weekday + "</b>";
    }

    private static Instant momentOf(Task task) {
        return task.isScheduled() ? task.startsAt() : task.dueAt();
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }

    private static String header(int days) {
        return "Расписание · " + label(days);
    }

    private static String label(int days) {
        return switch (days) {
            case 1 -> "день";
            case 3 -> "3 дня";
            case 7 -> "неделя";
            default -> days + " дней";
        };
    }
}

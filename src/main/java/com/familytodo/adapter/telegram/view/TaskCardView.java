package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.domain.Assignment;
import com.familytodo.domain.Actor;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import com.familytodo.domain.TaskStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/** Карточка одной задачи: что, от кого, кому, к какому сроку — и что с ней можно сделать. */
public final class TaskCardView {

    public static final String PREFIX = "t";
    public static final String CARD = "card";
    public static final String DONE = "done";
    public static final String DECLINE = "decline";
    public static final String REOPEN = "reopen";
    public static final String BACK = "back";

    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm");

    private TaskCardView() {}

    public static String render(
            Task task, Map<Long, Member> byId, ZoneId zone, Instant now) {

        StringBuilder out = new StringBuilder();
        out.append(status(task, now)).append(' ');
        out.append("<b>").append(HtmlEscaper.escape(task.title())).append("</b>\n\n");

        out.append("Просит: ").append(name(byId, task.creatorId())).append('\n');
        out.append("Делает: ").append(AssigneeNames.of(task, byId)).append('\n');
        out.append("Срок: ").append(due(task, zone)).append('\n');
        if (task.isScheduled()) {
            out.append("Когда: ").append(slot(task, zone)).append('\n');
        }
        if (task.location() != null) {
            out.append("Где: ").append(HtmlEscaper.escape(task.location())).append('\n');
        }

        // Отказы — по людям, а не одной строкой на дело: отказавшихся может быть несколько,
        // и «причина отказа» без имени при двух исполнителях не отвечает ни на что.
        // Формулировка безличная намеренно: «отказался» пришлось бы согласовывать с полом,
        // которого у нас нет, — в Telegram приходит только имя.
        for (Assignment assignment : task.assignments()) {
            if (assignment.hasDeclined()) {
                out.append("\nНе может: ")
                        .append(AssigneeNames.of(byId, assignment.memberId()))
                        .append(" — ")
                        .append(HtmlEscaper.escape(assignment.declineReason()));
            }
        }
        if (AssigneeNames.anyoneDeclined(task)) {
            out.append('\n');
        }

        // ⚠️ Без этой строки «Удалить» на вхождении выглядит как обычное удаление, а дело
        // возвращается: удаляется один день, само правило продолжает работать.
        if (task.isOccurrence()) {
            out.append('\n').append(Texts.TASK_IS_OCCURRENCE);
        }
        return out.toString();
    }

    /**
     * Набор кнопок спрашивается у домена, а не собирается по своим условиям: два набора правил
     * неизбежно разошлись бы, и кнопка появилась бы там, где нажатие даёт отказ.
     */
    public static InlineKeyboardMarkup keyboard(Task task, Actor actor, TaskListView.Kind kind) {
        String argument = TaskRef.format(kind, task.id());
        InlineKeyboardRow actions = new InlineKeyboardRow();

        if (task.mayComplete(actor)) {
            actions.add(button("Готово", DONE, argument));
        }
        if (task.mayDecline(actor)) {
            actions.add(button("Не могу", DECLINE, argument));
        }
        if (task.mayReopen(actor)) {
            actions.add(button("Вернуть", REOPEN, argument));
        }

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder =
                InlineKeyboardMarkup.builder();
        if (!actions.isEmpty()) {
            builder.keyboardRow(actions);
        }
        if (task.mayModify(actor)) {
            builder.keyboardRow(
                    new InlineKeyboardRow(
                            editButton("Изменить", TaskEditView.MENU, argument),
                            editButton("Удалить", TaskEditView.DELETE, argument)));
        }
        InlineKeyboardRow footer =
                new InlineKeyboardRow(
                        button("← Назад", BACK, String.valueOf(TaskRef.letter(kind))));
        // ⚠️ Переход к правилу — единственный способ распорядиться повторением: из карточки
        // вхождения видно только один день, а «больше не повторять» живёт на серии
        if (task.isOccurrence()) {
            footer.add(
                    InlineKeyboardButton.builder()
                            .text(Texts.TASK_TO_SERIES)
                            .callbackData(
                                    CallbackData.of(
                                                    SeriesView.PREFIX,
                                                    SeriesView.OPEN,
                                                    task.seriesId())
                                            .serialize())
                            .build());
        }
        builder.keyboardRow(footer);
        return builder.build();
    }

    private static InlineKeyboardButton button(String label, String action, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, action, argument).serialize())
                .build();
    }

    private static InlineKeyboardButton editButton(String label, String action, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(TaskEditView.PREFIX, action, argument).serialize())
                .build();
    }

    private static String status(Task task, Instant now) {
        return switch (task.status()) {
            case DONE -> "✅";
            case DECLINED -> "🚫";
            case OPEN -> overdue(task, now) ? "❗️" : "•";
        };
    }

    private static boolean overdue(Task task, Instant now) {
        return task.dueAt() != null && task.dueAt().isBefore(now);
    }

    private static String due(Task task, ZoneId zone) {
        if (task.dueAt() == null) {
            return "без срока";
        }
        ZonedDateTime local = task.dueAt().atZone(zone);
        return local.format(FULL);
    }

    /** Интервал показываем целиком; открытый конец — просто время начала. */
    private static String slot(Task task, ZoneId zone) {
        String start = task.startsAt().atZone(zone).format(FULL);
        if (task.endsAt() == null) {
            return start;
        }
        return start + " – " + task.endsAt().atZone(zone).format(TIME_ONLY);
    }

    private static String name(Map<Long, Member> byId, long memberId) {
        Member member = byId.get(memberId);
        return HtmlEscaper.escape(member == null ? "кто-то" : member.displayName());
    }
}

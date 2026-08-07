package com.familytodo.adapter.telegram.view;

import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.TaskRef;
import com.familytodo.domain.Member;
import com.familytodo.domain.Task;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/** Клавиатуры правки задачи и подтверждения удаления. */
public final class TaskEditView {

    public static final String PREFIX = "e";
    public static final String MENU = "menu";
    public static final String TITLE = "title";
    public static final String DUE = "due";
    public static final String SET_DUE = "setdue";
    public static final String WHO = "who";
    public static final String SET_WHO = "setwho";
    public static final String DELETE = "del";
    public static final String DELETE_OK = "delok";

    private TaskEditView() {}

    public static InlineKeyboardMarkup menu(Task task, TaskListView.Kind kind) {
        String argument = TaskRef.format(kind, task.id());
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Название", TITLE, argument),
                                button("Срок", DUE, argument),
                                button("Исполнителя", WHO, argument)))
                .keyboardRow(
                        new InlineKeyboardRow(
                                cardButton("← К задаче", argument)))
                .build();
    }

    public static InlineKeyboardMarkup dueDates() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Сегодня", SET_DUE, "today"),
                                button("Завтра", SET_DUE, "tomorrow")))
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("В выходные", SET_DUE, "weekend"),
                                button("Без срока", SET_DUE, "none")))
                .keyboardRow(new InlineKeyboardRow(button("Своя дата", SET_DUE, "custom")))
                .build();
    }

    public static InlineKeyboardMarkup assignees(List<Member> family) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (Member member : family) {
            row.add(
                    button(
                            HtmlEscaper.escape(member.displayName()),
                            SET_WHO,
                            Long.toString(member.id())));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /** Удаление необратимо стирает строку — подтверждение обязательно. */
    public static InlineKeyboardMarkup confirmDeletion(Task task, TaskListView.Kind kind) {
        String argument = TaskRef.format(kind, task.id());
        return InlineKeyboardMarkup.builder()
                .keyboardRow(
                        new InlineKeyboardRow(
                                button("Да, удалить", DELETE_OK, argument),
                                cardButton("Отмена", argument)))
                .build();
    }

    private static InlineKeyboardButton button(String label, String action, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(new CallbackData(PREFIX, action, argument).serialize())
                .build();
    }

    private static InlineKeyboardButton cardButton(String label, String argument) {
        return InlineKeyboardButton.builder()
                .text(label)
                .callbackData(
                        new CallbackData(TaskCardView.PREFIX, TaskCardView.CARD, argument)
                                .serialize())
                .build();
    }
}

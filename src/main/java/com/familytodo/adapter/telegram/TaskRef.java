package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.TaskListView;

/**
 * Ссылка на задачу вместе с тем, из какого списка её открыли: {@code m1234}.
 *
 * <p>Откуда пришли, нужно знать для кнопки «← Назад». Держать это на сервере значило бы заводить
 * состояние там, где хватает одного символа в самом {@code callback_data} — а поле и так
 * недоверенное, так что защищённее от подделки оно бы не стало.
 *
 * <p>Разделителя нет: формат {@code prefix:action:argument} занимает оба двоеточия, а буква
 * впереди числа однозначно отделяется без него.
 */
public record TaskRef(TaskListView.Kind kind, long taskId) {

    public static TaskRef parse(String argument) {
        if (argument == null || argument.length() < 2) {
            throw new IllegalArgumentException("task reference is malformed");
        }
        TaskListView.Kind kind =
                switch (argument.charAt(0)) {
                    case 'm' -> TaskListView.Kind.MINE;
                    case 'r' -> TaskListView.Kind.REQUESTED;
                    case 'a' -> TaskListView.Kind.ALL;
                    default -> throw new IllegalArgumentException("unknown list kind");
                };
        try {
            return new TaskRef(kind, Long.parseLong(argument.substring(1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("task id is not a number", e);
        }
    }

    public static String format(TaskListView.Kind kind, long taskId) {
        return letter(kind) + Long.toString(taskId);
    }

    public String argument() {
        return format(kind, taskId);
    }

    public static char letter(TaskListView.Kind kind) {
        return switch (kind) {
            case MINE -> 'm';
            case REQUESTED -> 'r';
            case ALL -> 'a';
        };
    }
}

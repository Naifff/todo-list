package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.TaskListView;

/**
 * Ссылка на задачу вместе с тем, откуда её открыли: {@code m1234} из списка, {@code g7-1234} из
 * расписания на неделю.
 *
 * <p>Откуда пришли, нужно знать для кнопки «← Назад». Держать это на сервере значило бы заводить
 * состояние там, где хватает пары символов в самом {@code callback_data} — а поле и так
 * недоверенное, так что защищённее от подделки оно бы не стало.
 *
 * <p>Разделителя между буквой и числом нет: формат {@code prefix:action:argument} занимает оба
 * двоеточия, а буква впереди числа однозначно отделяется без него. У расписания разделитель нужен:
 * там перед делом стоит ещё и горизонт.
 *
 * @param kind список, в который ведёт возврат; у расписания — {@link TaskListView.Kind#ALL}, потому
 *     что карточка рисуется одинаково
 * @param agendaDays горизонт расписания, из которого открыли дело; {@code 0} — открыто из списка.
 *     ⚠️ Без горизонта возврат из дела, открытого на неделе, показывал бы день
 */
public record TaskRef(TaskListView.Kind kind, long taskId, int agendaDays) {

    private static final char AGENDA = 'g';

    public TaskRef(TaskListView.Kind kind, long taskId) {
        this(kind, taskId, 0);
    }

    public static TaskRef parse(String argument) {
        if (argument == null || argument.length() < 2) {
            throw new IllegalArgumentException("task reference is malformed");
        }
        if (argument.charAt(0) == AGENDA) {
            return parseAgenda(argument);
        }
        TaskListView.Kind kind =
                switch (argument.charAt(0)) {
                    case 'm' -> TaskListView.Kind.MINE;
                    case 'r' -> TaskListView.Kind.REQUESTED;
                    case 'a' -> TaskListView.Kind.ALL;
                    default -> throw new IllegalArgumentException("unknown list kind");
                };
        return new TaskRef(kind, number(argument.substring(1)));
    }

    private static TaskRef parseAgenda(String argument) {
        int dash = argument.indexOf('-');
        if (dash < 2 || dash == argument.length() - 1) {
            throw new IllegalArgumentException("agenda reference is malformed");
        }
        int days = (int) number(argument.substring(1, dash));
        return new TaskRef(TaskListView.Kind.ALL, number(argument.substring(dash + 1)), days);
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("task reference is not a number", e);
        }
    }

    public static String format(TaskListView.Kind kind, long taskId) {
        return letter(kind) + Long.toString(taskId);
    }

    /** Ссылка из расписания: горизонт и дело. */
    public static String forAgenda(int days, long taskId) {
        return AGENDA + Integer.toString(days) + '-' + taskId;
    }

    public boolean isFromAgenda() {
        return agendaDays > 0;
    }

    public String argument() {
        return isFromAgenda() ? forAgenda(agendaDays, taskId) : format(kind, taskId);
    }

    public static char letter(TaskListView.Kind kind) {
        return switch (kind) {
            case MINE -> 'm';
            case REQUESTED -> 'r';
            case ALL -> 'a';
        };
    }
}

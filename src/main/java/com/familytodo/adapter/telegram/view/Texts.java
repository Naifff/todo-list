package com.familytodo.adapter.telegram.view;

/** Тексты для пользователя. Собраны в одном месте, чтобы тон не разъезжался по хендлерам. */
public final class Texts {

    /**
     * Единственный ответ незнакомцу. Ни списка команд, ни намёка на то, что бот кого-то
     * обслуживает: чем меньше сказано, тем меньше поводов подбирать дальше.
     */
    public static final String STRANGER = "Нужно приглашение от члена семьи.";

    public static final String UNKNOWN_COMMAND = "Не знаю такой команды. /help — что я умею.";

    public static final String INTERNAL_ERROR = "Что-то пошло не так. Попробуй ещё раз.";

    public static final String NOT_FOUND = "Задача не найдена.";

    public static final String NOT_PERMITTED = "Это действие тебе недоступно.";

    public static final String ALREADY_DONE = "Уже отмечено.";

    public static final String ALREADY_OPEN = "Задача и так открыта.";

    public static final String ALREADY_CLOSED = "Задача уже закрыта.";

    private Texts() {}
}

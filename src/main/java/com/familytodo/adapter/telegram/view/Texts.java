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

    // --- онбординг ---

    public static final String ASK_FAMILY_NAME =
            "Привет! Похоже, ты здесь впервые.\n\nКак назвать семью?";

    public static final String FAMILY_NAME_TOO_LONG =
            "Слишком длинно. Давай покороче — до 60 символов.";

    public static final String ASK_TIMEZONE =
            "Какой у вас часовой пояс? От него зависят сроки и время утреннего дайджеста.";

    public static final String ASK_TIMEZONE_AGAIN = "Не понял выбор. Нажми кнопку ещё раз.";

    /**
     * Один ответ на все причины: истёк, уже использован, не существует. Незнакомец не должен
     * узнать, какие коды бывают.
     */
    public static final String INVITE_INVALID =
            "Приглашение недействительно. Попроси новую ссылку.";

    public static final String ALREADY_IN_FAMILY = "Ты уже в семье.";

    public static final String MAIN_MENU =
            """
            Что я умею:

            /new — попросить кого-то о деле
            /my — что просили у меня
            /assigned — что я попросил у других
            /all — все дела семьи (только для родителей)
            /family — состав семьи и приглашения
            /help — эта справка""";

    // --- создание задачи ---

    public static final String ASK_TASK_TITLE = "Что нужно сделать?";

    public static final String TASK_TITLE_TOO_LONG =
            "Слишком длинно. До 200 символов — это просьба, а не инструкция.";

    public static final String ASK_ASSIGNEE = "Кого попросить?";

    public static final String ASK_DUE = "К какому сроку?";

    public static final String ASK_CUSTOM_DUE =
            "Напиши срок: <code>15.08</code>, <code>15.08 18:30</code> или <code>18:30</code>";

    public static final String DUE_NOT_PARSED =
            "Не понял срок. Попробуй так: <code>15.08</code>, <code>15.08 18:30</code> или <code>18:30</code>";

    public static final String DIALOG_EXPIRED = "Начнём заново: /new";

    // --- списки ---

    public static final String MINE_HEADER = "Что просят у меня";
    public static final String REQUESTED_HEADER = "Что я попросил";
    public static final String ALL_HEADER = "Все дела семьи";

    public static final String MINE_EMPTY = "У тебя ничего не просят.";
    public static final String REQUESTED_EMPTY = "Ты пока ни о чём не просил.";
    public static final String ALL_EMPTY = "Открытых дел нет.";

    public static final String ALL_IS_FOR_PARENTS = "Весь список семьи видят только родители.";

    // --- карточка ---

    public static final String ASK_DECLINE_REASON = "Почему не получится?";

    public static final String DECLINE_REASON_TOO_LONG = "Покороче, пожалуйста — до 200 символов.";

    // --- семья ---

    public static final String FAMILY_HEADER = "Семья";

    public static final String INVITE_IS_FOR_PARENTS = "Приглашать могут только родители.";

    public static final String REMOVE_IS_FOR_PARENTS = "Исключать могут только родители.";

    public static final String SETTINGS_ARE_FOR_PARENTS = "Настройки меняют только родители.";

    /** Отказ должен объяснять, а не просто запрещать: иначе выглядит как поломка. */
    public static final String LAST_PARENT_STAYS =
            "Это единственный родитель. Без него некому будет приглашать и управлять семьёй — "
                    + "сначала сделай родителем кого-то ещё.";

    public static final String NOBODY_TO_REMOVE = "Кроме тебя в семье никого нет.";

    public static final String ASK_WHO_TO_REMOVE = "Кого исключить?";

    public static final String REMOVED_NOTICE = "Исключён";

    public static final String TIMEZONE_SAVED = "Часовой пояс сохранён";

    public static final String DIGEST_SAVED = "Время дайджеста сохранено";

    public static final String DONE_NOTICE = "Отмечено";
    public static final String DECLINED_NOTICE = "Отказ записан";
    public static final String REOPENED_NOTICE = "Вернули в работу";

    private Texts() {}
}

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

    // --- списки покупок ---

    public static final String SHOP_FOOD_HEADER = "🍎 <b>Продукты</b>";

    public static final String SHOP_HOUSEHOLD_HEADER = "🧴 <b>Хозяйство</b>";

    public static final String SHOP_EMPTY = "Пока пусто.";

    public static final String SHOP_TRUNCATED = "Показаны первые 30 позиций.";

    public static final String SHOP_ADD = "➕ Добавить";

    public static final String SHOP_CLEAR_BOUGHT = "🧹 Убрать купленное";

    public static final String SHOP_ASK_ITEMS =
            """
            Что купить? Можно списком — по одной позиции в строке:

            <code>молоко
            хлеб
            стиральный порошок</code>""";

    /** Одна фраза на обе причины: пусто и слишком длинно. Разбираться человеку тут не в чем. */
    public static final String SHOP_ITEM_REJECTED =
            "Не понял. Одна позиция в строке, до 100 символов, не больше 20 за раз.";

    public static final String SHOP_SWITCH_TO_FOOD = "🍎 Продукты";

    public static final String SHOP_SWITCH_TO_HOUSEHOLD = "🧴 Хозяйство";

    // --- онбординг ---

    public static final String ASK_FAMILY_NAME =
            "Привет! Похоже, ты здесь впервые.\n\nКак назвать семью?";

    /**
     * Незнакомцу не сообщается ничего лишнего: ни сколько семей уже есть, ни как попросить доступ у
     * владельца бота. Ответ одинаков и для случайно забредшего, и для того, кому ссылку забыли
     * прислать.
     */
    public static final String FAMILY_CREATION_CLOSED =
            "Чтобы пользоваться ботом, нужна ссылка-приглашение от члена семьи.";

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
            /agenda — расписание на день, 3 дня, неделю, месяц
            /shop — списки покупок: продукты и хозяйство
            /my — что просили у меня
            /assigned — что я попросил у других
            /series — повторяющиеся дела
            /all — все дела семьи (только для родителей)
            /family — состав семьи и приглашения
            /help — эта справка""";

    // --- создание задачи ---

    public static final String ASK_TASK_TITLE = "Что нужно сделать?";

    public static final String TASK_TITLE_TOO_LONG =
            "Слишком длинно. До 200 символов — это просьба, а не инструкция.";

    public static final String ASK_ASSIGNEES = "Кого попросить? Можно отметить нескольких.";

    /** В правке «Готово» не нужно: каждое нажатие уже применено, отмеченные — текущий состав. */
    public static final String ASK_ASSIGNEES_EDIT =
            "Кто делает? Отмеченные — те, на ком дело сейчас. Нажатие добавляет или снимает.";

    public static final String PICK_AT_LEAST_ONE_ASSIGNEE = "Отметьте хотя бы одного.";

    /** Отказ должен объяснять, а не просто не срабатывать: иначе кнопка выглядит сломанной. */
    public static final String LAST_ASSIGNEE_STAYS =
            "Это единственный исполнитель — дело без него станет ничьим. "
                    + "Сначала отметьте кого-то ещё.";

    public static final String ASK_REPEAT = "Повторять?";

    public static final String PICK_AT_LEAST_ONE_DAY =
            "Отметьте хотя бы один день — иначе повторять нечего.";

    public static final String ASK_DUE = "К какому сроку?";

    public static final String ASK_CUSTOM_DUE =
            """
            Напиши когда и где:
            <code>18:00-19:00 парк</code> — займёт этот час
            <code>19:00 дом</code> — срок к семи
            <code>15.08 08:00-08:40 школа</code> — с датой
            <code>Zoom</code> — только место, без даты

            Текст без времени понимается как место.""";

    public static final String DUE_NOT_PARSED =
            "Не понял срок. Попробуй так: <code>15.08</code>, <code>15.08 18:30</code> или <code>18:30</code>";

    /** Тот же язык, что и при создании: экран разбирает ввод одним и тем же разбором. */
    public static final String ASK_SLOT =
            """
            Напиши когда и где:
            <code>27.08</code> — перенести на эту дату
            <code>27.08 18:00</code> — срок к шести вечера
            <code>08:00-08:40 школа</code> — займёт это время
            <code>Zoom</code> — только место

            Меняется только названное. <code>-</code> убирает время и место.""";

    public static final String SLOT_NOT_PARSED =
            "Не понял. Формат: <code>27.08</code>, <code>27.08 18:00</code>, "
                    + "<code>08:00-08:40 школа</code> или просто место.";

    public static final String DIALOG_EXPIRED = "Начнём заново: /new";

    // --- списки ---

    public static final String MINE_HEADER = "Что просят у меня";
    public static final String REQUESTED_HEADER = "Что я попросил";
    public static final String ALL_HEADER = "Все дела семьи";

    public static final String MINE_EMPTY = "У тебя ничего не просят.";
    public static final String REQUESTED_EMPTY = "Ты пока ни о чём не просил.";
    public static final String ALL_EMPTY = "Открытых дел нет.";

    public static final String AGENDA_EMPTY = "На этот срок дел нет.";

    public static final String ALL_IS_FOR_PARENTS = "Весь список семьи видят только родители.";

    // --- повторяющиеся дела ---

    public static final String SERIES_HEADER = "🔁 <b>Повторяющиеся дела</b>";

    /**
     * Пустой экран обязан говорить, откуда берутся серии: команды «завести повторение» нет, оно
     * спрашивается шагом внутри {@code /new}, и найти его самому неоткуда.
     */
    public static final String SERIES_EMPTY =
            "Повторяющихся дел нет.\n\nОни заводятся в /new: после срока бот спросит «Повторять?».";

    public static final String SERIES_BACK = "← К списку";

    public static final String SERIES_STOP = "⏹ Остановить";

    /**
     * Переспрашиваем, потому что остановка убирает будущие дела у всей семьи разом, а вернуть их
     * можно только заведя правило заново.
     */
    public static final String SERIES_STOP_CONFIRM =
            "Больше не повторять? Будущие дела уйдут из списков, сделанное останется в истории.";

    public static final String SERIES_STOP_OK = "Да, остановить";

    public static final String SERIES_STOP_CANCEL = "Отмена";

    public static final String SERIES_STOPPED = "Серия остановлена.";

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

    public static final String PROFILE_IS_FOR_PARENTS = "Имя и цвет меняют только родители.";

    public static final String ASK_WHOSE_PROFILE = "Кого правим?";

    public static final String PROFILE_HEADER = "Имя показывается в списках и в расписании, цвет — в расписании.";

    public static final String ASK_NEW_MEMBER_NAME = "Как записать этого человека?";

    public static final String MEMBER_NAME_REJECTED = "Не понял. Имя не пустое и до 40 символов.";

    public static final String ASK_COLOR = "Каким цветом рисовать его дела?";

    public static final String REMOVED_NOTICE = "Исключён";

    public static final String TIMEZONE_SAVED = "Часовой пояс сохранён";

    public static final String DIGEST_SAVED = "Время дайджеста сохранено";

    public static final String DONE_NOTICE = "Отмечено";
    public static final String DECLINED_NOTICE = "Отказ записан";
    public static final String REOPENED_NOTICE = "Вернули в работу";

    private Texts() {}
}

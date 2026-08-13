package com.familytodo.adapter.telegram;

/**
 * Шаг многошагового сценария: что бот ждёт от следующего сообщения.
 *
 * <p>Живёт в памяти и переживает только 15 минут — потеря при рестарте допустима. Держать это в БД
 * значило бы хранить обрывки намерений, которые никому не нужны через час.
 */
public sealed interface DialogState {

    /** Онбординг: спросили название семьи. */
    record AwaitingFamilyName() implements DialogState {}

    /** Онбординг: название есть, ждём выбора таймзоны кнопкой. */
    record AwaitingTimezone(String familyName) implements DialogState {}

    /** {@code /new}: спросили, что нужно сделать. */
    record AwaitingTaskTitle() implements DialogState {}

    /**
     * {@code /new}: текст есть, копим отмеченных исполнителей, пока не нажмут «Дальше».
     *
     * <p>⚠️ Отметки всегда, даже когда исполнитель один. Прежде тап по имени выбирал одного и сразу
     * вёл дальше, а несколько выбирались отдельной кнопкой, — так экономилось нажатие на частом
     * случае, но до кнопки под именами с телефона не доходили ни разу.
     *
     * <p>{@code plan} не пуст, если дело написали одной строкой («сходить на ролики 14.08
     * 18:30-20:00 цирк»). Тогда срок уже назван, и спрашивать его кнопками — второе «когда?» подряд.
     */
    record ChoosingAssignees(
            String title,
            java.util.List<Long> chosen,
            com.familytodo.application.DueDateParser.Plan plan)
            implements DialogState {

        /** Обычный путь: название есть, срок ещё спросим. */
        public ChoosingAssignees(String title, java.util.List<Long> chosen) {
            this(title, chosen, null);
        }

        /**
         * ⚠️ Список, а не множество. {@code Set.copyOf} порядок <b>не сохраняет</b>: у неизменяемых
         * множеств JDK итерация зависит от соли, своей на каждый запуск JVM. С множеством порядок
         * исполнителей — а значит и порядок имён в карточке и в расписании — менялся бы от
         * перезапуска к перезапуску. Нашлось не рассуждением, а тестом, который упал и прошёл
         * подряд без единой правки кода.
         *
         * <p>Повторы снимает переключатель: одного человека дважды в списке быть не может.
         */
        public ChoosingAssignees {
            chosen = java.util.List.copyOf(chosen);
        }
    }

    /** {@code /new}: исполнители выбраны, ждём срока кнопкой. */
    record AwaitingDueDate(String title, java.util.List<Long> assigneeIds) implements DialogState {

        public AwaitingDueDate {
            assigneeIds = java.util.List.copyOf(assigneeIds);
        }
    }

    /**
     * {@code /new}: срок выбран, спрашиваем про повторение.
     *
     * <p>Срок несём с собой: время серии — это время выбранного срока, спрашивать его отдельно
     * значило бы добавить шаг ради того, что человек только что назвал.
     */
    record AwaitingRepeat(
            String title,
            java.util.List<Long> assigneeIds,
            com.familytodo.application.DueDateParser.Plan plan)
            implements DialogState {

        public AwaitingRepeat {
            assigneeIds = java.util.List.copyOf(assigneeIds);
        }
    }

    /** {@code /new}: выбрали «Свои дни» — копим отмеченные, пока не нажмут «Готово». */
    record ChoosingDays(
            String title,
            java.util.List<Long> assigneeIds,
            com.familytodo.application.DueDateParser.Plan plan,
            java.util.Set<java.time.DayOfWeek> days)
            implements DialogState {

        public ChoosingDays {
            assigneeIds = java.util.List.copyOf(assigneeIds);
            days = java.util.Set.copyOf(days);
        }
    }

    /** {@code /new}: выбрали «Своя дата» — ждём её текстом. */
    record AwaitingCustomDueDate(String title, java.util.List<Long> assigneeIds)
            implements DialogState {

        public AwaitingCustomDueDate {
            assigneeIds = java.util.List.copyOf(assigneeIds);
        }
    }

    /**
     * {@code /shop}: нажали «Добавить» — ждём позиции текстом, по строке на позицию.
     *
     * <p>Список несём с собой, потому что позиции обязаны попасть туда, откуда нажали кнопку.
     */
    record AwaitingShoppingItems(com.familytodo.domain.ShoppingList list) implements DialogState {}

    /** {@code /family}: правим имя участника — ждём его текстом. */
    record AwaitingMemberName(long memberId) implements DialogState {}

    /** {@code /series}: нажали «До какого числа» — ждём дату текстом. */
    record AwaitingSeriesEnd(long seriesId) implements DialogState {}

    /**
     * Нажали «Не могу» — ждём причину текстом.
     *
     * <p>Список запоминается, чтобы после отказа вернуть человека туда, откуда он пришёл.
     */
    record AwaitingDeclineReason(long taskId, com.familytodo.adapter.telegram.view.TaskListView.Kind kind)
            implements DialogState {}

    /** Правка: ждём новое название текстом. */
    record AwaitingNewTitle(long taskId, com.familytodo.adapter.telegram.view.TaskListView.Kind kind)
            implements DialogState {}

    /**
     * Правка срока или исполнителя: выбор придёт кнопкой.
     *
     * <p>Какую задачу правим, приходится держать здесь: формат {@code prefix:action:argument} даёт
     * ровно одно поле, а нужно и id задачи, и выбранное значение.
     */
    record EditingTask(long taskId, com.familytodo.adapter.telegram.view.TaskListView.Kind kind)
            implements DialogState {}

    /** Правка: ждём «когда и где» текстом. */
    record AwaitingSlot(long taskId, com.familytodo.adapter.telegram.view.TaskListView.Kind kind)
            implements DialogState {}

    /** Правка: выбрали «Своя дата» — ждём её текстом. */
    record AwaitingNewCustomDue(
            long taskId, com.familytodo.adapter.telegram.view.TaskListView.Kind kind)
            implements DialogState {}
}

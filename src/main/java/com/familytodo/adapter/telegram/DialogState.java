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

    /** {@code /new}: текст есть, ждём выбора исполнителя кнопкой. */
    record AwaitingAssignee(String title) implements DialogState {}

    /** {@code /new}: исполнитель выбран, ждём срока кнопкой. */
    record AwaitingDueDate(String title, long assigneeId) implements DialogState {}

    /**
     * {@code /new}: срок выбран, спрашиваем про повторение.
     *
     * <p>Срок несём с собой: время серии — это время выбранного срока, спрашивать его отдельно
     * значило бы добавить шаг ради того, что человек только что назвал.
     */
    record AwaitingRepeat(
            String title, long assigneeId, com.familytodo.application.DueDateParser.Plan plan)
            implements DialogState {}

    /** {@code /new}: выбрали «Свои дни» — копим отмеченные, пока не нажмут «Готово». */
    record ChoosingDays(
            String title,
            long assigneeId,
            com.familytodo.application.DueDateParser.Plan plan,
            java.util.Set<java.time.DayOfWeek> days)
            implements DialogState {

        public ChoosingDays {
            days = java.util.Set.copyOf(days);
        }
    }

    /** {@code /new}: выбрали «Своя дата» — ждём её текстом. */
    record AwaitingCustomDueDate(String title, long assigneeId) implements DialogState {}

    /**
     * {@code /shop}: нажали «Добавить» — ждём позиции текстом, по строке на позицию.
     *
     * <p>Список несём с собой, потому что позиции обязаны попасть туда, откуда нажали кнопку.
     */
    record AwaitingShoppingItems(com.familytodo.domain.ShoppingList list) implements DialogState {}

    /** {@code /family}: правим имя участника — ждём его текстом. */
    record AwaitingMemberName(long memberId) implements DialogState {}

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

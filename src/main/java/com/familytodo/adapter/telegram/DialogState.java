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
}

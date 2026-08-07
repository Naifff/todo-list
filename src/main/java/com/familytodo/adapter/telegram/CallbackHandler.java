package com.familytodo.adapter.telegram;

public interface CallbackHandler {

    /** Префикс {@code callback_data}, который обслуживает этот обработчик: {@code t}, {@code n}. */
    String prefix();

    void handle(BotRequest request, CallbackData data);
}

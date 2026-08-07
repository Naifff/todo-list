package com.familytodo.adapter.telegram;

public interface CallbackHandler {

    /** Префикс {@code callback_data}, который обслуживает этот обработчик: {@code t}, {@code n}. */
    String prefix();

    void handle(BotRequest request, CallbackData data);

    /**
     * Можно ли нажимать эти кнопки тому, кто ещё не в семье.
     *
     * <p>По умолчанию нельзя. Исключение — онбординг: выбор часового пояса происходит до того, как
     * семья вообще создана, то есть человек в этот момент по определению чужой.
     */
    default boolean allowsStrangers() {
        return false;
    }
}

package com.familytodo.adapter.telegram;

import java.util.Set;

public interface CommandHandler {

    /** Команды без слэша: {@code start}, {@code new}, {@code my}. */
    Set<String> commands();

    void handle(BotRequest request);

    /**
     * Можно ли выполнять команду тому, кто ещё не в семье.
     *
     * <p>По умолчанию нельзя. Исключение ровно одно — {@code /start}: через него и создают семью, и
     * принимают приглашение, так что до него человек по определению чужой.
     */
    default boolean allowsStrangers() {
        return false;
    }
}

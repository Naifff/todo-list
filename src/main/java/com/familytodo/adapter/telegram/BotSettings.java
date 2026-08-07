package com.familytodo.adapter.telegram;

import java.util.regex.Pattern;

/**
 * Токен и имя бота, проверенные при сборке контекста.
 *
 * <p>Проверка нужна ровно затем, чтобы приложение не поднялось с негодными настройками. Пустой
 * токен — не гипотеза: в {@code .env.example} переменные оставлены незаполненными, и скопированный
 * файл даёт пустую строку, а не отсутствующее значение. Приложение с ней стартует, опрос получает
 * от Telegram 404, и в журнале это выглядит перебоями сети.
 *
 * <p>Ни одно сообщение об ошибке не содержит самих значений: они уходят в journald, а токен —
 * секрет, который после утечки отзывают через BotFather.
 */
public record BotSettings(String token, String username) {

    /** {@code <id>:<секрет>} — формат BotFather. Пробелов нет: обрезанная при копировании строка тоже ловится. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("\\d+:\\S+");

    public static BotSettings of(String token, String username) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "BOT_TOKEN is not set: put the token from BotFather into the environment");
        }
        if (!TOKEN_SHAPE.matcher(token).matches()) {
            throw new IllegalStateException("BOT_TOKEN is malformed: expected <id>:<secret> without spaces");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("BOT_USERNAME is not set: needed to build t.me invite links");
        }
        if (username.contains("@")) {
            throw new IllegalStateException("BOT_USERNAME must be given without @");
        }
        return new BotSettings(token, username);
    }
}

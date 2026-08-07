package com.familytodo.adapter.telegram;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Полезная нагрузка кнопки: {@code <префикс>:<действие>:<аргумент>}, например {@code t:done:1234}.
 *
 * <p><b>Это недоверенный ввод.</b> Строка приходит от клиента и может быть какой угодно: её легко
 * подделать, а идентификатор — подобрать. Кнопки отражают права, но не обеспечивают их, поэтому
 * разбор строгий, а каждый обработчик заново грузит объект и проверяет актора.
 *
 * <p>{@code family_id} внутрь не кладём: он всё равно был бы подделан вместе с остальным. Семья
 * берётся из отправителя апдейта.
 */
public record CallbackData(String prefix, String action, String argument) {

    /** Жёсткий предел Telegram — 64 байта. Строка длиннее просто не дойдёт до клиента. */
    public static final int MAX_BYTES = 64;

    private static final Pattern NAME = Pattern.compile("[a-z]{1,8}");
    private static final Pattern ARGUMENT = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public CallbackData {
        require(NAME, prefix, "prefix");
        require(NAME, action, "action");
        require(ARGUMENT, argument, "argument");

        String serialized = prefix + ':' + action + ':' + argument;
        int bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException("callback data is " + bytes + " bytes, limit is 64");
        }
    }

    public static CallbackData of(String prefix, String action, long id) {
        return new CallbackData(prefix, action, Long.toString(id));
    }

    /** Разбор недоверенной строки. На любой мусор — {@link IllegalArgumentException}. */
    public static CallbackData parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("callback data is missing");
        }
        String[] parts = raw.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("callback data must have exactly three parts");
        }
        return new CallbackData(parts[0], parts[1], parts[2]);
    }

    public String serialize() {
        return prefix + ':' + action + ':' + argument;
    }

    public long longArgument() {
        try {
            return Long.parseLong(argument);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("callback argument is not a number", e);
        }
    }

    private static void require(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("callback " + field + " is malformed");
        }
    }
}

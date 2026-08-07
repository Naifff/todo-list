package com.familytodo.domain;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Код приглашения: 128 бит из {@link SecureRandom} в base64url без выравнивания — 22 символа.
 *
 * <p>Выбор против {@code UUID.randomUUID()} сделан из-за длины ссылки (22 символа против 36) и
 * алфавита, а не стойкости: UUID версии 4 тоже основан на {@code SecureRandom} и слабым не
 * является. Алфавит важен потому, что код едет в параметре {@code start} диплинка Telegram,
 * который допускает только {@code A-Za-z0-9_-}.
 */
public final class InviteCodeGenerator {

    private static final int ENTROPY_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[ENTROPY_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}

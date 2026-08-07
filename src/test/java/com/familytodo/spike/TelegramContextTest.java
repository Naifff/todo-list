package com.familytodo.spike;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Вторая половина спайка: контекст Spring Boot 4.1 поднимается вместе с бинами telegrambots.
 *
 * <p>Опрос выключен намеренно. Без {@code telegram.bot.polling.enabled=false} этот тест начал бы
 * реальный long polling к api.telegram.org с фальшивым токеном — сеть в юнит-прогоне.
 */
@SpringBootTest(
        properties = {
            "telegram.bot.token=1:spike-token",
            "telegram.bot.username=spike_bot",
            "telegram.bot.polling.enabled=false",
            // база в памяти: юнит-прогон не должен оставлять файлов в репозитории
            "spring.datasource.url=jdbc:sqlite::memory:?foreign_keys=true"
        })
class TelegramContextTest {

    @Autowired private ApplicationContext context;

    @Autowired private TelegramClient telegramClient;

    @Test
    void contextStartsWithTelegramClientBean() {
        assertThat(telegramClient).isInstanceOf(OkHttpTelegramClient.class);
    }

    /** Структурная гарантия «не слушаем портов»: long polling — только исходящие соединения. */
    @Test
    void contextIsNotWebApplicationContext() {
        assertThat(context.getClass().getName()).doesNotContain("Web");
    }
}

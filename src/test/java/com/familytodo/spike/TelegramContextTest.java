package com.familytodo.spike;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Вторая половина спайка: контекст Spring Boot 4.1 поднимается вместе с бином telegrambots.
 *
 * <p>Бин объявлен здесь, а не в {@code config/}, намеренно — настоящий {@code TelegramConfig}
 * появится в задаче 11. Спайк не должен оставлять после себя продовый код.
 */
@SpringBootTest(
        properties = {
            "telegram.bot.token=spike-token",
            "telegram.bot.username=spike_bot",
            // база в памяти: юнит-прогон не должен оставлять файлов в репозитории
            "spring.datasource.url=jdbc:sqlite::memory:?foreign_keys=true"
        })
class TelegramContextTest {

    @TestConfiguration
    static class SpikeTelegramConfig {
        @Bean
        TelegramClient telegramClient() {
            return new OkHttpTelegramClient("spike-token");
        }
    }

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

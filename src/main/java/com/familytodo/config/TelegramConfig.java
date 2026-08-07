package com.familytodo.config;

import com.familytodo.adapter.telegram.BotSettings;
import com.familytodo.adapter.telegram.GuardedPollingExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Бины telegrambots собираются вручную: спринговый стартер библиотеки выпущен до Spring Boot 4 и
 * под него не тестировался.
 *
 * <p>Токен приходит только из окружения. Если он окажется в git, переписывать историю бесполезно —
 * отзывать через BotFather.
 */
@Configuration
public class TelegramConfig {

    /**
     * Значения по умолчанию пустые намеренно: без них незаданная переменная даёт ошибку разрешения
     * плейсхолдера, а заполненная пустой строкой не даёт ничего. Обе ошибки — одна и та же, и
     * отвечать на них должно одно понятное сообщение из {@link BotSettings}.
     */
    @Bean
    public BotSettings botSettings(
            @Value("${telegram.bot.token:}") String token,
            @Value("${telegram.bot.username:}") String username) {
        return BotSettings.of(token, username);
    }

    @Bean
    public TelegramClient telegramClient(BotSettings settings) {
        return new OkHttpTelegramClient(settings.token());
    }

    /**
     * Свой планировщик вместо умолчательного: библиотека ставит опрос периодической задачей, а такая
     * задача снимается с расписания навсегда от первого же неожиданного исключения — молча.
     */
    @Bean
    public TelegramBotsLongPollingApplication longPollingApplication() {
        return new TelegramBotsLongPollingApplication(
                ObjectMapper::new,
                new TelegramOkHttpClientFactory.DefaultOkHttpClientCreator(),
                GuardedPollingExecutor::new);
    }
}

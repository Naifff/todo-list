package com.familytodo.adapter.telegram;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Запуск цикла long polling.
 *
 * <p>Порты не открываются: приложение только устанавливает исходящие соединения. Это же и есть
 * причина, по которой не выбран webhook — у VPS нет домена, а поднимать TLS рядом с чужим VPN
 * значило бы трогать сеть на машине, которую нельзя ронять.
 *
 * <p>Регистрация своим бином, без спрингового стартера telegrambots: тот выпущен до Spring Boot 4
 * и под него не тестировался.
 *
 * <p>Отключается свойством {@code telegram.bot.polling.enabled=false}. Это не удобство, а защита:
 * без него любой тест, поднимающий контекст, начинал бы реальный опрос api.telegram.org.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
public class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    private final TelegramBotsLongPollingApplication application;
    private final UpdateRouter router;
    private final String token;

    public BotRunner(
            TelegramBotsLongPollingApplication application,
            UpdateRouter router,
            BotSettings settings) {
        this.application = application;
        this.router = router;
        this.token = settings.token();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        application.registerBot(token, (LongPollingSingleThreadUpdateConsumer) this::consume);
        // единственный признак живости: health-эндпоинта нет, потому что порт не открывается
        log.info("long polling started");
    }

    private void consume(Update update) {
        try {
            router.route(update);
        } catch (RuntimeException e) {
            // цикл опроса не должен умирать от ошибки в обработке одного апдейта:
            // JVM осталась бы жива, systemd видел бы здоровый процесс, а бот молчал
            log.error("update {} dropped", update.getUpdateId(), e);
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        application.stop();
        log.info("long polling stopped");
    }
}

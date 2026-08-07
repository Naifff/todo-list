package com.familytodo.adapter.telegram;

import java.io.Serializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Отправка сообщений. Хендлеры не держат клиента Telegram напрямую — им нужен один способ ответить,
 * а не весь Bot API.
 *
 * <p>Текст приходит уже готовой разметкой: экранирует вьюха, которая вставляет пользовательский
 * текст. Экранирование здесь разрушало бы собранный HTML.
 */
@Component
public class BotSender {

    private static final Logger log = LoggerFactory.getLogger(BotSender.class);

    private final TelegramClient client;

    public BotSender(TelegramClient client) {
        this.client = client;
    }

    public void send(long chatId, String html) {
        send(chatId, html, null);
    }

    public void send(long chatId, String html, InlineKeyboardMarkup markup) {
        execute(
                SendMessage.builder()
                        .chatId(chatId)
                        .text(html)
                        .parseMode("HTML")
                        .replyMarkup(markup)
                        .build());
    }

    /** Списки живут одним сообщением: нажатие кнопки переписывает его, а не плодит новые. */
    public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
        execute(
                EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(html)
                        .parseMode("HTML")
                        .replyMarkup(markup)
                        .build());
    }

    public void answerCallback(String callbackQueryId, String notice) {
        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId);
        if (notice != null) {
            builder.text(notice);
        }
        execute(builder.build());
    }

    private <T extends Serializable, M extends BotApiMethod<T>> void execute(M method) {
        try {
            client.execute(method);
        } catch (TelegramApiException e) {
            // текст ошибки может содержать сообщение пользователя — в лог только класс
            log.warn("telegram call failed: {}", e.getClass().getSimpleName());
        }
    }
}

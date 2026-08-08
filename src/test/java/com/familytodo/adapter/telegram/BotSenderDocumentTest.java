package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Расписание страницей уходит документом — в отличие от картинки.
 *
 * <p>Разница не в капризе: фотографию Telegram показывает прямо в ленте, и документ там был бы
 * карточкой файла. С HTML наоборот — показывать в ленте нечего, его надо открыть, а для этого он и
 * должен приехать файлом.
 */
class BotSenderDocumentTest {

    private static final byte[] HTML =
            "<!doctype html><html></html>".getBytes(StandardCharsets.UTF_8);

    private final TelegramClient client = mock(TelegramClient.class);
    private final BotSender sender = new BotSender(client);

    @Test
    void documentIsSentWithItsFileNameAndCaption() throws TelegramApiException {
        sender.sendDocument(4426L, HTML, "schedule-2026-08-08.html", "Расписание на 7 дней");

        ArgumentCaptor<SendDocument> sent = ArgumentCaptor.forClass(SendDocument.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getChatId()).isEqualTo("4426");
        assertThat(sent.getValue().getCaption()).isEqualTo("Расписание на 7 дней");
        assertThat(sent.getValue().getDocument().getMediaName())
                .isEqualTo("schedule-2026-08-08.html");
    }

    /** Подпись размечена так же, как остальные сообщения бота. */
    @Test
    void captionUsesHtml() throws TelegramApiException {
        sender.sendDocument(4426L, HTML, "schedule.html", "<b>Неделя</b>");

        ArgumentCaptor<SendDocument> sent = ArgumentCaptor.forClass(SendDocument.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getParseMode()).isEqualTo("HTML");
    }

    @Test
    void blockedRecipientIsReportedAsUnreachable() throws TelegramApiException {
        when(client.execute(any(SendDocument.class)))
                .thenThrow(new TelegramApiRequestException("forbidden", apiError(403)));

        assertThat(sender.sendDocument(4426L, HTML, "schedule.html", "неважно")).isFalse();
    }

    /** Сеть моргнула — не повод считать получателя недостижимым навсегда. */
    @Test
    void aTemporaryFailureIsNotUnreachable() throws TelegramApiException {
        when(client.execute(any(SendDocument.class)))
                .thenThrow(new TelegramApiRequestException("bad gateway", apiError(502)));

        assertThat(sender.sendDocument(4426L, HTML, "schedule.html", "неважно")).isTrue();
    }

    private static ApiResponse<?> apiError(int code) {
        return ApiResponse.builder().ok(false).errorCode(code).errorDescription("—").build();
    }
}

package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Календарь уходит фотографией, а не документом.
 *
 * <p>Документ в Telegram показывается карточкой файла: чтобы посмотреть картинку, её надо скачать.
 * Обзорный режим, ради которого всё и рисовалось, при этом теряет смысл.
 */
class BotSenderPhotoTest {

    private static final byte[] PNG = "не-настоящий-png".getBytes(StandardCharsets.UTF_8);

    private final TelegramClient client = mock(TelegramClient.class);
    private final BotSender sender = new BotSender(client);

    @Test
    void photoIsSentAsAPhotoWithACaption() throws TelegramApiException {
        sender.sendPhoto(4426L, PNG, "calendar.png", "Расписание на 7 дней");

        ArgumentCaptor<SendPhoto> sent = ArgumentCaptor.forClass(SendPhoto.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getChatId()).isEqualTo("4426");
        assertThat(sent.getValue().getCaption()).isEqualTo("Расписание на 7 дней");
        assertThat(sent.getValue().getPhoto().getMediaName()).isEqualTo("calendar.png");
    }

    /** Подпись размечена так же, как остальные сообщения бота. */
    @Test
    void captionUsesHtml() throws TelegramApiException {
        sender.sendPhoto(4426L, PNG, "calendar.png", "<b>Неделя</b>");

        ArgumentCaptor<SendPhoto> sent = ArgumentCaptor.forClass(SendPhoto.class);
        verify(client).execute(sent.capture());
        assertThat(sent.getValue().getParseMode()).isEqualTo("HTML");
    }

    @Test
    void blockedRecipientIsReportedAsUnreachable() throws TelegramApiException {
        when(client.execute(any(SendPhoto.class)))
                .thenThrow(new TelegramApiRequestException("forbidden", apiError(403)));

        assertThat(sender.sendPhoto(4426L, PNG, "calendar.png", "неважно")).isFalse();
    }

    /** Сеть моргнула — не повод считать получателя недостижимым навсегда. */
    @Test
    void transientFailureIsNotTreatedAsBlocked() throws TelegramApiException {
        when(client.execute(any(SendPhoto.class)))
                .thenThrow(new TelegramApiRequestException("bad gateway", apiError(502)));

        assertThat(sender.sendPhoto(4426L, PNG, "calendar.png", "неважно")).isTrue();
    }

    private static ApiResponse<?> apiError(int code) {
        return ApiResponse.builder().ok(false).errorCode(code).errorDescription("—").build();
    }
}

package com.familytodo.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Спайк: снимаем главный технический риск до написания домена.
 *
 * <p>Spring Boot 4.1 несёт Jackson 3 ({@code tools.jackson}), telegrambots 10.2.0 собран против
 * Jackson 2.17.2 ({@code com.fasterxml}). Поколения сосуществуют штатно, поэтому дубли в дереве
 * зависимостей ничего не доказывают — доказывает только реальный round-trip.
 *
 * <p>Критерий прохождения: все тесты этого класса и {@link TelegramContextTest} зелёные → риск
 * снят. Любой падает → откат на Spring Boot 3.5.x с записью причины в план.
 */
class TelegramCompatibilityTest {

    // тот же способ, каким библиотека создаёт маппер по умолчанию
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesTextMessageUpdate() throws Exception {
        Update update = mapper.readValue(fixture("update-message.json"), Update.class);

        assertThat(update.getUpdateId()).isEqualTo(704253901);
        assertThat(update.hasMessage()).isTrue();
        assertThat(update.getMessage().getText()).isEqualTo("/new Купить хлеб");
        assertThat(update.getMessage().getChatId()).isEqualTo(512034877L);
        assertThat(update.getMessage().getFrom().getId()).isEqualTo(512034877L);
        assertThat(update.getMessage().isUserMessage()).isTrue();
    }

    @Test
    void deserializesCallbackQueryUpdate() throws Exception {
        Update update = mapper.readValue(fixture("update-callback.json"), Update.class);

        assertThat(update.hasCallbackQuery()).isTrue();
        assertThat(update.getCallbackQuery().getData()).isEqualTo("t:done:1234");
        assertThat(update.getCallbackQuery().getFrom().getId()).isEqualTo(512034877L);
        assertThat(update.getCallbackQuery().getId()).isEqualTo("4382908134712345678");
    }

    @Test
    void serializesSendMessageWithInlineKeyboard() throws Exception {
        SendMessage message =
                SendMessage.builder()
                        .chatId(512034877L)
                        .text("Купить хлеб")
                        .parseMode("HTML")
                        .replyMarkup(
                                InlineKeyboardMarkup.builder()
                                        .keyboardRow(
                                                new InlineKeyboardRow(
                                                        InlineKeyboardButton.builder()
                                                                .text("Готово")
                                                                .callbackData("t:done:1234")
                                                                .build()))
                                        .build())
                        .build();

        JsonNode json = mapper.valueToTree(message);

        assertThat(json.path("chat_id").asText()).isEqualTo("512034877");
        assertThat(json.path("text").asText()).isEqualTo("Купить хлеб");
        assertThat(json.path("parse_mode").asText()).isEqualTo("HTML");

        JsonNode button = json.path("reply_markup").path("inline_keyboard").get(0).get(0);
        assertThat(button.path("text").asText()).isEqualTo("Готово");
        assertThat(button.path("callback_data").asText()).isEqualTo("t:done:1234");
    }

    /** Обратный round-trip: то, что мы отправляем, Telegram присылает обратно в том же виде. */
    @Test
    void inlineKeyboardSurvivesRoundTrip() throws Exception {
        InlineKeyboardMarkup original =
                InlineKeyboardMarkup.builder()
                        .keyboardRow(
                                new InlineKeyboardRow(
                                        InlineKeyboardButton.builder()
                                                .text("Не могу")
                                                .callbackData("t:decline:1234")
                                                .build()))
                        .build();

        String json = mapper.writeValueAsString(original);
        InlineKeyboardMarkup parsed = mapper.readValue(json, InlineKeyboardMarkup.class);

        List<InlineKeyboardRow> rows = parsed.getKeyboard();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(0).getCallbackData()).isEqualTo("t:decline:1234");
    }

    private InputStream fixture(String name) {
        InputStream stream = getClass().getResourceAsStream("/fixtures/" + name);
        assertThat(stream).as("фикстура %s", name).isNotNull();
        return stream;
    }
}

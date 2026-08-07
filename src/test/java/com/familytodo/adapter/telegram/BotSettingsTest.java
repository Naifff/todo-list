package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Настройки бота проверяются при сборке контекста, а не при первом обращении к Telegram.
 *
 * <p>Реальный способ ошибиться — скопировать {@code .env.example} и не заполнить его: значение
 * подставится пустой строкой, приложение поднимется, опрос начнёт получать 404, и по журналу это
 * будет выглядеть сетевым сбоем. Пустое имя бота ещё тише: ссылки-приглашения соберутся, но никуда
 * не приведут.
 */
class BotSettingsTest {

    private static final String TOKEN = "8840956753:AAG-fake-secret-value";
    private static final String USERNAME = "FamilyTODO_bot";

    @Test
    void validSettingsPassThrough() {
        BotSettings settings = BotSettings.of(TOKEN, USERNAME);

        assertThat(settings.token()).isEqualTo(TOKEN);
        assertThat(settings.username()).isEqualTo(USERNAME);
    }

    @Nested
    class Token {

        @Test
        void emptyTokenFailsNamingTheVariable() {
            assertThatThrownBy(() -> BotSettings.of("", USERNAME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_TOKEN");
        }

        @Test
        void blankTokenFails() {
            assertThatThrownBy(() -> BotSettings.of("   ", USERNAME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_TOKEN");
        }

        /** Обрезанная при копировании строка — тоже частый случай, и он должен ловиться сразу. */
        @Test
        void tokenWithoutTheIdPartFails() {
            assertThatThrownBy(() -> BotSettings.of("AAG-secret-without-id", USERNAME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_TOKEN");
        }

        @Test
        void tokenWithSpacesFails() {
            assertThatThrownBy(() -> BotSettings.of("8840956753: AAG-secret", USERNAME))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_TOKEN");
        }

        /** Сообщение об ошибке уходит в журнал целиком — значения в нём быть не должно. */
        @Test
        void errorNeverQuotesTheTokenItself() {
            assertThatThrownBy(() -> BotSettings.of("8840956753", USERNAME))
                    .hasMessageNotContaining("8840956753");
        }
    }

    @Nested
    class Username {

        @Test
        void emptyUsernameFailsNamingTheVariable() {
            assertThatThrownBy(() -> BotSettings.of(TOKEN, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_USERNAME");
        }

        /** С {@code @} ссылка t.me/@bot?start=... собирается, но никуда не ведёт. */
        @Test
        void usernameWithAtSignFails() {
            assertThatThrownBy(() -> BotSettings.of(TOKEN, "@FamilyTODO_bot"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOT_USERNAME");
        }
    }
}

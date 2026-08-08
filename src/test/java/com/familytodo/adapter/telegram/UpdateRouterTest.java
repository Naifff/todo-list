package com.familytodo.adapter.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.fake.InMemoryMemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.MemberStatus;
import com.familytodo.domain.Role;
import com.familytodo.domain.TaskStatus;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Роутер проверяется на готовых объектах {@code Update} и моке клиента — ни одного обращения к
 * Telegram.
 */
class UpdateRouterTest {

    private static final long FAMILY = 1L;
    private static final long MEMBER_TELEGRAM_ID = 100000001L;
    private static final long STRANGER_TELEGRAM_ID = 999999L;
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");

    private final InMemoryMemberRepository members = new InMemoryMemberRepository();
    private final TelegramClient client = mock(TelegramClient.class);

    private RecordingCommandHandler myCommand;
    private StrangerFriendlyHandler startCommand;
    private RecordingCallbackHandler taskCallbacks;
    private RecordingDialogHandler dialog;

    @BeforeEach
    void setUp() {
        members.save(
                Member.join(10L, FAMILY, MEMBER_TELEGRAM_ID, 100L, "Мама", Role.PARENT, NOW));
        myCommand = new RecordingCommandHandler(Set.of("my"));
        startCommand = new StrangerFriendlyHandler();
        taskCallbacks = new RecordingCallbackHandler();
        dialog = new RecordingDialogHandler();
    }

    /**
     * ⚠️ Обработчики раскладываются по map, и совпавший ключ молча вытеснил бы предыдущий: команда
     * или целый экран переставали бы работать без единой строки в журнале и без ошибки сборки.
     * Проверка стоит копейки, а находка — только по жалобе «кнопка ничего не делает».
     */
    @Nested
    class ClashingHandlers {

        @Test
        void twoHandlersOnTheSameCallbackPrefixRefuseToStart() {
            assertThatThrownBy(
                            () ->
                                    new UpdateRouter(
                                            members,
                                            new BotSender(client),
                                            List.of(),
                                            List.of(taskCallbacks, new SamePrefixHandler()),
                                            List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void twoHandlersOnTheSameCommandRefuseToStart() {
            assertThatThrownBy(
                            () ->
                                    new UpdateRouter(
                                            members,
                                            new BotSender(client),
                                            List.of(myCommand, new SameCommandHandler()),
                                            List.of(),
                                            List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        private final class SamePrefixHandler implements CallbackHandler {
            @Override
            public String prefix() {
                return taskCallbacks.prefix();
            }

            @Override
            public void handle(BotRequest request, CallbackData data) {}
        }

        private final class SameCommandHandler implements CommandHandler {
            @Override
            public java.util.Set<String> commands() {
                return myCommand.commands();
            }

            @Override
            public void handle(BotRequest request) {}
        }
    }

    private UpdateRouter router() {
        return new UpdateRouter(
                members,
                new BotSender(client),
                List.of(myCommand, startCommand),
                List.of(taskCallbacks),
                List.of(dialog));
    }

    @Nested
    class Strangers {

        /** Незнакомцу — один и тот же ответ и ничего больше. */
        @Test
        void getNothingButTheInvitationHint() throws Exception {
            router().route(message(STRANGER_TELEGRAM_ID, "/my"));

            assertThat(sentTexts()).containsExactly(Texts.STRANGER);
            assertThat(myCommand.calls).isZero();
        }

        @Test
        void plainTextFromStrangerRevealsNothingEither() throws Exception {
            router().route(message(STRANGER_TELEGRAM_ID, "привет, что ты умеешь?"));

            assertThat(sentTexts()).containsExactly(Texts.STRANGER);
        }

        /** {@code /start} — единственное исключение: через него и создают семью, и входят по коду. */
        @Test
        void mayRunStart() {
            router().route(message(STRANGER_TELEGRAM_ID, "/start inv_КОД"));

            assertThat(startCommand.calls).isEqualTo(1);
            assertThat(startCommand.lastArgument).isEqualTo("inv_КОД");
        }

        @Test
        void removedMemberIsTreatedAsStranger() throws Exception {
            members.save(
                    Member.restore(
                            10L,
                            FAMILY,
                            MEMBER_TELEGRAM_ID,
                            100L,
                            "Мама",
                            Role.PARENT,
                            MemberStatus.REMOVED,
                            false,
                            NOW));

            router().route(message(MEMBER_TELEGRAM_ID, "/my"));

            assertThat(sentTexts()).containsExactly(Texts.STRANGER);
            assertThat(myCommand.calls).isZero();
        }
    }

    @Nested
    class Commands {

        @Test
        void dispatchesKnownCommand() {
            router().route(message(MEMBER_TELEGRAM_ID, "/my"));

            assertThat(myCommand.calls).isEqualTo(1);
        }

        /** В группах Telegram дописывает к команде имя бота — в личке тоже встречается. */
        @Test
        void stripsBotSuffixFromCommand() {
            router().route(message(MEMBER_TELEGRAM_ID, "/my@FamilyTODO_bot"));

            assertThat(myCommand.calls).isEqualTo(1);
        }

        @Test
        void answersUnknownCommand() throws Exception {
            router().route(message(MEMBER_TELEGRAM_ID, "/такойнет"));

            assertThat(sentTexts()).containsExactly(Texts.UNKNOWN_COMMAND);
        }

        @Test
        void domainExceptionBecomesUserText() throws Exception {
            myCommand.failWith = new DomainException.NotPermitted("nope");

            router().route(message(MEMBER_TELEGRAM_ID, "/my"));

            assertThat(sentTexts()).containsExactly(Texts.NOT_PERMITTED);
        }

        /** Наружу не должно всплывать ничего: пользователь получает текст, а не молчание. */
        @Test
        void unexpectedExceptionBecomesUserText() throws Exception {
            myCommand.failWith = new IllegalStateException("boom");

            router().route(message(MEMBER_TELEGRAM_ID, "/my"));

            assertThat(sentTexts()).containsExactly(Texts.INTERNAL_ERROR);
        }
    }

    @Nested
    class Callbacks {

        @Test
        void dispatchesByPrefix() {
            router().route(callback(MEMBER_TELEGRAM_ID, "t:done:1234"));

            assertThat(taskCallbacks.calls).isEqualTo(1);
            assertThat(taskCallbacks.lastData.longArgument()).isEqualTo(1234L);
        }

        /** Без ответа на кнопке навсегда остаётся крутилка — зависший интерфейс вместо ошибки. */
        @Test
        void alwaysAnswersOnSuccess() throws Exception {
            router().route(callback(MEMBER_TELEGRAM_ID, "t:done:1234"));

            assertThat(answers()).hasSize(1);
        }

        @Test
        void alwaysAnswersOnDomainError() throws Exception {
            taskCallbacks.failWith =
                    new DomainException.InvalidTransition(TaskStatus.DONE, "already");

            router().route(callback(MEMBER_TELEGRAM_ID, "t:done:1234"));

            assertThat(answers()).extracting(AnswerCallbackQuery::getText)
                    .containsExactly(Texts.ALREADY_DONE);
        }

        @Test
        void alwaysAnswersOnUnexpectedError() throws Exception {
            taskCallbacks.failWith = new IllegalStateException("boom");

            router().route(callback(MEMBER_TELEGRAM_ID, "t:done:1234"));

            assertThat(answers()).extracting(AnswerCallbackQuery::getText)
                    .containsExactly(Texts.INTERNAL_ERROR);
        }

        /** Подделанная строка — ожидаемый случай, а не сбой: обработчик до неё не доходит. */
        @Test
        void answersOnMalformedData() throws Exception {
            router().route(callback(MEMBER_TELEGRAM_ID, "мусор"));

            assertThat(answers()).hasSize(1);
            assertThat(taskCallbacks.calls).isZero();
        }

        @Test
        void answersWhenNoHandlerMatchesPrefix() throws Exception {
            router().route(callback(MEMBER_TELEGRAM_ID, "z:done:1"));

            assertThat(answers()).hasSize(1);
            assertThat(taskCallbacks.calls).isZero();
        }

        @Test
        void strangerPressingAButtonLearnsNothing() throws Exception {
            router().route(callback(STRANGER_TELEGRAM_ID, "t:done:1234"));

            assertThat(answers()).extracting(AnswerCallbackQuery::getText)
                    .containsExactly(Texts.STRANGER);
            assertThat(taskCallbacks.calls).isZero();
        }
    }

    @Test
    void ignoresUpdatesWithoutMessageOrCallback() {
        router().route(new Update());

        verifyNoInteractions(client);
    }

    // --- сборка апдейтов и разбор отправленного ---

    private static Update message(long userId, String text) {
        User from = new User(userId, "Кто-то", false);
        Chat chat = new Chat(userId, "private");
        Message message = new Message();
        message.setMessageId(1);
        message.setFrom(from);
        message.setChat(chat);
        message.setText(text);

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }

    private static Update callback(long userId, String data) {
        User from = new User(userId, "Кто-то", false);
        Chat chat = new Chat(userId, "private");
        Message message = new Message();
        message.setMessageId(2);
        message.setChat(chat);

        CallbackQuery query = new CallbackQuery();
        query.setId("cb-1");
        query.setFrom(from);
        query.setMessage(message);
        query.setData(data);

        Update update = new Update();
        update.setUpdateId(2);
        update.setCallbackQuery(query);
        return update;
    }

    private List<String> sentTexts() throws TelegramApiException {
        return sent(SendMessage.class).stream().map(SendMessage::getText).toList();
    }

    private List<AnswerCallbackQuery> answers() throws TelegramApiException {
        return sent(AnswerCallbackQuery.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> List<T> sent(Class<T> type) throws TelegramApiException {
        ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(client, org.mockito.Mockito.atLeast(0)).execute(captor.capture());
        List<T> found = new ArrayList<>();
        for (Object value : captor.getAllValues()) {
            if (type.isInstance(value)) {
                found.add((T) value);
            }
        }
        return found;
    }

    // --- заглушки обработчиков ---

    private static class RecordingCommandHandler implements CommandHandler {
        private final Set<String> commands;
        int calls;
        RuntimeException failWith;

        RecordingCommandHandler(Set<String> commands) {
            this.commands = commands;
        }

        @Override
        public Set<String> commands() {
            return commands;
        }

        @Override
        public void handle(BotRequest request) {
            calls++;
            if (failWith != null) {
                throw failWith;
            }
        }
    }

    private static final class StrangerFriendlyHandler implements CommandHandler {
        int calls;
        String lastArgument;

        @Override
        public Set<String> commands() {
            return Set.of("start");
        }

        @Override
        public boolean allowsStrangers() {
            return true;
        }

        @Override
        public void handle(BotRequest request) {
            calls++;
            lastArgument = request.commandArgument().orElse(null);
        }
    }

    private static final class RecordingDialogHandler implements DialogHandler {
        boolean claims;
        int calls;

        @Override
        public boolean continueDialog(BotRequest request) {
            calls++;
            return claims;
        }
    }

    private static final class RecordingCallbackHandler implements CallbackHandler {
        int calls;
        CallbackData lastData;
        RuntimeException failWith;

        @Override
        public String prefix() {
            return "t";
        }

        @Override
        public void handle(BotRequest request, CallbackData data) {
            calls++;
            lastData = data;
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}

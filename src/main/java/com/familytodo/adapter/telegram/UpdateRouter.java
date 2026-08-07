package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.TaskStatus;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Единственная точка входа апдейтов.
 *
 * <p>Три обязательства, каждое проверено тестом:
 *
 * <ul>
 *   <li>незнакомцу уходит один и тот же ответ и ничего больше;
 *   <li>{@code answerCallbackQuery} вызывается <b>всегда</b> — иначе на кнопке навсегда остаётся
 *       крутилка, и пользователь видит зависший интерфейс вместо ошибки;
 *   <li>доменное исключение наружу не всплывает, а превращается в текст.
 * </ul>
 *
 * <p>В логи попадают только идентификаторы: ни названий задач, ни имён, ни причин отказа.
 */
@Component
public class UpdateRouter {

    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final MemberRepository members;
    private final TelegramClient client;
    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Map<String, CallbackHandler> callbacks = new HashMap<>();

    public UpdateRouter(
            MemberRepository members,
            TelegramClient client,
            List<CommandHandler> commandHandlers,
            List<CallbackHandler> callbackHandlers) {
        this.members = members;
        this.client = client;
        commandHandlers.forEach(
                handler -> handler.commands().forEach(name -> commands.put(name, handler)));
        callbackHandlers.forEach(handler -> callbacks.put(handler.prefix(), handler));
    }

    public void route(Update update) {
        if (update.hasCallbackQuery()) {
            routeCallback(update.getCallbackQuery());
        } else if (update.hasMessage() && update.getMessage().isUserMessage()) {
            routeMessage(update.getMessage());
        }
    }

    private void routeMessage(Message message) {
        BotRequest request = parse(message);
        try {
            dispatch(request);
        } catch (DomainException e) {
            reply(request.chatId(), userText(e));
        } catch (RuntimeException e) {
            log.error("failed to handle message from user {}", request.telegramUserId(), e);
            reply(request.chatId(), Texts.INTERNAL_ERROR);
        }
    }

    /**
     * Ответ на кнопку идёт в {@code finally}: Telegram держит крутилку до подтверждения, и без него
     * любая ошибка выглядит как зависание, а не как ошибка.
     */
    private void routeCallback(CallbackQuery query) {
        BotRequest request = parse(query);
        String notice = null;
        try {
            notice = dispatchCallback(request, query.getData());
        } catch (DomainException e) {
            notice = userText(e);
        } catch (IllegalArgumentException e) {
            // подделанная или испорченная строка кнопки — ожидаемый случай, не сбой
            log.warn("rejected malformed callback from user {}", request.telegramUserId());
            notice = Texts.INTERNAL_ERROR;
        } catch (RuntimeException e) {
            log.error("failed to handle callback from user {}", request.telegramUserId(), e);
            notice = Texts.INTERNAL_ERROR;
        } finally {
            answer(query.getId(), notice);
        }
    }

    private void dispatch(BotRequest request) {
        Optional<CommandHandler> handler = request.command().map(commands::get);

        if (request.member().isEmpty()) {
            if (handler.filter(CommandHandler::allowsStrangers).isPresent()) {
                handler.get().handle(request);
            } else {
                reply(request.chatId(), Texts.STRANGER);
            }
            return;
        }

        if (handler.isPresent()) {
            handler.get().handle(request);
        } else if (request.command().isPresent()) {
            reply(request.chatId(), Texts.UNKNOWN_COMMAND);
        } else {
            freeTextFallback(request);
        }
    }

    private String dispatchCallback(BotRequest request, String raw) {
        if (request.member().isEmpty()) {
            return Texts.STRANGER;
        }
        CallbackData data = CallbackData.parse(raw);
        CallbackHandler handler = callbacks.get(data.prefix());
        if (handler == null) {
            log.warn("no handler for callback prefix {}", data.prefix());
            return Texts.INTERNAL_ERROR;
        }
        handler.handle(request, data);
        return null;
    }

    /** Свободный текст вне диалога — пока не команда и не шаг сценария; сценарии добавит задача 14. */
    private void freeTextFallback(BotRequest request) {
        reply(request.chatId(), Texts.UNKNOWN_COMMAND);
    }

    private BotRequest parse(Message message) {
        long userId = message.getFrom().getId();
        String text = message.hasText() ? message.getText() : "";
        String command = null;
        String argument = null;

        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            command = parts[0].split("@", 2)[0].toLowerCase();
            argument = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
        }

        return new BotRequest(
                userId,
                message.getChatId(),
                members.findByTelegramUserId(userId).filter(Member::isActive),
                text,
                Optional.ofNullable(command),
                Optional.ofNullable(argument),
                Optional.of(message.getMessageId()),
                Optional.empty());
    }

    private BotRequest parse(CallbackQuery query) {
        long userId = query.getFrom().getId();
        Integer messageId =
                query.getMessage() == null ? null : query.getMessage().getMessageId();
        long chatId = query.getMessage() == null ? userId : query.getMessage().getChatId();

        return new BotRequest(
                userId,
                chatId,
                members.findByTelegramUserId(userId).filter(Member::isActive),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(messageId),
                Optional.of(query.getId()));
    }

    private String userText(DomainException e) {
        return switch (e) {
            case DomainException.NotFound ignored -> Texts.NOT_FOUND;
            case DomainException.NotPermitted ignored -> Texts.NOT_PERMITTED;
            case DomainException.InvalidTransition invalid -> transitionText(invalid);
        };
    }

    private String transitionText(DomainException.InvalidTransition invalid) {
        TaskStatus status = invalid.currentStatus();
        if (status == null) {
            return Texts.NOT_FOUND; // инвайт: истёк, использован или не существует — ответ один
        }
        return switch (status) {
            case DONE -> Texts.ALREADY_DONE;
            case OPEN -> Texts.ALREADY_OPEN;
            case DECLINED -> Texts.ALREADY_CLOSED;
        };
    }

    /**
     * Текст уходит как есть, без экранирования: сюда попадают только собственные сообщения бота, а
     * пользовательский текст экранирует вёрстка, которая его и вставляет. Экранировать здесь
     * значило бы разрушать разметку, собранную во вьюхах.
     */
    private void reply(long chatId, String text) {
        send(SendMessage.builder().chatId(chatId).text(text).parseMode("HTML").build());
    }

    private void answer(String callbackQueryId, String notice) {
        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder =
                AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId);
        if (notice != null) {
            builder.text(notice);
        }
        send(builder.build());
    }

    private <T extends Serializable, M extends BotApiMethod<T>> void send(M method) {
        try {
            client.execute(method);
        } catch (TelegramApiException e) {
            // текст ошибки может содержать сообщение пользователя — в лог только класс
            log.warn("telegram call failed: {}", e.getClass().getSimpleName());
        }
    }
}

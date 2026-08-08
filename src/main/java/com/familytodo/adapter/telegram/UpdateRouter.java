package com.familytodo.adapter.telegram;

import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import com.familytodo.domain.TaskStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

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
    private final BotSender sender;
    private final List<DialogHandler> dialogs;
    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Map<String, CallbackHandler> callbacks = new HashMap<>();

    public UpdateRouter(
            MemberRepository members,
            BotSender sender,
            List<CommandHandler> commandHandlers,
            List<CallbackHandler> callbackHandlers,
            List<DialogHandler> dialogHandlers) {
        this.members = members;
        this.sender = sender;
        this.dialogs = dialogHandlers;
        commandHandlers.forEach(
                handler -> handler.commands().forEach(name -> register(commands, name, handler)));
        callbackHandlers.forEach(handler -> register(callbacks, handler.prefix(), handler));
    }

    /**
     * ⚠️ Совпавший ключ обязан ронять сборку контекста, а не вытеснять предыдущий обработчик.
     *
     * <p>{@code Map.put} затирает молча: команда или целый экран переставали бы работать без единой
     * строки в журнале и без ошибки сборки, а кто именно выиграл — зависело бы от порядка бинов.
     * Находка в этом случае приходит только жалобой «кнопка ничего не делает».
     *
     * <p>В сообщении лишь ключ и классы: имена обработчиков — не пользовательские данные, а ключ
     * это буква из кода.
     */
    private static <H> void register(Map<String, H> registry, String key, H handler) {
        H previous = registry.put(key, handler);
        if (previous != null) {
            throw new IllegalStateException(
                    "two handlers claim '%s': %s and %s"
                            .formatted(
                                    key,
                                    previous.getClass().getSimpleName(),
                                    handler.getClass().getSimpleName()));
        }
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
            sender.send(request.chatId(), userText(e));
        } catch (RuntimeException e) {
            log.error("failed to handle message from user {}", request.telegramUserId(), e);
            sender.send(request.chatId(), Texts.INTERNAL_ERROR);
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
            sender.answerCallback(query.getId(), notice);
        }
    }

    private void dispatch(BotRequest request) {
        Optional<CommandHandler> handler = request.command().map(commands::get);

        if (request.member().isEmpty()) {
            if (handler.filter(CommandHandler::allowsStrangers).isPresent()) {
                handler.get().handle(request);
            } else if (request.command().isEmpty() && continueDialog(request)) {
                // бот сам начал разговор — отвечать на свой же вопрос человеку можно
                return;
            } else {
                sender.send(request.chatId(), Texts.STRANGER);
            }
            return;
        }

        if (handler.isPresent()) {
            handler.get().handle(request);
        } else if (request.command().isPresent()) {
            sender.send(request.chatId(), Texts.UNKNOWN_COMMAND);
        } else if (!continueDialog(request)) {
            sender.send(request.chatId(), Texts.UNKNOWN_COMMAND);
        }
    }

    /** Свободный текст принимается только внутри начатого сценария. */
    private boolean continueDialog(BotRequest request) {
        for (DialogHandler dialog : dialogs) {
            if (dialog.continueDialog(request)) {
                return true;
            }
        }
        return false;
    }

    private String dispatchCallback(BotRequest request, String raw) {
        CallbackData data = CallbackData.parse(raw);
        CallbackHandler handler = callbacks.get(data.prefix());
        if (handler == null) {
            log.warn("no handler for callback prefix {}", data.prefix());
            return Texts.INTERNAL_ERROR;
        }
        // онбординг доступен тому, кто ещё не в семье, — это его единственный путь внутрь
        if (request.member().isEmpty() && !handler.allowsStrangers()) {
            return Texts.STRANGER;
        }
        handler.handle(request, data);
        return null;
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
                displayName(message.getFrom().getFirstName(), userId),
                members.findByTelegramUserId(userId).filter(Member::isActive),
                text,
                Optional.ofNullable(command),
                Optional.ofNullable(argument),
                Optional.of(message.getMessageId()),
                Optional.empty());
    }

    private BotRequest parse(CallbackQuery query) {
        long userId = query.getFrom().getId();
        Integer messageId = query.getMessage() == null ? null : query.getMessage().getMessageId();
        long chatId = query.getMessage() == null ? userId : query.getMessage().getChatId();

        return new BotRequest(
                userId,
                chatId,
                displayName(query.getFrom().getFirstName(), userId),
                members.findByTelegramUserId(userId).filter(Member::isActive),
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(messageId),
                Optional.of(query.getId()));
    }

    /** Имя в профиле можно очистить — пустое сломало бы создание участника. */
    private static String displayName(String firstName, long userId) {
        return firstName == null || firstName.isBlank() ? "Участник " + userId : firstName;
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
}

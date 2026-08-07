package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.DialogHandler;
import com.familytodo.adapter.telegram.DialogState;
import com.familytodo.adapter.telegram.DialogStateStore;
import com.familytodo.adapter.telegram.keyboard.TimezoneKeyboard;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Member;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Онбординг: {@code /start}, создание семьи и вход по приглашению.
 *
 * <p>Единственная команда, доступная тому, кто ещё не в семье, — через неё и создают семью, и
 * принимают приглашение. Всё остальное незнакомцу отвечает одной фразой.
 */
@Component
public class StartHandler implements CommandHandler, CallbackHandler, DialogHandler {

    private static final Logger log = LoggerFactory.getLogger(StartHandler.class);

    private static final String INVITE_PREFIX = "inv_";
    private static final int MAX_FAMILY_NAME = 60;

    private final FamilyService families;
    private final InviteService invites;
    private final MemberRepository members;
    private final DialogStateStore dialogs;
    private final BotSender sender;

    public StartHandler(
            FamilyService families,
            InviteService invites,
            MemberRepository members,
            DialogStateStore dialogs,
            BotSender sender) {
        this.families = families;
        this.invites = invites;
        this.members = members;
        this.dialogs = dialogs;
        this.sender = sender;
    }

    @Override
    public Set<String> commands() {
        return Set.of("start");
    }

    /**
     * Один переопределённый метод закрывает оба интерфейса, и по смыслу это одно и то же правило:
     * и команда {@code /start}, и выбор часового пояса случаются до того, как семья существует.
     */
    @Override
    public boolean allowsStrangers() {
        return true;
    }

    @Override
    public String prefix() {
        return TimezoneKeyboard.PREFIX;
    }

    @Override
    public void handle(BotRequest request) {
        Optional<Member> member = request.member();
        if (member.isPresent()) {
            greetExistingMember(request, member.get());
            return;
        }

        Optional<String> code = request.commandArgument().flatMap(StartHandler::inviteCode);
        if (code.isPresent()) {
            redeem(request, code.get());
        } else {
            dialogs.put(request.telegramUserId(), new DialogState.AwaitingFamilyName());
            sender.send(request.chatId(), Texts.ASK_FAMILY_NAME);
        }
    }

    /** Свободный текст обрабатываем только когда сами о чём-то спросили. */
    @Override
    public boolean continueDialog(BotRequest request) {
        return dialogs.get(request.telegramUserId())
                        .filter(DialogState.AwaitingFamilyName.class::isInstance)
                        .isPresent()
                && acceptFamilyName(request);
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        if (!TimezoneKeyboard.ACTION.equals(data.action())) {
            return;
        }
        DialogState state = dialogs.get(request.telegramUserId()).orElse(null);
        if (!(state instanceof DialogState.AwaitingTimezone awaiting)) {
            sender.send(request.chatId(), Texts.ASK_FAMILY_NAME);
            dialogs.put(request.telegramUserId(), new DialogState.AwaitingFamilyName());
            return;
        }

        Optional<ZoneId> zone = TimezoneKeyboard.resolve(data.argument());
        if (zone.isEmpty()) {
            sender.send(request.chatId(), Texts.ASK_TIMEZONE_AGAIN, TimezoneKeyboard.markup());
            return;
        }

        Member founder =
                families.createFamily(
                        request.telegramUserId(),
                        request.chatId(),
                        request.displayName(),
                        awaiting.familyName(),
                        zone.get());
        dialogs.clear(request.telegramUserId());
        log.info("family {} created by member {}", founder.familyId(), founder.id());

        sender.send(request.chatId(), "Семья создана.\n\n" + Texts.MAIN_MENU);
    }

    private void greetExistingMember(BotRequest request, Member member) {
        // без актуального chat_id напоминания недоставимы
        if (member.privateChatId() != request.chatId()) {
            member.rememberPrivateChat(request.chatId());
            members.save(member);
        }
        dialogs.clear(request.telegramUserId());

        boolean broughtInvite =
                request.commandArgument().flatMap(StartHandler::inviteCode).isPresent();
        String text = broughtInvite ? Texts.ALREADY_IN_FAMILY + "\n\n" + Texts.MAIN_MENU : Texts.MAIN_MENU;
        sender.send(request.chatId(), text);
    }

    private void redeem(BotRequest request, String code) {
        try {
            Member joined =
                    invites.redeem(
                            code,
                            request.telegramUserId(),
                            request.chatId(),
                            request.displayName());
            dialogs.clear(request.telegramUserId());
            log.info("member {} joined family {}", joined.id(), joined.familyId());
            sender.send(request.chatId(), "Готово, ты в семье.\n\n" + Texts.MAIN_MENU);
        } catch (DomainException.NotFound | DomainException.InvalidTransition e) {
            // истёк, использован, не существует — снаружи неразличимо
            log.info("invite rejected for user {}", request.telegramUserId());
            sender.send(request.chatId(), Texts.INVITE_INVALID);
        } catch (DomainException.NotPermitted e) {
            sender.send(request.chatId(), Texts.ALREADY_IN_FAMILY);
        }
    }

    /** Шаг диалога: пришло название семьи. */
    private boolean acceptFamilyName(BotRequest request) {
        String name = request.text().trim();
        if (name.isBlank()) {
            sender.send(request.chatId(), Texts.ASK_FAMILY_NAME);
            return true;
        }
        if (name.length() > MAX_FAMILY_NAME) {
            sender.send(request.chatId(), Texts.FAMILY_NAME_TOO_LONG);
            return true;
        }

        dialogs.put(request.telegramUserId(), new DialogState.AwaitingTimezone(name));
        sender.send(
                request.chatId(),
                "Семья «" + HtmlEscaper.escape(name) + "».\n\n" + Texts.ASK_TIMEZONE,
                TimezoneKeyboard.markup());
        return true;
    }

    private static Optional<String> inviteCode(String argument) {
        return argument.startsWith(INVITE_PREFIX)
                ? Optional.of(argument.substring(INVITE_PREFIX.length()))
                : Optional.empty();
    }
}

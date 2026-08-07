package com.familytodo.adapter.telegram.handler;

import com.familytodo.adapter.telegram.BotRequest;
import com.familytodo.adapter.telegram.BotSender;
import com.familytodo.adapter.telegram.CallbackData;
import com.familytodo.adapter.telegram.CallbackHandler;
import com.familytodo.adapter.telegram.CommandHandler;
import com.familytodo.adapter.telegram.keyboard.TimezoneKeyboard;
import com.familytodo.adapter.telegram.view.FamilyView;
import com.familytodo.adapter.telegram.view.HtmlEscaper;
import com.familytodo.adapter.telegram.view.Texts;
import com.familytodo.application.FamilyService;
import com.familytodo.application.InviteService;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Family;
import com.familytodo.domain.Invite;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/** Состав семьи, приглашения и настройки: {@code /family}. */
@Component
public class FamilyHandler implements CommandHandler, CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(FamilyHandler.class);

    private final FamilyService families;
    private final InviteService invites;
    private final BotSender sender;
    private final String botUsername;

    public FamilyHandler(
            FamilyService families,
            InviteService invites,
            BotSender sender,
            @Value("${telegram.bot.username}") String botUsername) {
        this.families = families;
        this.invites = invites;
        this.sender = sender;
        this.botUsername = botUsername;
    }

    @Override
    public Set<String> commands() {
        return Set.of("family");
    }

    @Override
    public String prefix() {
        return FamilyView.PREFIX;
    }

    @Override
    public boolean allowsStrangers() {
        return false;
    }

    @Override
    public void handle(BotRequest request) {
        Member member = request.requireMember();
        sender.send(request.chatId(), roster(member), FamilyView.menu(isParent(member)));
    }

    @Override
    public void handle(BotRequest request, CallbackData data) {
        Member actor = request.requireMember();

        switch (data.action()) {
            case FamilyView.MENU -> showMenu(request, actor);
            case FamilyView.INVITE -> invite(request, actor, data.argument());
            case FamilyView.REMOVE -> askWhoToRemove(request, actor);
            case FamilyView.REMOVE_ASK -> confirmRemoval(request, actor, data);
            case FamilyView.REMOVE_DO -> remove(request, actor, data);
            case FamilyView.SETTINGS -> showSettings(request, actor);
            case FamilyView.TIMEZONE -> timezone(request, actor, data.argument());
            case FamilyView.DIGEST -> digest(request, actor, data.argument());
            default -> log.warn("unknown family action {}", data.action());
        }
    }

    private void showMenu(BotRequest request, Member actor) {
        edit(request, roster(actor), FamilyView.menu(isParent(actor)));
    }

    /**
     * Ссылка одноразовая и живёт 24 часа. Роль задаётся при выпуске, а не при входе: иначе
     * приглашённый сам решал бы, кем ему стать.
     */
    private void invite(BotRequest request, Member actor, String argument) {
        if (refuseNonParent(actor, Texts.INVITE_IS_FOR_PARENTS, request)) {
            return;
        }
        Role role = "parent".equals(argument) ? Role.PARENT : Role.CHILD;

        Invite invite = invites.issue(actor, role);
        log.info("invite issued in family {} by member {}", actor.familyId(), actor.id());

        String link = "https://t.me/" + botUsername + "?start=inv_" + invite.code();
        sender.send(
                request.chatId(),
                "Ссылка для входа в семью ("
                        + (role == Role.PARENT ? "взрослый" : "ребёнок")
                        + "), действует сутки и только один раз:\n\n"
                        + HtmlEscaper.escape(link));
    }

    private void askWhoToRemove(BotRequest request, Member actor) {
        if (refuseNonParent(actor, Texts.REMOVE_IS_FOR_PARENTS, request)) {
            return;
        }

        List<Member> others =
                families.roster(actor).stream().filter(m -> m.id() != actor.id()).toList();
        if (others.isEmpty()) {
            edit(request, Texts.NOBODY_TO_REMOVE, FamilyView.menu(true));
            return;
        }
        edit(request, Texts.ASK_WHO_TO_REMOVE, FamilyView.members(others, FamilyView.REMOVE_ASK));
    }

    private void confirmRemoval(BotRequest request, Member actor, CallbackData data) {
        if (refuseNonParent(actor, Texts.REMOVE_IS_FOR_PARENTS, request)) {
            return;
        }
        Member target = member(actor, data.longArgument());

        edit(
                request,
                "Исключить "
                        + HtmlEscaper.escape(target.displayName())
                        + "? Его открытые дела будут закрыты, а авторы получат уведомление.",
                FamilyView.confirmRemoval(target));
    }

    private void remove(BotRequest request, Member actor, CallbackData data) {
        if (refuseNonParent(actor, Texts.REMOVE_IS_FOR_PARENTS, request)) {
            return;
        }
        Member target = member(actor, data.longArgument());

        // проверяем условие явно, а не ловим NotPermitted: тот же тип означал бы
        // и «не родитель», и «чужая семья», и утверждать по нему одну причину нельзя
        if (isLastParent(actor, target)) {
            edit(request, Texts.LAST_PARENT_STAYS, FamilyView.menu(true));
            return;
        }

        families.removeMember(actor, target.id());
        log.info("member {} removed from family {}", target.id(), actor.familyId());
        showMenu(request, actor);
    }

    private void showSettings(BotRequest request, Member actor) {
        if (refuseNonParent(actor, Texts.SETTINGS_ARE_FOR_PARENTS, request)) {
            return;
        }
        edit(request, roster(actor), FamilyView.settings());
    }

    private void timezone(BotRequest request, Member actor, String argument) {
        if (refuseNonParent(actor, Texts.SETTINGS_ARE_FOR_PARENTS, request)) {
            return;
        }

        if ("ask".equals(argument)) {
            edit(request, Texts.ASK_TIMEZONE, TimezoneKeyboard.markup(FamilyView.PREFIX));
            return;
        }

        Optional<ZoneId> zone = TimezoneKeyboard.resolve(argument);
        if (zone.isEmpty()) {
            edit(request, Texts.ASK_TIMEZONE_AGAIN, TimezoneKeyboard.markup(FamilyView.PREFIX));
            return;
        }

        families.changeTimezone(actor, zone.get());
        showMenu(request, actor);
    }

    private void digest(BotRequest request, Member actor, String argument) {
        if (refuseNonParent(actor, Texts.SETTINGS_ARE_FOR_PARENTS, request)) {
            return;
        }

        if ("ask".equals(argument)) {
            edit(request, "Во сколько присылать утренний список?", FamilyView.digestHours());
            return;
        }

        int hour;
        try {
            hour = Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("digest hour is not a number", e);
        }
        if (!FamilyView.DIGEST_HOURS.contains(hour)) {
            throw new IllegalArgumentException("digest hour is out of range");
        }

        families.changeDigestTime(actor, LocalTime.of(hour, 0));
        showMenu(request, actor);
    }

    private String roster(Member viewer) {
        Family family = families.family(viewer);
        return FamilyView.roster(family, families.roster(viewer));
    }

    private Member member(Member actor, long memberId) {
        return families.roster(actor).stream()
                .filter(m -> m.id() == memberId)
                .findFirst()
                .orElseThrow(() -> new DomainException.NotFound("member not found"));
    }

    private boolean isLastParent(Member actor, Member target) {
        return target.role() == Role.PARENT
                && families.roster(actor).stream()
                                .filter(m -> m.role() == Role.PARENT)
                                .filter(m -> m.id() != target.id())
                                .findAny()
                                .isEmpty();
    }

    private boolean isParent(Member member) {
        return member.role() == Role.PARENT;
    }

    /**
     * Кнопки ребёнку не показываются, но нажатие может прийти подделанной строкой.
     *
     * @return {@code true}, если действие отклонено и вызывающему нужно остановиться
     */
    private boolean refuseNonParent(Member actor, String message, BotRequest request) {
        if (isParent(actor)) {
            return false;
        }
        sender.send(request.chatId(), message);
        return true;
    }

    private void edit(BotRequest request, String text, InlineKeyboardMarkup markup) {
        request.messageId()
                .ifPresentOrElse(
                        id -> sender.edit(request.chatId(), id, text, markup),
                        () -> sender.send(request.chatId(), text, markup));
    }
}

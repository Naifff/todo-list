package com.familytodo.application;

import com.familytodo.application.port.InviteRepository;
import com.familytodo.application.port.MemberRepository;
import com.familytodo.domain.DomainException;
import com.familytodo.domain.Invite;
import com.familytodo.domain.InviteCodeGenerator;
import com.familytodo.domain.Member;
import com.familytodo.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Выпуск и погашение приглашений. */
public class InviteService {

    private final InviteRepository invites;
    private final MemberRepository members;
    private final InviteCodeGenerator codes;
    private final Clock clock;

    public InviteService(
            InviteRepository invites,
            MemberRepository members,
            InviteCodeGenerator codes,
            Clock clock) {
        this.invites = invites;
        this.members = members;
        this.codes = codes;
        this.clock = clock;
    }

    public Invite issue(Member actor, Role invitedRole) {
        Invite invite =
                Invite.issue(
                        actor.asActor(),
                        invites.nextId(),
                        actor.familyId(),
                        codes.generate(),
                        invitedRole,
                        clock.instant());
        return invites.save(invite);
    }

    /**
     * Порядок важен дважды.
     *
     * <p>Во-первых, код гасится <b>последним</b>: приглашение сначала только читается, а все отказы
     * происходят до {@code redeem} — иначе родитель, кликнувший по собственной ссылке, сжёг бы код,
     * предназначенный другому.
     *
     * <p>Во-вторых, участник записывается <b>раньше</b> приглашения: {@code invite.used_by}
     * ссылается на {@code member(id)}. Обратный порядок нарушает внешний ключ — и нарушал, пока это
     * не всплыло на первой настоящей ссылке. Юнит-тесты на фейках такого не видят, гарантию держит
     * {@code InviteRedemptionIT} на реальной базе.
     *
     * <p>Обе записи идут одной транзакцией: сбой между ними оставил бы человека в семье с живой
     * одноразовой ссылкой. Транзакция короткая и без сети — отправка в Telegram происходит уже
     * после возврата, в хендлере. Что аннотация действительно работает, проверяет
     * {@code InviteTransactionIT} на настоящем контексте: без прокси Spring она была бы пустой
     * декорацией, которая ничего не откатывает и молчит об этом.
     */
    @Transactional
    public Member redeem(String code, long telegramUserId, long chatId, String displayName) {
        Optional<Member> existing = members.findByTelegramUserId(telegramUserId);
        Invite invite =
                invites.findByCode(code)
                        .orElseThrow(() -> new DomainException.NotFound("invite not found"));

        Instant now = clock.instant();

        if (existing.isPresent()) {
            return rejoin(existing.get(), invite, chatId, now);
        }

        long memberId = members.nextId();
        invite.redeem(memberId, now);

        // строка участника пишется ПЕРВОЙ. `invite.used_by` ссылается на `member(id)`,
        // и погашение приглашения до создания участника нарушает внешний ключ: в проде
        // это дало «Что-то пошло не так» на первой же ссылке, отправленной жене.
        Member joined =
                members.save(
                        Member.join(
                                memberId,
                                invite.familyId(),
                                telegramUserId,
                                chatId,
                                displayName,
                                invite.role(),
                                now));
        invites.save(invite);
        return joined;
    }

    /**
     * Исключённого можно позвать обратно — но только в ту же семью: {@code telegram_user_id}
     * уникален глобально, и его строка принадлежит прежней семье.
     */
    private Member rejoin(Member member, Invite invite, long chatId, Instant now) {
        if (member.isActive() || member.familyId() != invite.familyId()) {
            throw new DomainException.NotPermitted("person already in a family");
        }

        invite.redeem(member.id(), now);

        // тот же порядок, что и у нового участника: сначала то, на что ссылаются.
        // Здесь строка уже есть и ключ не упал бы, но два разных порядка в одном
        // методе — приглашение повторить ошибку
        member.rejoin(invite.role(), chatId);
        Member joined = members.save(member);
        invites.save(invite);
        return joined;
    }
}

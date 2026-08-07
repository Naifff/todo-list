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
     * Порядок важен: код гасится <b>последним</b>. Приглашение сначала только читается, а все
     * отказы происходят до {@code redeem} — иначе родитель, кликнувший по собственной ссылке, сжёг
     * бы код, предназначенный другому.
     */
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
        invites.save(invite);

        return members.save(
                Member.join(
                        memberId,
                        invite.familyId(),
                        telegramUserId,
                        chatId,
                        displayName,
                        invite.role(),
                        now));
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
        invites.save(invite);

        member.rejoin(invite.role(), chatId);
        return members.save(member);
    }
}

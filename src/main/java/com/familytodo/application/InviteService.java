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
     * Порядок проверок важен: сначала «этот человек уже в семье», потом состояние приглашения.
     * Иначе повторный переход по своей же ссылке гасит код, который предназначался другому.
     */
    public Member redeem(String code, long telegramUserId, long chatId, String displayName) {
        members.findByTelegramUserId(telegramUserId)
                .ifPresent(
                        existing -> {
                            throw new DomainException.NotPermitted("person already in a family");
                        });

        Invite invite =
                invites.findByCode(code)
                        .orElseThrow(() -> new DomainException.NotFound("invite not found"));

        Instant now = clock.instant();
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
}

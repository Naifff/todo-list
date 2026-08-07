package com.familytodo.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Одноразовое приглашение в семью со сроком жизни 24 часа.
 *
 * <p>Причины отказа при погашении различаются в сообщении для логов, но наружу бот отдаёт один и
 * тот же текст: незнакомцу не сообщается, существует ли код, истёк он или уже использован.
 */
public final class Invite {

    public static final Duration TTL = Duration.ofHours(24);

    private final long id;
    private final long familyId;
    private final String code;
    private final Role role;
    private final long createdBy;
    private final Instant expiresAt;

    private Long usedBy;
    private Instant usedAt;

    private Invite(
            long id,
            long familyId,
            String code,
            Role role,
            long createdBy,
            Instant expiresAt,
            Long usedBy,
            Instant usedAt) {
        this.id = id;
        this.familyId = familyId;
        this.code = requireCode(code);
        this.role = requireRole(role);
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.usedBy = usedBy;
        this.usedAt = usedAt;
    }

    /** Выпустить приглашение может только родитель этой семьи; роль приглашаемого задаётся здесь. */
    public static Invite issue(
            Actor actor, long id, long familyId, String code, Role role, Instant now) {
        if (!(actor instanceof Actor.MemberActor member)
                || member.familyId() != familyId
                || member.role() != Role.PARENT) {
            throw new DomainException.NotPermitted("only a parent of this family may invite");
        }
        return new Invite(id, familyId, code, role, member.memberId(), now.plus(TTL), null, null);
    }

    public static Invite restore(
            long id,
            long familyId,
            String code,
            Role role,
            long createdBy,
            Instant expiresAt,
            Long usedBy,
            Instant usedAt) {
        return new Invite(id, familyId, code, role, createdBy, expiresAt, usedBy, usedAt);
    }

    public void redeem(long memberId, Instant now) {
        if (usedBy != null) {
            throw new DomainException.InvalidTransition(null, "invite is already used");
        }
        if (!now.isBefore(expiresAt)) {
            throw new DomainException.InvalidTransition(null, "invite has expired");
        }

        this.usedBy = memberId;
        this.usedAt = now;
    }

    public boolean isUsable(Instant now) {
        return usedBy == null && now.isBefore(expiresAt);
    }

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public String code() {
        return code;
    }

    public Role role() {
        return role;
    }

    public long createdBy() {
        return createdBy;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Long usedBy() {
        return usedBy;
    }

    public Instant usedAt() {
        return usedAt;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("invite code is required");
        }
        return code;
    }

    private static Role requireRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("invited role is required");
        }
        return role;
    }
}

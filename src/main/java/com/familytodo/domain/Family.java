package com.familytodo.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;

/**
 * Семья: настройки и правила, касающиеся состава.
 *
 * <p>Состав участников семья в себе не держит — он приходит параметром. Инвариант «хотя бы один
 * активный родитель» проверяется по этому списку: держать его полем значило бы грузить всех
 * участников ради любой операции над семьёй.
 */
public final class Family {

    private static final LocalTime DEFAULT_DIGEST_TIME = LocalTime.of(8, 0);

    private final long id;
    private final Instant createdAt;

    private String name;
    private ZoneId timezone;
    private LocalTime digestTime;
    private LocalDate lastDigestDate;

    private Family(
            long id,
            String name,
            ZoneId timezone,
            LocalTime digestTime,
            LocalDate lastDigestDate,
            Instant createdAt) {
        this.id = id;
        this.name = requireName(name);
        this.timezone = requireTimezone(timezone);
        this.digestTime = requireDigestTime(digestTime);
        this.lastDigestDate = lastDigestDate;
        this.createdAt = createdAt;
    }

    /**
     * {@code lastDigestDate} — сегодняшняя дата по календарю семьи, а не {@code null}: иначе
     * семья, зарегистрированная днём, получит дайджест ближайшим тиком джобы.
     */
    public static Family create(long id, String name, ZoneId timezone, Instant now) {
        ZoneId zone = requireTimezone(timezone);
        return new Family(
                id, name, zone, DEFAULT_DIGEST_TIME, LocalDate.ofInstant(now, zone), now);
    }

    public static Family restore(
            long id,
            String name,
            ZoneId timezone,
            LocalTime digestTime,
            LocalDate lastDigestDate,
            Instant createdAt) {
        return new Family(id, name, timezone, digestTime, lastDigestDate, createdAt);
    }

    /**
     * Исключение участника: статус, а не удаление строки.
     *
     * @param activeMembers все активные участники семьи, включая исключаемого
     */
    public void removeMember(Actor actor, Member target, Collection<Member> activeMembers) {
        requireParentOfThisFamily(actor);
        requireOwnMember(target);
        if (!target.isActive()) {
            throw new DomainException.InvalidTransition(null, "member is already removed");
        }
        requireParentRemains(target, activeMembers, false);

        target.markRemovedBy(this);
    }

    /** Понижение последнего родителя запрещено ровно по той же причине, что и его исключение. */
    public void changeRole(
            Actor actor, Member target, Role newRole, Collection<Member> activeMembers) {
        requireParentOfThisFamily(actor);
        requireOwnMember(target);
        if (!target.isActive()) {
            throw new DomainException.InvalidTransition(null, "member is removed");
        }
        requireParentRemains(target, activeMembers, newRole == Role.PARENT);

        target.changeRoleBy(this, newRole);
    }

    public void changeTimezone(Actor actor, ZoneId newTimezone) {
        requireParentOfThisFamily(actor);
        this.timezone = requireTimezone(newTimezone);
    }

    public void changeDigestTime(Actor actor, LocalTime newDigestTime) {
        requireParentOfThisFamily(actor);
        this.digestTime = requireDigestTime(newDigestTime);
    }

    public void rename(Actor actor, String newName) {
        requireParentOfThisFamily(actor);
        this.name = requireName(newName);
    }

    public void markDigestSent(LocalDate sentOn) {
        this.lastDigestDate = sentOn;
    }

    /** Текущая дата по календарю семьи — им меряется и дайджест, и «сегодня» в сроках. */
    public LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, timezone);
    }

    // --- правила ---

    private void requireParentOfThisFamily(Actor actor) {
        if (!(actor instanceof Actor.MemberActor member)
                || member.familyId() != id
                || member.role() != Role.PARENT) {
            throw new DomainException.NotPermitted("only a parent of this family may do that");
        }
    }

    private void requireOwnMember(Member target) {
        if (target.familyId() != id) {
            throw new DomainException.NotPermitted("member belongs to another family");
        }
    }

    /**
     * После операции в семье должен остаться хотя бы один активный родитель — иначе некому звать
     * новых участников и управлять составом.
     *
     * @param targetStaysParent останется ли {@code target} активным родителем после операции; при
     *     исключении — всегда {@code false}
     */
    private void requireParentRemains(
            Member target, Collection<Member> activeMembers, boolean targetStaysParent) {
        long otherParents =
                activeMembers.stream()
                        .filter(Member::isActive)
                        .filter(Member::isParent)
                        .filter(m -> m.id() != target.id())
                        .count();

        if (otherParents == 0 && !targetStaysParent && target.isParent()) {
            throw new DomainException.NotPermitted("the last parent must remain");
        }
    }

    // --- чтение ---

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ZoneId timezone() {
        return timezone;
    }

    public LocalTime digestTime() {
        return digestTime;
    }

    public LocalDate lastDigestDate() {
        return lastDigestDate;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("family name is required");
        }
        return name;
    }

    private static ZoneId requireTimezone(ZoneId timezone) {
        if (timezone == null) {
            throw new IllegalArgumentException("timezone is required");
        }
        return timezone;
    }

    private static LocalTime requireDigestTime(LocalTime digestTime) {
        if (digestTime == null) {
            throw new IllegalArgumentException("digest time is required");
        }
        return digestTime;
    }
}

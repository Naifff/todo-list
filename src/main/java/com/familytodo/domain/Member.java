package com.familytodo.domain;

import java.time.Instant;

/**
 * Участник семьи.
 *
 * <p>Исключение — смена статуса на {@link MemberStatus#REMOVED}, а не удаление строки: за
 * участником остаются закрытые задачи, и они должны читаться после его ухода.
 *
 * <p>Правила «кто кого может исключить или понизить» живут в {@link Family} — они про состав
 * семьи целиком, а не про одного участника.
 */
public final class Member {

    /**
     * Имя показывается в списках, карточках и календаре. Длиннее — и строка списка перестаёт
     * читаться, а на кнопке остаётся многоточие.
     */
    public static final int MAX_NAME = 40;

    private final long id;
    private final long familyId;
    private final long telegramUserId;
    private final Instant createdAt;

    private long privateChatId;
    private String displayName;
    private Role role;
    private MemberStatus status;
    private boolean blockedBot;

    /** Цвет, которым рисуются дела этого человека в расписании. */
    private MemberColor color;

    private Member(
            long id,
            long familyId,
            long telegramUserId,
            long privateChatId,
            String displayName,
            Role role,
            MemberStatus status,
            boolean blockedBot,
            MemberColor color,
            Instant createdAt) {
        this.id = id;
        this.familyId = familyId;
        this.telegramUserId = telegramUserId;
        this.privateChatId = privateChatId;
        this.displayName = requireDisplayName(displayName);
        this.role = requireRole(role);
        this.status = status;
        this.blockedBot = blockedBot;
        this.color = color == null ? MemberColor.forMember(id) : color;
        this.createdAt = createdAt;
    }

    public static Member join(
            long id,
            long familyId,
            long telegramUserId,
            long privateChatId,
            String displayName,
            Role role,
            Instant createdAt) {
        return new Member(
                id,
                familyId,
                telegramUserId,
                privateChatId,
                displayName,
                role,
                MemberStatus.ACTIVE,
                false,
                MemberColor.forMember(id),
                createdAt);
    }

    public static Member restore(
            long id,
            long familyId,
            long telegramUserId,
            long privateChatId,
            String displayName,
            Role role,
            MemberStatus status,
            boolean blockedBot,
            MemberColor color,
            Instant createdAt) {
        return new Member(
                id,
                familyId,
                telegramUserId,
                privateChatId,
                displayName,
                role,
                status,
                blockedBot,
                color,
                createdAt);
    }

    public Actor asActor() {
        return Actor.member(id, familyId, role);
    }

    /** Бот пишет в личный чат, а не туда, откуда пришёл апдейт: иначе напоминания недоставимы. */
    public void rememberPrivateChat(long chatId) {
        this.privateChatId = chatId;
    }

    public void rename(String newDisplayName) {
        this.displayName = requireDisplayName(newDisplayName);
    }

    public void markBotBlocked() {
        this.blockedBot = true;
    }

    public void markBotUnblocked() {
        this.blockedBot = false;
    }

    // пакетная видимость: переход разрешён только через Family, которая знает состав семьи
    void markRemovedBy(Family family) {
        if (family.id() != familyId) {
            throw new DomainException.NotPermitted("member belongs to another family");
        }
        this.status = MemberStatus.REMOVED;
    }

    void renameBy(Family family, String newDisplayName) {
        requireSameFamily(family);
        this.displayName = requireDisplayName(newDisplayName);
    }

    void recolorBy(Family family, MemberColor newColor) {
        requireSameFamily(family);
        if (newColor == null) {
            throw new IllegalArgumentException("color is required");
        }
        this.color = newColor;
    }

    private void requireSameFamily(Family family) {
        if (family.id() != familyId) {
            throw new DomainException.NotPermitted("member belongs to another family");
        }
    }

    void changeRoleBy(Family family, Role newRole) {
        if (family.id() != familyId) {
            throw new DomainException.NotPermitted("member belongs to another family");
        }
        this.role = requireRole(newRole);
    }

    /**
     * Возвращение в семью по новому приглашению.
     *
     * <p>Без этого перехода исключение необратимо: строка с {@code telegram_user_id} остаётся, и
     * человек навсегда «уже в семье» — ребёнок, исключённый по ошибке, обратно не позовётся.
     *
     * <p>Роль берётся из приглашения, а выпустить его может только родитель, так что возврат не
     * даёт повысить себя самостоятельно.
     */
    public void rejoin(Role newRole, long chatId) {
        if (status != MemberStatus.REMOVED) {
            throw new DomainException.InvalidTransition(null, "member is already active");
        }
        this.status = MemberStatus.ACTIVE;
        this.role = requireRole(newRole);
        this.privateChatId = chatId;
        // раз человек прошёл по ссылке, бот у него не заблокирован
        this.blockedBot = false;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean isParent() {
        return role == Role.PARENT;
    }

    /** Кому вообще можно писать: исключённым и заблокировавшим бота — нет. */
    public boolean isReachable() {
        return isActive() && !blockedBot;
    }

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public long telegramUserId() {
        return telegramUserId;
    }

    public long privateChatId() {
        return privateChatId;
    }

    public MemberColor color() {
        return color;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    public MemberStatus status() {
        return status;
    }

    public boolean blockedBot() {
        return blockedBot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name is required");
        }
        String trimmed = displayName.strip();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("display name is longer than " + MAX_NAME);
        }
        return trimmed;
    }

    private static Role requireRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        return role;
    }
}

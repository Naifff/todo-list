package com.familytodo.domain;

import java.time.Instant;

/**
 * Задача и все её переходы. Ни Spring, ни JPA, ни одной аннотации — правила прав должны
 * проверяться без поднятия контекста.
 *
 * <p>Порядок проверок внутри каждого перехода: сначала права, потом состояние, потом аргументы.
 * Иначе посторонний по подделанному {@code callback_data} узнаёт статус чужой задачи из текста
 * ошибки.
 */
public final class Task {

    /** Совпадает с ограничением схемы: длиннее в колонку всё равно не влезет. */
    public static final int MAX_TITLE_LENGTH = 200;

    private final long id;
    private final long familyId;
    private final long creatorId;
    private final Instant createdAt;

    private String title;
    private Assignee assignee;
    private TaskStatus status;
    private Instant dueAt;
    private String declineReason;
    private Instant closedAt;

    private Task(
            long id,
            long familyId,
            String title,
            long creatorId,
            Assignee assignee,
            TaskStatus status,
            Instant dueAt,
            String declineReason,
            Instant createdAt,
            Instant closedAt) {
        this.id = id;
        this.familyId = familyId;
        this.title = requireValidTitle(title);
        this.creatorId = creatorId;
        this.assignee = requireAssignee(assignee);
        this.status = status;
        this.dueAt = dueAt;
        this.declineReason = declineReason;
        this.createdAt = createdAt;
        this.closedAt = closedAt;
    }

    public static Task create(
            long id,
            long familyId,
            String title,
            long creatorId,
            Assignee assignee,
            Instant dueAt,
            Instant createdAt) {
        return new Task(
                id,
                familyId,
                title,
                creatorId,
                assignee,
                TaskStatus.OPEN,
                dueAt,
                null,
                createdAt,
                null);
    }

    /** Восстановление из хранилища: состояние приходит как есть, без переигрывания переходов. */
    public static Task restore(
            long id,
            long familyId,
            String title,
            long creatorId,
            Assignee assignee,
            TaskStatus status,
            Instant dueAt,
            String declineReason,
            Instant createdAt,
            Instant closedAt) {
        return new Task(
                id,
                familyId,
                title,
                creatorId,
                assignee,
                status,
                dueAt,
                declineReason,
                createdAt,
                closedAt);
    }

    // --- переходы ---

    /** Закрыть: исполнитель, автор или любой родитель семьи. */
    public void complete(Actor actor, Instant now) {
        requireMember(actor, this::isAssignee, this::isCreator, this::isParent);
        requireOpen();

        status = TaskStatus.DONE;
        closedAt = now;
    }

    /** Отказаться: только исполнитель. Отказ — ответ на просьбу, отвечать может лишь адресат. */
    public void decline(Actor actor, String reason, Instant now) {
        requireMember(actor, this::isAssignee);
        requireOpen();
        if (isBlank(reason)) {
            throw new IllegalArgumentException("decline reason is required");
        }

        status = TaskStatus.DECLINED;
        declineReason = reason;
        closedAt = now;
    }

    /** Вернуть в работу: исполнитель или автор. Родителю со стороны — нет. */
    public void reopen(Actor actor) {
        requireMember(actor, this::isAssignee, this::isCreator);
        if (!status.isClosed()) {
            throw invalidTransition("task is already open");
        }

        status = TaskStatus.OPEN;
        declineReason = null;
        closedAt = null;
    }

    /** Править: автор; родитель — только если исполнитель ребёнок. */
    public void edit(Actor actor, String newTitle, Instant newDueAt) {
        requireEditor(actor);
        requireOpen();

        title = requireValidTitle(newTitle);
        dueAt = newDueAt;
    }

    /** Удаление стирает строку, поэтому домен только проверяет право — состояние не важно. */
    public void assertDeletableBy(Actor actor) {
        requireEditor(actor);
    }

    /**
     * Системное закрытие: участника исключили из семьи, его открытые задачи закрываются от имени
     * системы. Обычному участнику этот переход недоступен — иначе он обходит правило «decline
     * только исполнителю».
     */
    public void cancelBySystem(Actor actor, String reason, Instant now) {
        if (!(actor instanceof Actor.SystemActor)) {
            throw new DomainException.NotPermitted("only the system may cancel a task");
        }
        requireOpen();
        if (isBlank(reason)) {
            throw new IllegalArgumentException("cancellation reason is required");
        }

        status = TaskStatus.DECLINED;
        declineReason = reason;
        closedAt = now;
    }

    // --- права: вопросы ---

    /**
     * Ответы для вёрстки кнопок. Набор кнопок обязан отражать права, но не он их обеспечивает:
     * каждый переход проверяет актора заново, потому что нажатие приходит подделываемой строкой.
     *
     * <p>Вьюха спрашивает домен, а не повторяет его правила у себя, — иначе два набора условий
     * разойдутся, и кнопка «Готово» появится там, где нажатие даст отказ.
     */
    public boolean mayComplete(Actor actor) {
        return !status.isClosed()
                && allows(actor, this::isAssignee, this::isCreator, this::isParent);
    }

    public boolean mayDecline(Actor actor) {
        return !status.isClosed() && allows(actor, this::isAssignee);
    }

    public boolean mayReopen(Actor actor) {
        return status.isClosed() && allows(actor, this::isAssignee, this::isCreator);
    }

    public boolean mayModify(Actor actor) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            return false;
        }
        return isCreator(member) || (isParent(member) && assignee.isChild());
    }

    @SafeVarargs
    private boolean allows(Actor actor, java.util.function.Predicate<Actor.MemberActor>... any) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            return false;
        }
        for (java.util.function.Predicate<Actor.MemberActor> rule : any) {
            if (rule.test(member)) {
                return true;
            }
        }
        return false;
    }

    // --- права: проверки ---

    @SafeVarargs
    private void requireMember(Actor actor, java.util.function.Predicate<Actor.MemberActor>... any) {
        Actor.MemberActor member = asFamilyMember(actor);
        for (java.util.function.Predicate<Actor.MemberActor> rule : any) {
            if (rule.test(member)) {
                return;
            }
        }
        throw new DomainException.NotPermitted("actor may not act on this task");
    }

    private void requireEditor(Actor actor) {
        Actor.MemberActor member = asFamilyMember(actor);
        if (isCreator(member) || (isParent(member) && assignee.isChild())) {
            return;
        }
        throw new DomainException.NotPermitted("actor may not modify this task");
    }

    /**
     * Защита в глубину: изоляцию держит фильтр по {@code family_id} в SQL, но подделанный {@code
     * callback_data} с чужим id не должен пройти и здесь.
     */
    private Actor.MemberActor asFamilyMember(Actor actor) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            throw new DomainException.NotPermitted("actor does not belong to this family");
        }
        return member;
    }

    private boolean isAssignee(Actor.MemberActor member) {
        return member.memberId() == assignee.memberId();
    }

    private boolean isCreator(Actor.MemberActor member) {
        return member.memberId() == creatorId;
    }

    private boolean isParent(Actor.MemberActor member) {
        return member.role() == Role.PARENT;
    }

    // --- состояние и валидация ---

    private void requireOpen() {
        if (status.isClosed()) {
            throw invalidTransition("task is already closed");
        }
    }

    private DomainException.InvalidTransition invalidTransition(String message) {
        return new DomainException.InvalidTransition(status, message);
    }

    private static Assignee requireAssignee(Assignee assignee) {
        if (assignee == null) {
            throw new IllegalArgumentException("assignee is required");
        }
        return assignee;
    }

    private static String requireValidTitle(String title) {
        if (isBlank(title)) {
            throw new IllegalArgumentException("title is required");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title is longer than " + MAX_TITLE_LENGTH);
        }
        return title;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- чтение ---

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public String title() {
        return title;
    }

    public long creatorId() {
        return creatorId;
    }

    public Assignee assignee() {
        return assignee;
    }

    public TaskStatus status() {
        return status;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public String declineReason() {
        return declineReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public boolean isSelfAssigned() {
        return creatorId == assignee.memberId();
    }
}

package com.familytodo.domain;

import java.time.Instant;

/**
 * Позиция списка покупок. Ни Spring, ни JPA, ни одной аннотации — как и у {@link Task}.
 *
 * <p>Это <b>не задача</b>, и разница не в размере. У позиции нет исполнителя, срока и отказа: её
 * никому не поручают, о ней не напоминают и от неё нельзя отказаться с причиной. Заведи мы её как
 * {@code Task}, сорок строк «молоко» немедленно засорили бы {@code /my}, дайджест и календарь —
 * три экрана, ради которых всё и делалось.
 *
 * <p>Роль не ограничивает ничего: список покупок общий, и ребёнок вносит заказы наравне со взрослым.
 * От прав остаётся одна проверка — принадлежность семье. Это защита в глубину: изоляцию держит
 * фильтр по {@code family_id} в SQL, но подделанный {@code callback_data} не должен проходить и
 * здесь.
 */
public final class ShoppingItem {

    /**
     * Короче, чем у задачи: «молоко 2.5%» — это позиция, а рецепт — уже нет. Ограничение отбивает не
     * длину строки, а попытку вести в списке покупок переписку.
     */
    public static final int MAX_TITLE_LENGTH = 100;

    private final long id;
    private final long familyId;
    private final ShoppingList list;
    private final String title;
    private final long addedBy;
    private final Instant addedAt;

    private Long boughtBy;
    private Instant boughtAt;

    private ShoppingItem(
            long id,
            long familyId,
            ShoppingList list,
            String title,
            long addedBy,
            Instant addedAt,
            Long boughtBy,
            Instant boughtAt) {
        this.id = id;
        this.familyId = familyId;
        this.list = requireList(list);
        this.title = requireValidTitle(title);
        this.addedBy = addedBy;
        this.addedAt = addedAt;
        this.boughtBy = boughtBy;
        this.boughtAt = boughtAt;
    }

    /** Внести позицию в список. Может любой член семьи, роль значения не имеет. */
    public static ShoppingItem add(
            long id,
            long familyId,
            ShoppingList list,
            String title,
            Actor actor,
            Instant addedAt) {
        Actor.MemberActor member = requireFamilyMember(actor, familyId);
        return new ShoppingItem(
                id, familyId, list, title, member.memberId(), addedAt, null, null);
    }

    /** Путь из {@code RowMapper}: состояние берётся из строки как есть, права не при чём. */
    public static ShoppingItem restore(
            long id,
            long familyId,
            ShoppingList list,
            String title,
            long addedBy,
            Instant addedAt,
            Long boughtBy,
            Instant boughtAt) {
        return new ShoppingItem(id, familyId, list, title, addedBy, addedAt, boughtBy, boughtAt);
    }

    /**
     * Отметить купленным. Вычёркивает кто угодно из семьи: купил — отметил, независимо от того, кто
     * просил.
     *
     * <p>Повторное нажатие не ошибка, но покупателя и время не переписывает: первым купил тот, кто
     * купил. Список перерисовывается одним сообщением, и два тапа подряд по подтормаживающей связи —
     * норма, а не попытка что-то сломать.
     */
    public void markBought(Actor actor, Instant now) {
        Actor.MemberActor member = requireFamilyMember(actor, familyId);
        if (isBought()) {
            return;
        }
        boughtBy = member.memberId();
        boughtAt = now;
    }

    /** Вернуть в список: положили в корзину не то, или позиция понадобилась снова. */
    public void unmarkBought(Actor actor) {
        requireFamilyMember(actor, familyId);
        boughtBy = null;
        boughtAt = null;
    }

    public boolean isBought() {
        return boughtAt != null;
    }

    public long id() {
        return id;
    }

    public long familyId() {
        return familyId;
    }

    public ShoppingList list() {
        return list;
    }

    public String title() {
        return title;
    }

    public long addedBy() {
        return addedBy;
    }

    public Instant addedAt() {
        return addedAt;
    }

    public Long boughtBy() {
        return boughtBy;
    }

    public Instant boughtAt() {
        return boughtAt;
    }

    // --- права и валидация ---

    private static Actor.MemberActor requireFamilyMember(Actor actor, long familyId) {
        if (!(actor instanceof Actor.MemberActor member) || member.familyId() != familyId) {
            throw new DomainException.NotPermitted("actor does not belong to this family");
        }
        return member;
    }

    private static ShoppingList requireList(ShoppingList list) {
        if (list == null) {
            throw new IllegalArgumentException("list is required");
        }
        return list;
    }

    private static String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title is longer than " + MAX_TITLE_LENGTH);
        }
        return title;
    }
}

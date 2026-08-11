package com.familytodo.domain;

import java.time.Instant;

/**
 * Назначение: кому поручено дело и что человек на это ответил.
 *
 * <p>Отличается от {@link Assignee} тем же, чем просьба отличается от ответа на неё. {@code
 * Assignee} — это то, что называют при создании и при добавлении («сделает папа»), {@code
 * Assignment} — то, что хранится: тот же человек плюс его отказ, если он был.
 *
 * <p>Отказ живёт <b>здесь</b>, а не в задаче, и причина тому не техническая. «Сделано» — факт о
 * мире: к врачу сходили, и второму родителю держать это в голове больше не нужно. Отказ — ответ на
 * просьбу, и отвечает на неё каждый адресат за себя. Поэтому у одного дела причин ровно столько,
 * сколько отказавшихся.
 *
 * <p>Роль — снимок на момент загрузки, как и в {@link Assignee}: без неё правило «PARENT правит
 * дело, если среди исполнителей есть CHILD» потребовало бы тащить в домен репозиторий участников.
 */
public record Assignment(long memberId, Role role, Instant declinedAt, String declineReason) {

    public Assignment {
        if (role == null) {
            throw new IllegalArgumentException("assignment role is required");
        }
    }

    public static Assignment of(Assignee assignee) {
        return new Assignment(assignee.memberId(), assignee.role(), null, null);
    }

    public boolean hasDeclined() {
        return declinedAt != null;
    }

    public boolean isChild() {
        return role == Role.CHILD;
    }

    Assignment declined(String reason, Instant at) {
        return new Assignment(memberId, role, at, reason);
    }

    Assignment withoutRefusal() {
        return new Assignment(memberId, role, null, null);
    }
}

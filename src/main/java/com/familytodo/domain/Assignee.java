package com.familytodo.domain;

/**
 * Исполнитель задачи: идентификатор плюс роль на момент загрузки.
 *
 * <p>Роль здесь не денормализация в БД, а снимок, который подтягивает маппер: без неё нельзя
 * проверить правило «PARENT правит задачу, только если исполнитель CHILD», не таща в домен
 * репозиторий участников.
 */
public record Assignee(long memberId, Role role) {

    public Assignee {
        if (role == null) {
            throw new IllegalArgumentException("assignee role is required");
        }
    }

    public boolean isChild() {
        return role == Role.CHILD;
    }
}

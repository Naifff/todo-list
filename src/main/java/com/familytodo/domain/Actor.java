package com.familytodo.domain;

/**
 * Тот, кто выполняет действие: либо участник семьи, либо сама система.
 *
 * <p>Системный актор нужен для автоматических переходов — исключение участника закрывает его
 * открытые задачи, хотя {@code decline} доступен только исполнителю. Без отдельного типа это
 * правило пришлось бы ослабить, то есть открыть обход для всех.
 */
public sealed interface Actor {

    static Actor member(long memberId, long familyId, Role role) {
        return new MemberActor(memberId, familyId, role);
    }

    static Actor system() {
        return SystemActor.INSTANCE;
    }

    record MemberActor(long memberId, long familyId, Role role) implements Actor {

        public MemberActor {
            if (role == null) {
                throw new IllegalArgumentException("actor role is required");
            }
        }
    }

    enum SystemActor implements Actor {
        INSTANCE
    }
}

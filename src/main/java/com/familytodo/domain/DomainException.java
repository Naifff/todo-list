package com.familytodo.domain;

/**
 * Ровно три типа доменных ошибок. Больше не нужно: адаптеру бота требуется различать «нельзя»,
 * «состояние не то» и «не найдено» — по одному ответу пользователю на каждый.
 */
public abstract sealed class DomainException extends RuntimeException {

    private DomainException(String message) {
        super(message);
    }

    /** Актору не разрешено это действие — в том числе если задача из чужой семьи. */
    public static final class NotPermitted extends DomainException {
        public NotPermitted(String message) {
            super(message);
        }
    }

    /**
     * Переход невозможен из текущего состояния. Статус — часть ошибки: по нему бот выбирает ответ
     * («уже отмечено» для {@code DONE}, «задача и так открыта» для {@code OPEN}).
     */
    public static final class InvalidTransition extends DomainException {
        private final TaskStatus currentStatus;

        public InvalidTransition(TaskStatus currentStatus, String message) {
            super(message);
            this.currentStatus = currentStatus;
        }

        public TaskStatus currentStatus() {
            return currentStatus;
        }
    }

    public static final class NotFound extends DomainException {
        public NotFound(String message) {
            super(message);
        }
    }
}

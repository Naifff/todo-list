package com.familytodo.domain;

public enum TaskStatus {
    OPEN,
    DONE,
    DECLINED;

    public boolean isClosed() {
        return this != OPEN;
    }
}

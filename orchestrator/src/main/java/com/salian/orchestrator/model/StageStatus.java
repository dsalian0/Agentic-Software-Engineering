package com.salian.orchestrator.model;

public enum StageStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_CLARIFICATION,
    SUCCEEDED,
    SKIPPED,
    FAILED;

    /** Satisfies downstream dependencies. */
    public boolean isDone() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}

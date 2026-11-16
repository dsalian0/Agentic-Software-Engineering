package com.salian.orchestrator.model;

public enum RunStatus {
    RUNNING,
    WAITING_APPROVAL,
    WAITING_CLARIFICATION,
    /** Halted by a control (policy, exhausted recovery, rejection, timeout, budget, kill switch). Resumable. */
    SAFE_STOPPED,
    /** An upstream artifact was amended; the dependent sub-graph was invalidated and awaits resume. */
    REPLANNED,
    COMPLETED;

    public boolean isWaiting() {
        return this == WAITING_APPROVAL || this == WAITING_CLARIFICATION;
    }
}

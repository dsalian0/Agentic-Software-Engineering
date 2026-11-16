package com.salian.orchestrator.engine;

/** Why a run is being safe-stopped. Every trigger leaves the run resumable after human action. */
public record StopSignal(Trigger trigger, String stageId, String message) {

    public enum Trigger {
        KILL_SWITCH,
        BUDGET_EXCEEDED,
        STAGE_FAILED,
        REWORK_EXHAUSTED,
        APPROVAL_REJECTED,
        APPROVAL_TIMEOUT,
        CLARIFICATION_TIMEOUT,
        ENTRY_GATE_FAILED,
        NO_PROGRESS
    }
}

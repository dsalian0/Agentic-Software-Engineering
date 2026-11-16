package com.salian.orchestrator.audit;

/** Audit event vocabulary (string constants keep the JSONL readable and forward compatible). */
public final class EventType {

    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_RESUMED = "RUN_RESUMED";
    public static final String RUN_PAUSED = "RUN_PAUSED";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String SAFE_STOP = "SAFE_STOP";

    public static final String STAGE_SKIPPED = "STAGE_SKIPPED";
    public static final String STAGE_STARTED = "STAGE_STARTED";
    public static final String AGENT_INVOKED = "AGENT_INVOKED";
    public static final String FAULT_INJECTED = "FAULT_INJECTED";
    public static final String STAGE_ATTEMPT_FAILED = "STAGE_ATTEMPT_FAILED";
    public static final String RETRY_SCHEDULED = "RETRY_SCHEDULED";
    public static final String FALLBACK_ACTIVATED = "FALLBACK_ACTIVATED";
    public static final String GATE_EVALUATED = "GATE_EVALUATED";
    public static final String STAGE_SUCCEEDED = "STAGE_SUCCEEDED";
    public static final String STAGE_FAILED = "STAGE_FAILED";
    public static final String STAGE_INVALIDATED = "STAGE_INVALIDATED";
    public static final String STAGE_RECOVERED = "STAGE_RECOVERED";

    public static final String ARTIFACT_WRITTEN = "ARTIFACT_WRITTEN";
    public static final String DECISION_RECORDED = "DECISION_RECORDED";
    public static final String POLICY_EVALUATED = "POLICY_EVALUATED";

    public static final String ROLLBACK_COMPLETED = "ROLLBACK_COMPLETED";
    public static final String REWORK_TRIGGERED = "REWORK_TRIGGERED";
    public static final String REPLAN_TRIGGERED = "REPLAN_TRIGGERED";
    public static final String ARTIFACT_AMENDED = "ARTIFACT_AMENDED";

    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
    public static final String APPROVAL_GRANTED = "APPROVAL_GRANTED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String APPROVAL_TIMED_OUT = "APPROVAL_TIMED_OUT";
    public static final String APPROVAL_VOIDED = "APPROVAL_VOIDED";
    public static final String CLARIFICATION_REQUESTED = "CLARIFICATION_REQUESTED";
    public static final String CLARIFICATION_RECEIVED = "CLARIFICATION_RECEIVED";
    public static final String CLARIFICATION_TIMED_OUT = "CLARIFICATION_TIMED_OUT";

    public static final String KILL_SWITCH_SET = "KILL_SWITCH_SET";
    public static final String KILL_SWITCH_CLEARED = "KILL_SWITCH_CLEARED";

    private EventType() {
    }
}

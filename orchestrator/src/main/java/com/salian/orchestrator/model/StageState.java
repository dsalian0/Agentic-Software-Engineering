package com.salian.orchestrator.model;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StageState {

    private String stageId;
    private StageStatus status = StageStatus.PENDING;
    /** Attempts in the current execution (reset when the stage is invalidated/reworked). */
    private int attempts;
    /** How many times this stage has been executed to completion or failure (grows with rework/replan). */
    private int executions;
    private Instant startedAt;
    private Instant finishedAt;
    private String agentUsed;
    private String executionMode;
    private boolean usedFallback;
    private String lastError;
    private String lastFailureKind;
    private String summary;

    public StageState(String stageId) {
        this.stageId = stageId;
    }

    public void reset() {
        status = StageStatus.PENDING;
        attempts = 0;
        startedAt = null;
        finishedAt = null;
        agentUsed = null;
        executionMode = null;
        usedFallback = false;
        lastError = null;
        lastFailureKind = null;
        summary = null;
    }
}

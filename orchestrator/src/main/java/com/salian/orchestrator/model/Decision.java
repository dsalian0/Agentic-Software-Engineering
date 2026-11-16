package com.salian.orchestrator.model;

import java.time.Instant;
import java.util.List;

/**
 * A recorded engineering decision: what was decided, why, what else was considered, and which
 * artifact versions it was based on.
 */
public record Decision(String id, String stageId, int stageExecution, String summary, String rationale,
                       List<String> alternatives, List<String> inputs, Instant timestamp) {
}

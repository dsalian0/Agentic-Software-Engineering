package com.salian.orchestrator.model;

/**
 * Non-linear recovery: when the owning stage fails terminally, roll back {@code stage} and everything
 * downstream of it, publish the failure details as {@code feedbackArtifact}, and re-run from
 * {@code stage}. Bounded by {@code maxReworks}; beyond that the run safe-stops.
 */
public record ReworkPolicy(String stage, int maxReworks, String feedbackArtifact) {

    public ReworkPolicy {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("rework stage is required");
        }
        if (maxReworks < 1 || maxReworks > 5) {
            throw new IllegalArgumentException("maxReworks must be between 1 and 5");
        }
    }
}

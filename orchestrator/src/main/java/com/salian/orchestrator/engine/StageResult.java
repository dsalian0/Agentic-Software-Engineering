package com.salian.orchestrator.engine;

/**
 * Outcome of one agent attempt. {@code details} travels with failures (e.g. policy findings) and
 * becomes the feedback artifact when a rework policy routes the failure upstream.
 */
public record StageResult(boolean success, String summary, FailureKind failureKind, Object details) {

    public static StageResult ok(String summary) {
        return new StageResult(true, summary, null, null);
    }

    public static StageResult fail(FailureKind kind, String message) {
        return new StageResult(false, message, kind, null);
    }

    public static StageResult fail(FailureKind kind, String message, Object details) {
        return new StageResult(false, message, kind, details);
    }
}

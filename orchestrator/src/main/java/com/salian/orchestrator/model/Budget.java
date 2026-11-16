package com.salian.orchestrator.model;

/**
 * Hard limits that trigger a safe-stop when exceeded.
 *
 * @param maxStageExecutions total agent executions (attempts) allowed across the run
 * @param maxWallClockMs     wall-clock limit for one drive of the run (resume starts a new window)
 */
public record Budget(int maxStageExecutions, long maxWallClockMs) {

    public static final Budget DEFAULT = new Budget(80, 30 * 60_000L);

    public Budget {
        if (maxStageExecutions <= 0) {
            maxStageExecutions = DEFAULT_EXECUTIONS;
        }
        if (maxWallClockMs <= 0) {
            maxWallClockMs = DEFAULT_WALL_CLOCK;
        }
    }

    private static final int DEFAULT_EXECUTIONS = 80;
    private static final long DEFAULT_WALL_CLOCK = 30 * 60_000L;
}

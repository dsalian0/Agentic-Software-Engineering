package com.salian.orchestrator.metrics;

/**
 * Reliability metrics for one run, derived only from its audit log (so they are reproducible and
 * cannot drift from what actually happened).
 *
 * @param endToEndMs          RUN_STARTED to RUN_COMPLETED (or to the last event if not completed)
 * @param humanWaitMs         time the run sat paused on approvals/clarifications/safe-stop/replan
 * @param activeMs            endToEndMs - humanWaitMs (engine + agent time)
 * @param stageSuccessRate    agent-stage executions that succeeded / agent-stage executions that ended
 * @param firstAttemptSuccess agent-stage executions that succeeded on attempt 1 / succeeded executions
 * @param mttrMs              mean time from a failed attempt to that stage's next success (wall clock)
 * @param mttrActiveMs        same, excluding time paused for humans
 */
public record RunMetrics(
        String runId,
        String scenario,
        String finalStatus,
        long endToEndMs,
        long humanWaitMs,
        long activeMs,
        int agentInvocations,
        int stageSucceeded,
        int stageFailed,
        double stageSuccessRate,
        double firstAttemptSuccess,
        int failedAttempts,
        int retries,
        int fallbacks,
        int rollbacks,
        int reworks,
        int replans,
        int safeStops,
        int injectedFaults,
        int approvalsInteractive,
        int approvalsDelegated,
        int approvalsRejected,
        int policyEvaluations,
        int incidents,
        int recoveredIncidents,
        long mttrMs,
        long mttrActiveMs,
        boolean auditChainIntact) {
}

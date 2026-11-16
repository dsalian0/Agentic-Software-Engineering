package com.salian.orchestrator.metrics;

import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.AuditLog;
import com.salian.orchestrator.audit.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Derives {@link RunMetrics} from audit events, and aggregates them across runs. */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static RunMetrics forRun(String runId, String scenario, String finalStatus, List<AuditEvent> events) {
        Instant start = events.isEmpty() ? Instant.EPOCH : events.get(0).timestamp();
        Instant end = events.isEmpty() ? start : events.get(events.size() - 1).timestamp();
        for (AuditEvent e : events) {
            if (EventType.RUN_COMPLETED.equals(e.type())) {
                end = e.timestamp();
            }
        }

        List<Instant[]> pauses = pauseIntervals(events, end);
        long humanWait = pauses.stream().mapToLong(p -> Duration.between(p[0], p[1]).toMillis()).sum();
        long e2e = Duration.between(start, end).toMillis();

        int invocations = 0;
        int succeeded = 0;
        int failed = 0;
        int firstAttempt = 0;
        int failedAttempts = 0;
        int retries = 0;
        int fallbacks = 0;
        int rollbacks = 0;
        int reworks = 0;
        int replans = 0;
        int safeStops = 0;
        int injected = 0;
        int interactive = 0;
        int delegated = 0;
        int rejected = 0;
        int policy = 0;
        Map<String, List<Instant>> openIncidents = new HashMap<>();
        List<Long> recoveries = new ArrayList<>();
        List<Long> activeRecoveries = new ArrayList<>();
        int incidents = 0;

        for (AuditEvent e : events) {
            switch (e.type()) {
                case EventType.AGENT_INVOKED -> invocations++;
                case EventType.STAGE_SUCCEEDED -> {
                    if (e.data().has("agent")) {
                        succeeded++;
                        if (e.data().path("attempts").asInt() == 1 && !e.data().path("usedFallback").asBoolean()) {
                            firstAttempt++;
                        }
                    }
                    List<Instant> open = openIncidents.remove(e.stageId());
                    if (open != null) {
                        for (Instant failedAt : open) {
                            long wall = Duration.between(failedAt, e.timestamp()).toMillis();
                            recoveries.add(wall);
                            activeRecoveries.add(Math.max(0, wall - overlap(pauses, failedAt, e.timestamp())));
                        }
                    }
                }
                case EventType.STAGE_FAILED -> {
                    if (!"APPROVAL_REJECTED".equals(e.data().path("kind").asText())) {
                        failed++;
                    }
                }
                case EventType.STAGE_ATTEMPT_FAILED -> {
                    failedAttempts++;
                    incidents++;
                    openIncidents.computeIfAbsent(e.stageId(), k -> new ArrayList<>()).add(e.timestamp());
                }
                case EventType.RETRY_SCHEDULED -> retries++;
                case EventType.FALLBACK_ACTIVATED -> fallbacks++;
                case EventType.ROLLBACK_COMPLETED -> rollbacks++;
                case EventType.REWORK_TRIGGERED -> reworks++;
                case EventType.REPLAN_TRIGGERED -> replans++;
                case EventType.SAFE_STOP -> safeStops++;
                case EventType.FAULT_INJECTED -> injected++;
                case EventType.POLICY_EVALUATED -> policy++;
                case EventType.APPROVAL_GRANTED -> {
                    if ("DELEGATED".equals(e.data().path("mode").asText())) {
                        delegated++;
                    } else {
                        interactive++;
                    }
                }
                case EventType.APPROVAL_REJECTED -> rejected++;
                default -> { }
            }
        }
        int ended = succeeded + failed;
        return new RunMetrics(runId, scenario, finalStatus, e2e, humanWait, Math.max(0, e2e - humanWait),
                invocations, succeeded, failed,
                ended == 0 ? 0 : (double) succeeded / ended,
                succeeded == 0 ? 0 : (double) firstAttempt / succeeded,
                failedAttempts, retries, fallbacks, rollbacks, reworks, replans, safeStops, injected,
                interactive, delegated, rejected, policy,
                incidents, recoveries.size(), mean(recoveries), mean(activeRecoveries),
                AuditLog.verify(events).intact());
    }

    public static AggregateMetrics aggregate(List<RunMetrics> runs) {
        int completed = (int) runs.stream().filter(r -> "COMPLETED".equals(r.finalStatus())).count();
        int stopped = (int) runs.stream().filter(r -> "SAFE_STOPPED".equals(r.finalStatus())).count();
        int executions = runs.stream().mapToInt(RunMetrics::agentInvocations).sum();
        int retries = runs.stream().mapToInt(RunMetrics::retries).sum();
        int rollbacks = runs.stream().mapToInt(RunMetrics::rollbacks).sum();
        List<Long> e2e = runs.stream().filter(r -> "COMPLETED".equals(r.finalStatus()))
                .map(RunMetrics::endToEndMs).sorted().toList();
        List<Long> active = runs.stream().filter(r -> "COMPLETED".equals(r.finalStatus()))
                .map(RunMetrics::activeMs).sorted().toList();
        long incidents = runs.stream().mapToLong(RunMetrics::recoveredIncidents).sum();
        double mttr = incidents == 0 ? 0 : runs.stream()
                .mapToDouble(r -> (double) r.mttrMs() * r.recoveredIncidents()).sum() / incidents;
        double mttrActive = incidents == 0 ? 0 : runs.stream()
                .mapToDouble(r -> (double) r.mttrActiveMs() * r.recoveredIncidents()).sum() / incidents;
        int terminal = completed + stopped;
        return new AggregateMetrics(runs.size(), completed, stopped,
                terminal == 0 ? 0 : (double) completed / terminal,
                executions,
                executions == 0 ? 0 : (double) retries / executions,
                runs.isEmpty() ? 0 : (double) rollbacks / runs.size(),
                runs.stream().mapToInt(RunMetrics::reworks).sum(),
                runs.stream().mapToInt(RunMetrics::replans).sum(),
                runs.stream().mapToInt(RunMetrics::safeStops).sum(),
                (int) incidents, (long) mttr, (long) mttrActive,
                percentile(e2e, 50), percentile(e2e, 95), percentile(active, 50), percentile(active, 95),
                runs.stream().allMatch(RunMetrics::auditChainIntact));
    }

    /** Intervals in which the run waited on humans: RUN_PAUSED / SAFE_STOP / ARTIFACT_AMENDED until RUN_RESUMED. */
    private static List<Instant[]> pauseIntervals(List<AuditEvent> events, Instant end) {
        List<Instant[]> intervals = new ArrayList<>();
        Instant pausedAt = null;
        for (AuditEvent e : events) {
            switch (e.type()) {
                case EventType.RUN_PAUSED, EventType.SAFE_STOP -> {
                    if (pausedAt == null) {
                        pausedAt = e.timestamp();
                    }
                }
                case EventType.RUN_RESUMED -> {
                    if (pausedAt != null) {
                        intervals.add(new Instant[] {pausedAt, e.timestamp()});
                        pausedAt = null;
                    }
                }
                default -> { }
            }
        }
        if (pausedAt != null && end.isAfter(pausedAt)) {
            intervals.add(new Instant[] {pausedAt, end});
        }
        return intervals;
    }

    private static long overlap(List<Instant[]> intervals, Instant from, Instant to) {
        long total = 0;
        for (Instant[] interval : intervals) {
            Instant s = interval[0].isAfter(from) ? interval[0] : from;
            Instant e = interval[1].isBefore(to) ? interval[1] : to;
            if (e.isAfter(s)) {
                total += Duration.between(s, e).toMillis();
            }
        }
        return total;
    }

    private static long mean(List<Long> values) {
        return values.isEmpty() ? 0 : (long) values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    public record AggregateMetrics(
            int runs,
            int completed,
            int safeStopped,
            double successRate,
            int agentInvocations,
            double retryFrequency,
            double rollbacksPerRun,
            int reworks,
            int replans,
            int safeStops,
            int recoveredIncidents,
            long mttrMs,
            long mttrActiveMs,
            long e2eP50Ms,
            long e2eP95Ms,
            long activeP50Ms,
            long activeP95Ms,
            boolean allAuditChainsIntact) {
    }
}

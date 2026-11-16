package com.salian.orchestrator.metrics;

import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.AuditLog;
import com.salian.orchestrator.audit.EventType;
import com.salian.orchestrator.model.ApprovalRecord;
import com.salian.orchestrator.model.ArtifactVersion;
import com.salian.orchestrator.model.Decision;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.StageState;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renders a reviewer-facing Markdown report (and metrics JSON) for a run from its state and audit log. */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static Path write(RunState state, List<AuditEvent> events, Path runDir) {
        RunMetrics metrics = MetricsCalculator.forRun(state.getRunId(), state.getScenario(), state.getStatus().name(), events);
        StringBuilder md = new StringBuilder();
        md.append("# Run report: ").append(state.getRunId()).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Scenario | ").append(state.getScenario()).append(" |\n");
        md.append("| Workflow | ").append(state.getWorkflowId()).append("@").append(state.getWorkflowVersion()).append(" |\n");
        md.append("| Status | **").append(state.getStatus()).append("** ")
                .append(state.getStatusReason() == null ? "" : "(" + state.getStatusReason() + ")").append(" |\n");
        md.append("| Audit chain | ").append(AuditLog.verify(events).intact() ? "intact" : "BROKEN").append(" (")
                .append(events.size()).append(" events) |\n\n");

        md.append("## Stages\n\n| Stage | Status | Agent | Mode | Executions | Attempts | Fallback | Summary |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (StageState s : state.getStages().values()) {
            md.append("| ").append(s.getStageId()).append(" | ").append(s.getStatus()).append(" | ")
                    .append(nz(s.getAgentUsed())).append(" | ").append(nz(s.getExecutionMode())).append(" | ")
                    .append(s.getExecutions()).append(" | ").append(s.getAttempts()).append(" | ")
                    .append(s.isUsedFallback() ? "yes" : "").append(" | ")
                    .append(cell(s.getSummary() != null ? s.getSummary() : s.getLastError())).append(" |\n");
        }

        md.append("\n## Reliability metrics\n\n| Metric | Value |\n|---|---|\n");
        row(md, "End-to-end latency", metrics.endToEndMs() + " ms (active " + metrics.activeMs() + " ms, human wait "
                + metrics.humanWaitMs() + " ms)");
        row(md, "Agent-stage success rate", pct(metrics.stageSuccessRate()) + " (" + metrics.stageSucceeded() + " ok / "
                + metrics.stageFailed() + " failed)");
        row(md, "First-attempt success", pct(metrics.firstAttemptSuccess()));
        row(md, "Agent invocations", String.valueOf(metrics.agentInvocations()));
        row(md, "Failed attempts / retries / fallbacks", metrics.failedAttempts() + " / " + metrics.retries() + " / " + metrics.fallbacks());
        row(md, "Rollbacks / reworks / replans", metrics.rollbacks() + " / " + metrics.reworks() + " / " + metrics.replans());
        row(md, "Safe-stops", String.valueOf(metrics.safeStops()));
        row(md, "MTTR (wall / active)", metrics.mttrMs() + " ms / " + metrics.mttrActiveMs() + " ms over "
                + metrics.recoveredIncidents() + " recovered incident(s)");
        row(md, "Injected faults (labelled)", String.valueOf(metrics.injectedFaults()));
        row(md, "Approvals (interactive / delegated / rejected)", metrics.approvalsInteractive() + " / "
                + metrics.approvalsDelegated() + " / " + metrics.approvalsRejected());

        md.append("\n## Human checkpoints\n\n");
        if (state.getApprovals().isEmpty() && state.getClarifications().isEmpty()) {
            md.append("_None reached._\n");
        }
        for (ApprovalRecord a : state.getApprovals().values()) {
            md.append("- **").append(a.getStageId()).append("**: ").append(a.getStatus());
            if (a.getDecidedBy() != null) {
                md.append(" by ").append(a.getDecidedBy()).append(" (").append(a.getMode()).append(")");
            }
            md.append(" on ").append(a.getInputRefs()).append("\n");
        }
        state.getClarifications().forEach((stage, answers) ->
                md.append("- **").append(stage).append("**: answers ").append(answers).append("\n"));

        md.append("\n## Decisions (lineage)\n\n");
        for (Decision d : state.getDecisions()) {
            md.append("- `").append(d.stageId()).append("#").append(d.stageExecution()).append("` **")
                    .append(d.summary()).append("**: ").append(d.rationale());
            if (!d.inputs().isEmpty()) {
                md.append(" _(based on ").append(String.join(", ", d.inputs())).append(")_");
            }
            md.append("\n");
        }

        md.append("\n## Artifacts\n\n| Artifact | Producer | Derived from | Stale |\n|---|---|---|---|\n");
        state.getArtifacts().values().forEach(versions -> versions.forEach(v -> md.append("| ").append(v.ref())
                .append(" | ").append(v.getProducedBy()).append(" | ").append(String.join(", ", v.getDerivedFrom()))
                .append(" | ").append(v.isStale() ? "yes" : "").append(" |\n")));

        md.append("\n## Notable events\n\n");
        Set<String> notable = Set.of(EventType.FAULT_INJECTED, EventType.RETRY_SCHEDULED, EventType.FALLBACK_ACTIVATED,
                EventType.ROLLBACK_COMPLETED, EventType.REWORK_TRIGGERED, EventType.REPLAN_TRIGGERED,
                EventType.SAFE_STOP, EventType.STAGE_ATTEMPT_FAILED, EventType.APPROVAL_GRANTED,
                EventType.APPROVAL_REJECTED, EventType.CLARIFICATION_REQUESTED, EventType.CLARIFICATION_RECEIVED,
                EventType.ARTIFACT_AMENDED, EventType.STAGE_SKIPPED, EventType.KILL_SWITCH_SET);
        for (AuditEvent e : events) {
            if (notable.contains(e.type())) {
                md.append("- #").append(e.seq()).append(" `").append(e.type()).append("` ")
                        .append(e.stageId() == null ? "" : e.stageId() + " ").append("(").append(e.actor()).append(") ")
                        .append(cell(brief(e))).append("\n");
            }
        }
        md.append("\nExecution modes: REAL = deterministic logic or real tools at run time; SCRIPTED = content "
                + "selected from a pre-authored playbook; LLM = model-backed agent (unavailable in this build).\n");

        try {
            Path report = runDir.resolve("report.md");
            Files.writeString(report, md.toString());
            Files.writeString(runDir.resolve("metrics.json"), Json.pretty(metrics));
            return report;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Lineage tree for an artifact: the versions it was derived from, recursively. */
    public static List<String> lineage(RunState state, String ref) {
        List<String> lines = new ArrayList<>();
        walk(state, ref, 0, lines, new LinkedHashSet<>());
        return lines;
    }

    private static void walk(RunState state, String ref, int depth, List<String> lines, Set<String> seen) {
        int at = ref.lastIndexOf('@');
        String key = at < 0 ? ref : ref.substring(0, at);
        List<ArtifactVersion> versions = state.getArtifacts().getOrDefault(key, List.of());
        ArtifactVersion version = at < 0
                ? state.latest(key).orElse(null)
                : versions.stream().filter(v -> v.ref().equals(ref)).findFirst().orElse(null);
        String indent = "  ".repeat(depth);
        if (version == null) {
            lines.add(indent + ref + " (unknown)");
            return;
        }
        lines.add(indent + version.ref() + "  <- " + version.getProducedBy() + (version.isStale() ? " [stale]" : "")
                + "  sha256:" + version.getContentHash().substring(0, 12));
        if (!seen.add(version.ref())) {
            return;
        }
        for (String parent : version.getDerivedFrom()) {
            walk(state, parent, depth + 1, lines, seen);
        }
    }

    private static String brief(AuditEvent e) {
        String text = e.data() == null ? "" : e.data().toString();
        return text.length() > 220 ? text.substring(0, 217) + "..." : text;
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}

package com.salian.orchestrator.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.EventType;
import java.io.PrintStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Prints engine events as concise, human-readable progress lines. */
public final class EventPrinter implements Consumer<AuditEvent> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PrintStream out;

    public EventPrinter(PrintStream out) {
        this.out = out;
    }

    @Override
    public synchronized void accept(AuditEvent e) {
        JsonNode d = e.data();
        String line = switch (e.type()) {
            case EventType.RUN_STARTED -> "RUN STARTED  scenario=" + d.path("scenario").asText()
                    + " parallel levels=" + d.path("parallelLevels");
            case EventType.STAGE_STARTED -> "  > " + e.stageId() + " started (execution " + d.path("execution").asInt()
                    + ", inputs " + d.path("inputs") + ")";
            case EventType.AGENT_INVOKED -> "    agent " + d.path("agent").asText() + " [" + d.path("mode").asText() + "]"
                    + (d.path("fallback").asBoolean() ? " (FALLBACK)" : "") + " attempt " + e.attempt();
            case EventType.FAULT_INJECTED -> "    !! INJECTED FAULT (" + d.path("kind").asText() + "): " + d.path("message").asText();
            case EventType.STAGE_ATTEMPT_FAILED -> "    x attempt " + e.attempt() + " failed [" + d.path("kind").asText() + "] "
                    + abbreviate(d.path("message").asText());
            case EventType.RETRY_SCHEDULED -> "    ~ retry: next attempt " + d.path("nextAttempt").asText()
                    + " in " + d.path("delayMs").asText() + " ms";
            case EventType.FALLBACK_ACTIVATED -> "    ~ fallback: " + d.path("from").asText() + " -> " + d.path("to").asText();
            case EventType.ROLLBACK_COMPLETED -> "    <- rollback (" + d.path("scope").asText() + ") restored "
                    + d.path("restoredFiles").size() + " file(s)";
            case EventType.GATE_EVALUATED -> d.path("passed").asBoolean() ? null
                    : "    gate " + d.path("gate").asText() + " FAILED: " + abbreviate(d.path("detail").asText());
            case EventType.STAGE_SUCCEEDED -> d.has("approvedBy")
                    ? "  [v] " + e.stageId() + " approved by " + d.path("approvedBy").asText() + " (" + d.path("mode").asText() + ")"
                    : "  v " + e.stageId() + " succeeded in " + d.path("durationMs").asLong() + " ms"
                    + (d.path("usedFallback").asBoolean() ? " via fallback" : "") + ": " + abbreviate(d.path("summary").asText());
            case EventType.STAGE_FAILED -> "  X " + e.stageId() + " FAILED: " + abbreviate(d.path("message").asText());
            case EventType.STAGE_SKIPPED -> "  - " + e.stageId() + " skipped (condition " + d.path("condition").asText() + " is false)";
            case EventType.REWORK_TRIGGERED -> "  <> REWORK: " + d.path("failedStage").asText() + " failed -> re-run from "
                    + d.path("reworkStage").asText() + " (" + d.path("rework").asInt() + "/" + d.path("maxReworks").asInt()
                    + "), invalidating " + d.path("invalidated");
            case EventType.REPLAN_TRIGGERED -> "  <> REPLAN: " + d.path("changedArtifact").asText() + " changed; invalidated "
                    + d.path("invalidated") + "; preserved " + d.path("preserved");
            case EventType.STAGE_INVALIDATED -> "    invalidated " + e.stageId();
            case EventType.APPROVAL_REQUESTED -> "  [?] APPROVAL REQUESTED at " + e.stageId() + " on " + d.path("inputs");
            case EventType.APPROVAL_GRANTED -> "  [+] " + e.stageId() + " approved by " + e.actor();
            case EventType.APPROVAL_REJECTED -> "  [-] " + e.stageId() + " REJECTED by " + e.actor() + ": " + d.path("comment").asText();
            case EventType.APPROVAL_VOIDED -> "    approval voided at " + e.stageId() + " (" + abbreviate(d.path("reason").asText()) + ")";
            case EventType.CLARIFICATION_REQUESTED -> "  [?] CLARIFICATION REQUESTED: " + d.path("questions").size() + " question(s)";
            case EventType.CLARIFICATION_RECEIVED -> "  [+] clarification answers received from " + e.actor();
            case EventType.ARTIFACT_AMENDED -> "  [*] " + d.path("artifact").asText() + " amended by " + e.actor()
                    + ": " + d.path("reason").asText();
            case EventType.RUN_PAUSED -> "RUN PAUSED waiting on " + d.path("waitingOn");
            case EventType.RUN_RESUMED -> "RUN RESUMED (was " + d.path("previousStatus").asText() + ")";
            case EventType.SAFE_STOP -> "RUN SAFE-STOPPED [" + d.path("trigger").asText() + "] " + d.path("message").asText();
            case EventType.RUN_COMPLETED -> "RUN COMPLETED in " + d.path("elapsedMs").asLong() + " ms";
            case EventType.KILL_SWITCH_SET -> "  !! kill switch set by " + e.actor();
            case EventType.STAGE_RECOVERED -> "  recovered interrupted stage " + e.stageId();
            default -> null;
        };
        if (line != null) {
            out.println(TIME.format(e.timestamp()) + " " + line);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 160 ? text.substring(0, 157) + "..." : text;
    }
}

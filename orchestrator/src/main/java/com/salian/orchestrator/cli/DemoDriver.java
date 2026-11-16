package com.salian.orchestrator.cli;

import com.salian.orchestrator.engine.Orchestrator;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.RunStatus;
import com.salian.orchestrator.model.StageStatus;
import com.salian.orchestrator.scenario.ScenarioService;
import java.io.PrintStream;
import java.util.List;

/**
 * Scripted end-to-end demonstrations. The driver stands in for the humans: it answers clarification
 * questions and approves/rejects checkpoints. Every such action is recorded as DELEGATED
 * ({@code delegated:demo-reviewer} / {@code delegated:product-owner}), never as a real person.
 * The engine, agents, gates, builds and policies run for real.
 */
public final class DemoDriver {

    public static final String REVIEWER = "demo-reviewer";
    public static final String PRODUCT_OWNER = "product-owner";

    private final ScenarioService scenarios;
    private final PrintStream out;

    public DemoDriver(ScenarioService scenarios, PrintStream out) {
        this.scenarios = scenarios;
        this.out = out;
    }

    public RunState greenfield(boolean offline) {
        return approveUntilDone(start("greenfield", offline));
    }

    public RunState brownfield(boolean offline) {
        return approveUntilDone(start("brownfield", offline));
    }

    /** Clarify, implement and verify, then amend the requirement at release approval and replan. */
    public RunState ambiguous(boolean offline) {
        RunState state = start("ambiguous", offline);
        boolean amended = false;
        while (state.getStatus() != RunStatus.COMPLETED && state.getStatus() != RunStatus.SAFE_STOPPED) {
            if (state.getStatus() == RunStatus.WAITING_CLARIFICATION) {
                banner("Clarification checkpoint: submitting the product owner's scripted answers "
                        + scenarios.clarificationAnswers("ambiguous"));
                scenarios.orchestrator().clarify(state.getRunId(), "clarify",
                        scenarios.clarificationAnswers("ambiguous"), PRODUCT_OWNER, true);
            } else if (!amended && waitingAt(state, "approve-release")) {
                banner("Product owner amends the requirement before release: " + scenarios.amendmentNote(state.getRunId()));
                scenarios.orchestrator().amend(state.getRunId(), "requirement",
                        scenarios.amendedRequirement(state.getRunId()), PRODUCT_OWNER, scenarios.amendmentNote(state.getRunId()),
                        true);
                amended = true;
            } else {
                approvePending(state);
            }
            state = scenarios.resume(state.getRunId());
        }
        return finish(state);
    }

    /** The reviewer rejects the plan: the run safe-stops, nothing is built or published; resume re-requests approval. */
    public RunState safeStop() {
        RunState state = start("greenfield", true);
        if (waitingAt(state, "approve-plan")) {
            banner("Reviewer REJECTS the plan (scripted delegated decision)");
            scenarios.orchestrator().decideDelegated(state.getRunId(), "approve-plan", REVIEWER,
                    "Rejected for demo: scope must be confirmed with security first", false);
            state = scenarios.resume(state.getRunId());
        }
        banner("Run is " + state.getStatus() + " (" + state.getStatusReason() + "). No code was written; "
                + "resuming re-requests the approval instead of proceeding.");
        state = scenarios.resume(state.getRunId());
        return finish(state);
    }

    private RunState start(String scenario, boolean offline) {
        String runId = scenarios.newRunId(scenario);
        banner("Starting scenario '" + scenario + "' as run " + runId
                + " (approvals and clarifications in this demo are DELEGATED to a scripted reviewer)");
        return scenarios.start(scenario, runId, null, offline);
    }

    private RunState approveUntilDone(RunState state) {
        while (state.getStatus() != RunStatus.COMPLETED && state.getStatus() != RunStatus.SAFE_STOPPED) {
            approvePending(state);
            state = scenarios.resume(state.getRunId());
        }
        return finish(state);
    }

    private void approvePending(RunState state) {
        List<String> waiting = state.getStages().values().stream()
                .filter(s -> s.getStatus() == StageStatus.WAITING_APPROVAL).map(s -> s.getStageId()).toList();
        if (waiting.isEmpty() && state.getStatus() != RunStatus.REPLANNED) {
            throw new IllegalStateException("Run " + state.getRunId() + " is " + state.getStatus() + " with nothing to approve");
        }
        Orchestrator orchestrator = scenarios.orchestrator();
        for (String stage : waiting) {
            banner("Approval checkpoint '" + stage + "': delegated reviewer approves");
            orchestrator.decideDelegated(state.getRunId(), stage, REVIEWER, "approved by scripted demo reviewer", true);
        }
    }

    private static boolean waitingAt(RunState state, String stage) {
        return state.getStages().get(stage).getStatus() == StageStatus.WAITING_APPROVAL;
    }

    private RunState finish(RunState state) {
        banner("Run " + state.getRunId() + " finished: " + state.getStatus()
                + (state.getStatusReason() == null ? "" : " (" + state.getStatusReason() + ")"));
        return state;
    }

    private void banner(String text) {
        out.println();
        out.println("==> " + text);
    }
}

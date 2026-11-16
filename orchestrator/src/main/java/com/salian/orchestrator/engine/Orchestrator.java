package com.salian.orchestrator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.AuditLog;
import com.salian.orchestrator.audit.EventType;
import com.salian.orchestrator.engine.FaultInjector.Fault;
import com.salian.orchestrator.engine.Gate.GateResult;
import com.salian.orchestrator.engine.StopSignal.Trigger;
import com.salian.orchestrator.model.ApprovalRecord;
import com.salian.orchestrator.model.ArtifactVersion;
import com.salian.orchestrator.model.Decision;
import com.salian.orchestrator.model.ReworkPolicy;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.RunStatus;
import com.salian.orchestrator.model.StageDefinition;
import com.salian.orchestrator.model.StageState;
import com.salian.orchestrator.model.StageStatus;
import com.salian.orchestrator.model.StageType;
import com.salian.orchestrator.support.Json;
import com.salian.orchestrator.workspace.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The workflow engine. Drives a run through its dependency graph:
 * <ul>
 *   <li>schedules every stage whose dependencies are done, running independent stages in parallel and
 *       joining on completion (a stage starts only when ALL its dependencies succeeded or were skipped);</li>
 *   <li>evaluates entry gates (conditions, required inputs) and exit gates (named predicates);</li>
 *   <li>applies bounded retries for retryable failures, then a fallback agent, rolling back the sandbox
 *       after every failed attempt;</li>
 *   <li>routes terminal failures to a declared rework stage (non-linear loop) or safe-stops;</li>
 *   <li>pauses at human approval / clarification checkpoints and persists state after every transition;</li>
 *   <li>replans when an upstream artifact is amended by invalidating only the dependent sub-graph.</li>
 * </ul>
 * Every transition is written to the hash-chained audit log.
 */
public final class Orchestrator {

    public static final String SYSTEM = "system:orchestrator";
    public static final String PARAM_AUTO_APPROVE = "autoApproveAs";

    private final WorkflowGraph graph;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final RunStore store;
    private final Clock clock;
    private final Sleeper sleeper;
    private final int parallelism;

    public Orchestrator(WorkflowGraph graph, AgentRegistry agents, GateRegistry gates, RunStore store,
                        Clock clock, Sleeper sleeper, int parallelism) {
        this.graph = graph;
        this.agents = agents;
        this.gates = gates;
        this.store = store;
        this.clock = clock;
        this.sleeper = sleeper;
        this.parallelism = Math.max(1, parallelism);
        validateAgents();
    }

    public WorkflowGraph graph() {
        return graph;
    }

    public RunStore store() {
        return store;
    }

    // ------------------------------------------------------------------ public API

    public RunState start(String runId, String scenario, Map<String, String> parameters, FaultInjector faults) {
        if (store.exists(runId)) {
            throw new IllegalStateException("Run already exists: " + runId);
        }
        RunState state = new RunState();
        state.setRunId(runId);
        state.setScenario(scenario);
        state.setWorkflowId(graph.definition().id());
        state.setWorkflowVersion(graph.definition().version());
        state.setCreatedAt(clock.instant());
        state.setParameters(new LinkedHashMap<>(parameters));
        graph.topologicalOrder().forEach(id -> state.getStages().put(id, new StageState(id)));
        Run run = open(state, faults);
        run.emit(EventType.RUN_STARTED, null, null, SYSTEM, Map.of(
                "scenario", scenario,
                "workflow", graph.definition().id() + "@" + graph.definition().version(),
                "parameters", parameters,
                "parallelLevels", graph.parallelLevels()));
        run.save();
        return drive(run);
    }

    public RunState resume(String runId, FaultInjector faults) {
        RunState state = store.load(runId);
        if (state.getStatus() == RunStatus.COMPLETED) {
            return state;
        }
        Run run = open(state, faults);
        RunStatus previous = state.getStatus();
        synchronized (run.lock) {
            if (Files.exists(run.stopFile())) {
                deleteQuietly(run.stopFile());
                run.emit(EventType.KILL_SWITCH_CLEARED, null, null, SYSTEM, Map.of("reason", "resume requested"));
            }
            for (StageState stage : state.getStages().values()) {
                switch (stage.getStatus()) {
                    case RUNNING -> {
                        // The previous process died mid-stage: undo its partial changes, then re-run it.
                        List<String> restored = run.workspace.rollback(stage.getStageId());
                        stage.reset();
                        run.emit(EventType.STAGE_RECOVERED, stage.getStageId(), null, SYSTEM,
                                Map.of("reason", "interrupted execution rolled back", "restoredFiles", restored));
                    }
                    case FAILED, WAITING_APPROVAL, WAITING_CLARIFICATION -> stage.setStatus(StageStatus.PENDING);
                    default -> { }
                }
            }
            if (previous == RunStatus.SAFE_STOPPED) {
                state.getApprovals().values().removeIf(rec -> {
                    if (rec.getStatus() == ApprovalRecord.Status.REJECTED) {
                        run.emit(EventType.APPROVAL_VOIDED, rec.getStageId(), null, SYSTEM,
                                Map.of("reason", "run resumed after rejection; approval will be requested again"));
                        return true;
                    }
                    return false;
                });
                state.getReworkCounts().clear();
            }
            run.emit(EventType.RUN_RESUMED, null, null, SYSTEM,
                    Map.of("previousStatus", previous, "previousReason", String.valueOf(state.getStatusReason())));
            state.setStatus(RunStatus.RUNNING);
            state.setStatusReason(null);
            run.save();
        }
        return drive(run);
    }

    public RunState approve(String runId, String stageId, String by, String comment) {
        return decide(runId, stageId, by, comment, true, false);
    }

    public RunState reject(String runId, String stageId, String by, String comment) {
        return decide(runId, stageId, by, comment, false, false);
    }

    /** Decision made by a non-interactive delegate (demo driver / CI); recorded as DELEGATED, never as a person. */
    public RunState decideDelegated(String runId, String stageId, String by, String comment, boolean approved) {
        return decide(runId, stageId, by, comment, approved, true);
    }

    /** Records human answers for a clarification checkpoint; takes effect on the next resume. */
    public RunState clarify(String runId, String stageId, JsonNode answers, String by, boolean delegated) {
        RunState state = store.load(runId);
        StageDefinition def = graph.stage(stageId);
        if (def.type() != StageType.CLARIFICATION) {
            throw new IllegalArgumentException("Stage '" + stageId + "' is not a clarification checkpoint");
        }
        Run run = open(state, FaultInjector.NONE);
        state.getClarifications().put(stageId, answers);
        String actor = (delegated ? "delegated:" : "human:") + by;
        run.emit(EventType.CLARIFICATION_RECEIVED, stageId, null, actor, Map.of("answers", answers, "delegated", delegated));
        run.save();
        return state;
    }

    /**
     * Human amendment of an upstream artifact (e.g. a changed requirement). Invalidates every stage
     * that reads it plus everything downstream, rolls their sandbox changes back, voids affected
     * approvals, and keeps all unaffected completed work. Call {@link #resume} to re-execute.
     */
    public RunState amend(String runId, String artifactKey, JsonNode content, String by, String reason) {
        return amend(runId, artifactKey, content, by, reason, false);
    }

    /** @param delegated true when a script (demo/CI) submits the amendment on a person's behalf */
    public RunState amend(String runId, String artifactKey, JsonNode content, String by, String reason, boolean delegated) {
        RunState state = store.load(runId);
        if (state.getStatus() == RunStatus.RUNNING) {
            throw new IllegalStateException("Run " + runId + " is executing; stop or wait for a checkpoint before amending");
        }
        Run run = open(state, FaultInjector.NONE);
        String actor = (delegated ? "delegated:" : "human:") + by;
        synchronized (run.lock) {
            Optional<ArtifactVersion> previous = state.latest(artifactKey);
            ArtifactVersion amended = commitArtifact(run, artifactKey, content, actor, 0,
                    previous.map(ArtifactVersion::ref).map(List::of).orElse(List.of()));
            run.emit(EventType.ARTIFACT_AMENDED, null, null, actor, Map.of(
                    "artifact", amended.ref(),
                    "previous", previous.map(ArtifactVersion::ref).orElse("none"),
                    "reason", reason));
            List<String> readers = graph.readersOf(artifactKey);
            List<String> invalidated = graph.downstreamClosure(new HashSet<>(readers));
            List<String> preserved = graph.topologicalOrder().stream().filter(id -> !invalidated.contains(id)).toList();
            run.emit(EventType.REPLAN_TRIGGERED, null, null, SYSTEM, Map.of(
                    "changedArtifact", amended.ref(),
                    "directReaders", readers,
                    "invalidated", invalidated,
                    "preserved", preserved,
                    "reason", reason));
            invalidate(run, invalidated, "replan: " + artifactKey + " changed (" + reason + ")");
            state.setReplanCount(state.getReplanCount() + 1);
            state.setStatus(RunStatus.REPLANNED);
            state.setStatusReason("REPLANNED: resume to re-execute " + invalidated.size() + " invalidated stage(s)");
            run.save();
        }
        return state;
    }

    /** Kill switch: the engine stops scheduling new work and safe-stops at the next checkpoint. */
    public void requestStop(String runId, String by, String reason) {
        RunState state = store.load(runId);
        Run run = open(state, FaultInjector.NONE);
        try {
            Files.writeString(run.stopFile(), by + ": " + reason);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        run.emit(EventType.KILL_SWITCH_SET, null, null, "human:" + by, Map.of("reason", reason));
    }

    /** One AuditLog instance per run within this process (cross-process writers are handled by file locking). */
    public AuditLog auditLog(String runId) {
        return auditLogs.computeIfAbsent(runId,
                id -> new AuditLog(store.runDir(id).resolve("audit.jsonl"), id, clock));
    }

    private final Map<String, AuditLog> auditLogs = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Consumer<AuditEvent> eventListener;

    /** Optional live observer of engine events (e.g. console progress). Agent-emitted events are not forwarded. */
    public void setEventListener(Consumer<AuditEvent> listener) {
        this.eventListener = listener;
    }

    public Workspace workspace(String runId) {
        Path dir = store.runDir(runId);
        return new Workspace(dir.resolve("workspace"), dir.resolve("journal"));
    }

    // ------------------------------------------------------------------ drive loop

    private RunState drive(Run run) {
        RunState state = run.state;
        Instant deadline = clock.instant().plusMillis(graph.definition().budget().maxWallClockMs());
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "stage-worker");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Outcome> completions = new ExecutorCompletionService<>(pool);
        int inFlight = 0;
        List<Outcome> failures = new ArrayList<>();
        StopSignal stop = null;
        try {
            while (true) {
                if (stop == null) {
                    stop = checkLimits(run, deadline);
                }
                if (stop == null && failures.isEmpty()) {
                    boolean progressed;
                    do {
                        progressed = false;
                        for (String id : graph.topologicalOrder()) {
                            StageState st = state.stage(id);
                            if (st.getStatus() != StageStatus.PENDING || !dependenciesDone(state, id)) {
                                continue;
                            }
                            StageDefinition def = graph.stage(id);
                            synchronized (run.lock) {
                                if (!Conditions.evaluate(def.when(), state)) {
                                    st.setStatus(StageStatus.SKIPPED);
                                    run.emit(EventType.STAGE_SKIPPED, id, null, SYSTEM, Map.of("condition", def.when()));
                                    run.save();
                                    progressed = true;
                                    continue;
                                }
                                List<String> missing = def.requiredReads().stream()
                                        .filter(key -> state.latest(key).isEmpty()).toList();
                                if (!missing.isEmpty()) {
                                    st.setStatus(StageStatus.FAILED);
                                    run.emit(EventType.GATE_EVALUATED, id, null, SYSTEM, Map.of(
                                            "gate", "entry:required-inputs", "passed", false, "missing", missing));
                                    stop = new StopSignal(Trigger.ENTRY_GATE_FAILED, id, "missing required inputs " + missing);
                                    break;
                                }
                            }
                            if (def.type() == StageType.APPROVAL) {
                                Step step = handleApproval(run, def);
                                if (step.stop != null) {
                                    stop = step.stop;
                                    break;
                                }
                                progressed |= step.progressed;
                            } else if (def.type() == StageType.CLARIFICATION && !state.getClarifications().containsKey(id)) {
                                Step step = requestClarification(run, def);
                                if (step.stop != null) {
                                    stop = step.stop;
                                    break;
                                }
                            } else {
                                submit(run, def, completions);
                                inFlight++;
                            }
                        }
                    } while (progressed && stop == null);
                }

                if (inFlight == 0) {
                    if (!failures.isEmpty()) {
                        failures.sort(Comparator.comparingInt(o -> graph.topologicalOrder().indexOf(o.stageId)));
                        List<Outcome> toHandle = new ArrayList<>(failures);
                        failures.clear();
                        for (Outcome failure : toHandle) {
                            // An earlier rework in this batch may already have reset this stage.
                            if (state.stage(failure.stageId).getStatus() != StageStatus.FAILED) {
                                continue;
                            }
                            StopSignal signal = handleTerminalFailure(run, failure);
                            if (signal != null) {
                                stop = stop == null ? signal : stop;
                                break;
                            }
                        }
                        if (stop == null) {
                            continue;
                        }
                    }
                    if (stop != null) {
                        safeStop(run, stop);
                        break;
                    }
                    if (state.getStages().values().stream().allMatch(s -> s.getStatus().isDone())) {
                        complete(run);
                        break;
                    }
                    List<String> waiting = state.getStages().values().stream()
                            .filter(s -> s.getStatus() == StageStatus.WAITING_APPROVAL
                                    || s.getStatus() == StageStatus.WAITING_CLARIFICATION)
                            .map(StageState::getStageId).toList();
                    if (!waiting.isEmpty()) {
                        pause(run, waiting);
                        break;
                    }
                    stop = new StopSignal(Trigger.NO_PROGRESS, null, "no runnable, running or waiting stages");
                    continue;
                }

                Outcome outcome = completions.take().get();
                inFlight--;
                apply(run, outcome);
                if (!outcome.success) {
                    failures.add(outcome);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Orchestrator interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Stage worker crashed", ex.getCause());
        } finally {
            pool.shutdownNow();
        }
        return state;
    }

    private StopSignal checkLimits(Run run, Instant deadline) {
        if (Files.exists(run.stopFile())) {
            return new StopSignal(Trigger.KILL_SWITCH, null, "kill switch engaged: " + readQuietly(run.stopFile()));
        }
        int maxExecutions = graph.definition().budget().maxStageExecutions();
        if (run.state.getStageExecutions() >= maxExecutions) {
            return new StopSignal(Trigger.BUDGET_EXCEEDED, null, "stage execution budget of " + maxExecutions + " reached");
        }
        if (clock.instant().isAfter(deadline)) {
            return new StopSignal(Trigger.BUDGET_EXCEEDED, null, "wall-clock budget exceeded");
        }
        return null;
    }

    private boolean dependenciesDone(RunState state, String stageId) {
        return graph.stage(stageId).dependsOn().stream().allMatch(dep -> state.stage(dep).getStatus().isDone());
    }

    // ------------------------------------------------------------------ agent stages

    private void submit(Run run, StageDefinition def, CompletionService<Outcome> completions) {
        Map<String, ArtifactVersion> inputs = new LinkedHashMap<>();
        JsonNode answers;
        int execution;
        synchronized (run.lock) {
            for (String key : def.readKeys()) {
                run.state.latest(key).ifPresent(v -> inputs.put(key, v));
            }
            answers = run.state.getClarifications().get(def.id());
            StageState st = run.state.stage(def.id());
            st.setStatus(StageStatus.RUNNING);
            st.setStartedAt(clock.instant());
            st.setExecutions(st.getExecutions() + 1);
            execution = st.getExecutions();
            run.emit(EventType.STAGE_STARTED, def.id(), null, SYSTEM, Map.of(
                    "execution", execution,
                    "agent", def.agent(),
                    "inputs", inputs.values().stream().map(ArtifactVersion::ref).toList()));
            run.save();
        }
        completions.submit(() -> execute(run, def, execution, inputs, answers));
    }

    private Outcome execute(Run run, StageDefinition def, int execution, Map<String, ArtifactVersion> inputs,
                            JsonNode answers) throws InterruptedException {
        Instant started = clock.instant();
        Agent primary = agents.find(def.agent()).orElseThrow();
        Attempt last = null;
        int attempt = 0;
        for (attempt = 1; attempt <= def.retry().maxAttempts(); attempt++) {
            last = attempt(run, def, primary, execution, attempt, false, inputs, answers);
            if (last.result.success()) {
                return Outcome.of(def.id(), execution, attempt, last, false, started, clock.instant());
            }
            if (last.result.failureKind() != FailureKind.RETRYABLE || attempt == def.retry().maxAttempts()) {
                break;
            }
            if (run.state.getStageExecutions() >= graph.definition().budget().maxStageExecutions()) {
                run.emit(EventType.RETRY_SCHEDULED, def.id(), attempt, SYSTEM, Map.of(
                        "nextAttempt", "none", "reason", "run execution budget exhausted; retries stopped"));
                break;
            }
            long delay = def.retry().delayBeforeAttempt(attempt + 1);
            run.emit(EventType.RETRY_SCHEDULED, def.id(), attempt, SYSTEM, Map.of(
                    "nextAttempt", attempt + 1, "maxAttempts", def.retry().maxAttempts(),
                    "delayMs", delay, "reason", last.result.summary()));
            sleeper.sleep(delay);
        }
        if (def.fallbackAgent() != null && last.result.failureKind() != FailureKind.POLICY_BLOCK) {
            Agent fallback = agents.find(def.fallbackAgent()).orElseThrow();
            int fallbackAttempt = Math.min(attempt, def.retry().maxAttempts()) + 1;
            run.emit(EventType.FALLBACK_ACTIVATED, def.id(), fallbackAttempt, SYSTEM, Map.of(
                    "from", primary.name(), "to", fallback.name(), "reason", last.result.summary()));
            last = attempt(run, def, fallback, execution, fallbackAttempt, true, inputs, answers);
            return Outcome.of(def.id(), execution, fallbackAttempt, last, true, started, clock.instant());
        }
        return Outcome.of(def.id(), execution, Math.min(attempt, def.retry().maxAttempts()), last, false, started, clock.instant());
    }

    private Attempt attempt(Run run, StageDefinition def, Agent agent, int execution, int attempt, boolean fallback,
                            Map<String, ArtifactVersion> inputs, JsonNode answers) {
        synchronized (run.lock) {
            run.state.setStageExecutions(run.state.getStageExecutions() + 1);
            run.state.stage(def.id()).setAttempts(attempt);
        }
        String actor = "agent:" + agent.name();
        StageContext ctx = new StageContext(run.state.getRunId(), def, execution, attempt, fallback, inputs,
                run.state.getParameters(), answers, run.workspace, run.dir, run.audit, actor);
        run.emit(EventType.AGENT_INVOKED, def.id(), attempt, actor, Map.of(
                "agent", agent.name(), "mode", agent.mode(), "execution", execution, "fallback", fallback));

        StageResult result;
        Optional<Fault> fault = run.faults.match(def.id(), execution, attempt);
        if (fault.isPresent()) {
            run.emit(EventType.FAULT_INJECTED, def.id(), attempt, SYSTEM, Map.of(
                    "injected", true, "kind", fault.get().kind(), "message", fault.get().message()));
            result = StageResult.fail(fault.get().kind(), "[injected fault] " + fault.get().message());
        } else {
            result = invokeSafely(agent, ctx);
        }

        List<GateResult> gateResults = new ArrayList<>();
        if (result.success()) {
            List<String> missing = def.writes().stream().filter(w -> !ctx.outputs().containsKey(w)).toList();
            GateResult outputs = missing.isEmpty()
                    ? GateResult.pass("outputs-present", "all declared outputs produced")
                    : GateResult.fail("outputs-present", "missing outputs " + missing, FailureKind.PERMANENT);
            gateResults.add(outputs);
            for (String gateName : def.exitGates()) {
                if (!gateResults.get(gateResults.size() - 1).passed()) {
                    break;
                }
                Gate gate = gates.find(gateName).orElseThrow(() ->
                        new IllegalStateException("Unknown gate '" + gateName + "' on stage " + def.id()));
                gateResults.add(gate.evaluate(ctx, ctx.outputs()));
            }
            for (GateResult g : gateResults) {
                run.emit(EventType.GATE_EVALUATED, def.id(), attempt, SYSTEM, Map.of(
                        "gate", g.gate(), "passed", g.passed(), "detail", String.valueOf(g.detail())));
            }
            Optional<GateResult> failed = gateResults.stream().filter(g -> !g.passed()).findFirst();
            if (failed.isPresent()) {
                result = StageResult.fail(failed.get().failureKind(),
                        "exit gate '" + failed.get().gate() + "' failed: " + failed.get().detail(), ctx.outputs());
            }
        }
        if (!result.success()) {
            List<String> restored = run.workspace.rollback(def.id());
            if (!restored.isEmpty()) {
                run.emit(EventType.ROLLBACK_COMPLETED, def.id(), attempt, SYSTEM,
                        Map.of("scope", "failed-attempt", "restoredFiles", restored));
            }
            run.emit(EventType.STAGE_ATTEMPT_FAILED, def.id(), attempt, actor, Map.of(
                    "kind", result.failureKind(), "message", result.summary(), "fallback", fallback));
        }
        return new Attempt(agent, ctx, result, gateResults);
    }

    private static StageResult invokeSafely(Agent agent, StageContext ctx) {
        try {
            StageResult result = agent.execute(ctx);
            return result == null ? StageResult.fail(FailureKind.PERMANENT, "agent returned no result") : result;
        } catch (Workspace.SandboxViolationException | Workspace.WriteConflictException ex) {
            return StageResult.fail(FailureKind.POLICY_BLOCK, "guardrail: " + ex.getMessage());
        } catch (IOException | UncheckedIOException ex) {
            return StageResult.fail(FailureKind.RETRYABLE, "I/O error: " + ex.getMessage());
        } catch (Exception ex) {
            return StageResult.fail(FailureKind.PERMANENT, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void apply(Run run, Outcome outcome) {
        synchronized (run.lock) {
            StageState st = run.state.stage(outcome.stageId);
            st.setFinishedAt(outcome.finished);
            st.setAgentUsed(outcome.agentName);
            st.setExecutionMode(outcome.mode.name());
            st.setUsedFallback(outcome.usedFallback);
            st.setSummary(outcome.summary);
            if (outcome.success) {
                List<String> written = new ArrayList<>();
                outcome.outputs.forEach((key, content) -> written.add(
                        commitArtifact(run, key, content, outcome.stageId, outcome.attempts, outcome.inputRefs).ref()));
                for (StageContext.PendingDecision d : outcome.decisions) {
                    Decision decision = new Decision(UUID.randomUUID().toString(), outcome.stageId, outcome.execution,
                            d.summary(), d.rationale(), d.alternatives(), outcome.inputRefs, clock.instant());
                    run.state.getDecisions().add(decision);
                    run.emit(EventType.DECISION_RECORDED, outcome.stageId, outcome.attempts, "agent:" + outcome.agentName,
                            Map.of("decisionId", decision.id(), "summary", d.summary(), "rationale", d.rationale(),
                                    "alternatives", d.alternatives(), "inputs", outcome.inputRefs));
                }
                st.setStatus(StageStatus.SUCCEEDED);
                st.setLastError(null);
                run.emit(EventType.STAGE_SUCCEEDED, outcome.stageId, outcome.attempts, SYSTEM, Map.of(
                        "execution", outcome.execution, "attempts", outcome.attempts,
                        "durationMs", Duration.between(outcome.started, outcome.finished).toMillis(),
                        "agent", outcome.agentName, "mode", outcome.mode, "usedFallback", outcome.usedFallback,
                        "outputs", written, "summary", String.valueOf(outcome.summary)));
            } else {
                st.setStatus(StageStatus.FAILED);
                st.setLastError(outcome.summary);
                st.setLastFailureKind(String.valueOf(outcome.failureKind));
                run.emit(EventType.STAGE_FAILED, outcome.stageId, outcome.attempts, SYSTEM, Map.of(
                        "execution", outcome.execution, "attempts", outcome.attempts,
                        "durationMs", Duration.between(outcome.started, outcome.finished).toMillis(),
                        "kind", String.valueOf(outcome.failureKind), "message", String.valueOf(outcome.summary),
                        "usedFallback", outcome.usedFallback));
            }
            run.save();
        }
    }

    private StopSignal handleTerminalFailure(Run run, Outcome failure) {
        StageDefinition def = graph.stage(failure.stageId);
        ReworkPolicy rework = def.onFailure();
        synchronized (run.lock) {
            if (rework == null) {
                return new StopSignal(Trigger.STAGE_FAILED, failure.stageId,
                        failure.failureKind + ": " + failure.summary);
            }
            int done = run.state.getReworkCounts().getOrDefault(failure.stageId, 0);
            if (done >= rework.maxReworks()) {
                return new StopSignal(Trigger.REWORK_EXHAUSTED, failure.stageId,
                        "rework limit " + rework.maxReworks() + " reached; last failure: " + failure.summary);
            }
            run.state.getReworkCounts().put(failure.stageId, done + 1);
            List<String> invalidated = graph.downstreamClosure(Set.of(rework.stage()));
            run.emit(EventType.REWORK_TRIGGERED, failure.stageId, null, SYSTEM, Map.of(
                    "failedStage", failure.stageId, "reworkStage", rework.stage(),
                    "rework", done + 1, "maxReworks", rework.maxReworks(),
                    "failureKind", String.valueOf(failure.failureKind), "reason", String.valueOf(failure.summary),
                    "invalidated", invalidated));
            invalidate(run, invalidated, "rework requested by " + failure.stageId);
            if (rework.feedbackArtifact() != null) {
                Map<String, Object> feedback = new LinkedHashMap<>();
                feedback.put("failedStage", failure.stageId);
                feedback.put("failureKind", String.valueOf(failure.failureKind));
                feedback.put("message", failure.summary);
                feedback.put("rework", done + 1);
                feedback.put("details", failure.details);
                commitArtifact(run, rework.feedbackArtifact(), Json.tree(feedback), failure.stageId, failure.attempts,
                        failure.inputRefs);
            }
            run.save();
            return null;
        }
    }

    // ------------------------------------------------------------------ human checkpoints

    private record Step(boolean progressed, StopSignal stop) {
    }

    private Step handleApproval(Run run, StageDefinition def) {
        synchronized (run.lock) {
            RunState state = run.state;
            StageState st = state.stage(def.id());
            ApprovalRecord rec = state.getApprovals().get(def.id());
            if (rec == null) {
                rec = new ApprovalRecord();
                rec.setStageId(def.id());
                rec.setRequestedAt(clock.instant());
                List<String> refs = rec.getInputRefs();
                for (String key : def.readKeys()) {
                    state.latest(key).ifPresent(v -> refs.add(v.ref()));
                }
                state.getApprovals().put(def.id(), rec);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("description", String.valueOf(def.description()));
                data.put("inputs", rec.getInputRefs());
                data.put("summaries", summariesOf(state, def));
                data.put("howToDecide", "orch approve|reject " + state.getRunId() + " " + def.id() + " --by <name>");
                run.emit(EventType.APPROVAL_REQUESTED, def.id(), null, SYSTEM, data);
            }
            String delegate = state.getParameters().get(PARAM_AUTO_APPROVE);
            if (rec.getStatus() == ApprovalRecord.Status.PENDING && delegate != null && !delegate.isBlank()) {
                rec.setStatus(ApprovalRecord.Status.APPROVED);
                rec.setMode(ApprovalRecord.Mode.DELEGATED);
                rec.setDecidedBy(delegate);
                rec.setDecidedAt(clock.instant());
                rec.setComment("auto-approved under delegation (--auto-approve-as); not an interactive review");
                run.emit(EventType.APPROVAL_GRANTED, def.id(), null, "delegated:" + delegate,
                        Map.of("mode", "DELEGATED", "inputs", rec.getInputRefs()));
            }
            switch (rec.getStatus()) {
                case APPROVED -> {
                    st.setStatus(StageStatus.SUCCEEDED);
                    st.setFinishedAt(clock.instant());
                    st.setSummary("approved by " + rec.getDecidedBy() + " (" + rec.getMode() + ")");
                    run.emit(EventType.STAGE_SUCCEEDED, def.id(), null, SYSTEM, Map.of(
                            "approvedBy", rec.getDecidedBy(), "mode", String.valueOf(rec.getMode()),
                            "waitMs", Duration.between(rec.getRequestedAt(), rec.getDecidedAt()).toMillis()));
                    run.save();
                    return new Step(true, null);
                }
                case REJECTED -> {
                    st.setStatus(StageStatus.FAILED);
                    st.setLastError("rejected by " + rec.getDecidedBy() + ": " + rec.getComment());
                    run.emit(EventType.STAGE_FAILED, def.id(), null, SYSTEM, Map.of(
                            "kind", "APPROVAL_REJECTED", "message", st.getLastError()));
                    run.save();
                    return new Step(false, new StopSignal(Trigger.APPROVAL_REJECTED, def.id(), st.getLastError()));
                }
                default -> {
                    if (def.timeoutMs() != null && clock.instant().isAfter(rec.getRequestedAt().plusMillis(def.timeoutMs()))) {
                        st.setStatus(StageStatus.FAILED);
                        run.emit(EventType.APPROVAL_TIMED_OUT, def.id(), null, SYSTEM, Map.of("timeoutMs", def.timeoutMs()));
                        run.save();
                        return new Step(false, new StopSignal(Trigger.APPROVAL_TIMEOUT, def.id(),
                                "no decision within " + def.timeoutMs() + " ms"));
                    }
                    st.setStatus(StageStatus.WAITING_APPROVAL);
                    run.save();
                    return new Step(false, null);
                }
            }
        }
    }

    private Step requestClarification(Run run, StageDefinition def) {
        synchronized (run.lock) {
            RunState state = run.state;
            StageState st = state.stage(def.id());
            Instant requestedAt = state.getClarificationRequests().get(def.id());
            if (requestedAt == null) {
                requestedAt = clock.instant();
                state.getClarificationRequests().put(def.id(), requestedAt);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("description", String.valueOf(def.description()));
                for (String key : def.readKeys()) {
                    state.latest(key).ifPresent(v -> {
                        data.put("source", v.ref());
                        data.put("questions", v.getContent().path("questions"));
                    });
                }
                data.put("howToAnswer", "orch clarify " + state.getRunId() + " " + def.id() + " <answers.json> --by <name>");
                run.emit(EventType.CLARIFICATION_REQUESTED, def.id(), null, SYSTEM, data);
            }
            if (def.timeoutMs() != null && clock.instant().isAfter(requestedAt.plusMillis(def.timeoutMs()))) {
                st.setStatus(StageStatus.FAILED);
                run.emit(EventType.CLARIFICATION_TIMED_OUT, def.id(), null, SYSTEM, Map.of("timeoutMs", def.timeoutMs()));
                run.save();
                return new Step(false, new StopSignal(Trigger.CLARIFICATION_TIMEOUT, def.id(),
                        "no answers within " + def.timeoutMs() + " ms; refusing to guess on ambiguous requirements"));
            }
            st.setStatus(StageStatus.WAITING_CLARIFICATION);
            run.save();
            return new Step(false, null);
        }
    }

    private RunState decide(String runId, String stageId, String by, String comment, boolean approved, boolean delegated) {
        RunState state = store.load(runId);
        if (graph.stage(stageId).type() != StageType.APPROVAL) {
            throw new IllegalArgumentException("Stage '" + stageId + "' is not an approval checkpoint");
        }
        ApprovalRecord rec = state.getApprovals().get(stageId);
        if (rec == null || rec.getStatus() != ApprovalRecord.Status.PENDING) {
            throw new IllegalStateException("No pending approval for stage '" + stageId + "' in run " + runId);
        }
        Run run = open(state, FaultInjector.NONE);
        rec.setStatus(approved ? ApprovalRecord.Status.APPROVED : ApprovalRecord.Status.REJECTED);
        ApprovalRecord.Mode mode = delegated ? ApprovalRecord.Mode.DELEGATED : ApprovalRecord.Mode.INTERACTIVE;
        rec.setMode(mode);
        rec.setDecidedBy(by);
        rec.setDecidedAt(clock.instant());
        rec.setComment(comment);
        run.emit(approved ? EventType.APPROVAL_GRANTED : EventType.APPROVAL_REJECTED, stageId, null,
                (delegated ? "delegated:" : "human:") + by,
                Map.of("mode", mode.name(), "comment", String.valueOf(comment), "inputs", rec.getInputRefs()));
        run.save();
        return state;
    }

    private static Map<String, Object> summariesOf(RunState state, StageDefinition def) {
        Map<String, Object> summaries = new LinkedHashMap<>();
        for (String key : def.readKeys()) {
            state.latest(key).ifPresent(v -> summaries.put(v.ref(), v.getContent().path("summary").asText("")));
        }
        return summaries;
    }

    // ------------------------------------------------------------------ state transitions

    private void invalidate(Run run, List<String> stageIds, String reason) {
        List<String> reversed = new ArrayList<>(stageIds);
        java.util.Collections.reverse(reversed);
        for (String id : reversed) {
            List<String> restored = run.workspace.rollback(id);
            if (!restored.isEmpty()) {
                run.emit(EventType.ROLLBACK_COMPLETED, id, null, SYSTEM,
                        Map.of("scope", "invalidation", "restoredFiles", restored, "reason", reason));
            }
            run.state.getArtifacts().values().forEach(versions -> versions.stream()
                    .filter(v -> id.equals(v.getProducedBy()) && !v.isStale())
                    .forEach(v -> v.setStale(true)));
            ApprovalRecord voided = run.state.getApprovals().remove(id);
            if (voided != null) {
                run.emit(EventType.APPROVAL_VOIDED, id, null, SYSTEM, Map.of(
                        "reason", reason, "previousDecision", String.valueOf(voided.getStatus()),
                        "previousInputs", voided.getInputRefs()));
            }
            StageState st = run.state.stage(id);
            String previous = st.getStatus().name();
            st.reset();
            run.emit(EventType.STAGE_INVALIDATED, id, null, SYSTEM, Map.of("reason", reason, "previousStatus", previous));
        }
    }

    private ArtifactVersion commitArtifact(Run run, String key, JsonNode content, String producedBy, int attempt,
                                           List<String> derivedFrom) {
        ArtifactVersion version = new ArtifactVersion();
        version.setKey(key);
        version.setVersion(run.state.nextVersion(key));
        version.setContent(content);
        version.setContentHash(Json.sha256(Json.canonical(content)));
        version.setProducedBy(producedBy);
        version.setAttempt(attempt);
        version.setDerivedFrom(new ArrayList<>(derivedFrom));
        version.setCreatedAt(clock.instant());
        run.state.getArtifacts().computeIfAbsent(key, k -> new ArrayList<>()).add(version);
        run.emit(EventType.ARTIFACT_WRITTEN, producedBy.contains(":") ? null : producedBy, null,
                producedBy.contains(":") ? producedBy : SYSTEM, Map.of(
                        "artifact", version.ref(), "contentHash", version.getContentHash(), "derivedFrom", derivedFrom));
        return version;
    }

    private void safeStop(Run run, StopSignal stop) {
        synchronized (run.lock) {
            run.state.setStatus(RunStatus.SAFE_STOPPED);
            run.state.setStatusReason(stop.trigger() + ": " + stop.message());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("trigger", stop.trigger());
            data.put("stage", String.valueOf(stop.stageId()));
            data.put("message", stop.message());
            data.put("resumable", true);
            data.put("workspacePreserved", run.workspace.root().toString());
            data.put("howToResume", "fix the cause, then: orch resume " + run.state.getRunId());
            run.emit(EventType.SAFE_STOP, stop.stageId(), null, SYSTEM, data);
            run.save();
        }
    }

    private void pause(Run run, List<String> waiting) {
        synchronized (run.lock) {
            boolean clarification = waiting.stream()
                    .anyMatch(id -> run.state.stage(id).getStatus() == StageStatus.WAITING_CLARIFICATION);
            run.state.setStatus(clarification ? RunStatus.WAITING_CLARIFICATION : RunStatus.WAITING_APPROVAL);
            run.state.setStatusReason("waiting on human checkpoint(s) " + waiting);
            run.emit(EventType.RUN_PAUSED, null, null, SYSTEM, Map.of("waitingOn", waiting));
            run.save();
        }
    }

    private void complete(Run run) {
        synchronized (run.lock) {
            run.state.setStatus(RunStatus.COMPLETED);
            run.state.setStatusReason(null);
            run.emit(EventType.RUN_COMPLETED, null, null, SYSTEM, Map.of(
                    "elapsedMs", Duration.between(run.state.getCreatedAt(), clock.instant()).toMillis(),
                    "stageExecutions", run.state.getStageExecutions(),
                    "replans", run.state.getReplanCount(),
                    "decisions", run.state.getDecisions().size()));
            run.save();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void validateAgents() {
        for (String id : graph.topologicalOrder()) {
            StageDefinition def = graph.stage(id);
            if (def.agent() != null && agents.find(def.agent()).isEmpty()) {
                throw new IllegalStateException("Stage '" + id + "' uses unregistered agent '" + def.agent() + "'");
            }
            if (def.fallbackAgent() != null && agents.find(def.fallbackAgent()).isEmpty()) {
                throw new IllegalStateException("Stage '" + id + "' uses unregistered fallback agent '" + def.fallbackAgent() + "'");
            }
        }
    }

    private Run open(RunState state, FaultInjector faults) {
        Path dir = store.runDir(state.getRunId());
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new Run(state, auditLog(state.getRunId()), workspace(state.getRunId()), dir, faults);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file).trim();
        } catch (IOException ex) {
            return "(unreadable)";
        }
    }

    private final class Run {
        final RunState state;
        final AuditLog audit;
        final Workspace workspace;
        final Path dir;
        final FaultInjector faults;
        final Object lock = new Object();

        Run(RunState state, AuditLog audit, Workspace workspace, Path dir, FaultInjector faults) {
            this.state = state;
            this.audit = audit;
            this.workspace = workspace;
            this.dir = dir;
            this.faults = faults == null ? FaultInjector.NONE : faults;
        }

        Path stopFile() {
            return dir.resolve("STOP");
        }

        void emit(String type, String stageId, Integer attempt, String actor, Object data) {
            AuditEvent event = audit.append(type, stageId, attempt, actor, data);
            Consumer<AuditEvent> listener = eventListener;
            if (listener != null) {
                listener.accept(event);
            }
        }

        void save() {
            synchronized (lock) {
                state.setUpdatedAt(clock.instant());
                store.save(state);
            }
        }
    }

    private record Attempt(Agent agent, StageContext context, StageResult result, List<GateResult> gates) {
    }

    private static final class Outcome {
        String stageId;
        int execution;
        int attempts;
        boolean success;
        boolean usedFallback;
        String summary;
        FailureKind failureKind;
        Object details;
        String agentName;
        ExecutionMode mode;
        Map<String, JsonNode> outputs;
        List<StageContext.PendingDecision> decisions;
        List<String> inputRefs;
        Instant started;
        Instant finished;

        static Outcome of(String stageId, int execution, int attempts, Attempt attempt, boolean usedFallback,
                          Instant started, Instant finished) {
            Outcome o = new Outcome();
            o.stageId = stageId;
            o.execution = execution;
            o.attempts = attempts;
            o.success = attempt.result.success();
            o.usedFallback = usedFallback;
            o.summary = attempt.result.summary();
            o.failureKind = attempt.result.failureKind();
            o.details = attempt.result.details();
            o.agentName = attempt.agent.name();
            o.mode = attempt.agent.mode();
            o.outputs = attempt.context.outputs();
            o.decisions = attempt.context.decisions();
            o.inputRefs = attempt.context.inputRefs();
            o.started = started;
            o.finished = finished;
            return o;
        }
    }
}

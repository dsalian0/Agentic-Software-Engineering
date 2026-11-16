package com.salian.orchestrator.engine;

import static com.salian.orchestrator.engine.EngineFixture.agent;
import static com.salian.orchestrator.engine.EngineFixture.approval;
import static com.salian.orchestrator.engine.EngineFixture.clarification;
import static com.salian.orchestrator.engine.EngineFixture.count;
import static com.salian.orchestrator.engine.EngineFixture.graph;
import static com.salian.orchestrator.engine.EngineFixture.list;
import static com.salian.orchestrator.engine.EngineFixture.orchestrator;
import static com.salian.orchestrator.engine.EngineFixture.producing;
import static com.salian.orchestrator.engine.EngineFixture.withFallback;
import static com.salian.orchestrator.engine.EngineFixture.withRetry;
import static com.salian.orchestrator.engine.EngineFixture.withRework;
import static com.salian.orchestrator.engine.EngineFixture.withTimeout;
import static com.salian.orchestrator.engine.EngineFixture.withWhen;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.EventType;
import com.salian.orchestrator.engine.EngineFixture.FakeAgent;
import com.salian.orchestrator.engine.EngineFixture.MutableClock;
import com.salian.orchestrator.engine.EngineFixture.RecordingSleeper;
import com.salian.orchestrator.model.ApprovalRecord;
import com.salian.orchestrator.model.Budget;
import com.salian.orchestrator.model.RetryPolicy;
import com.salian.orchestrator.model.ReworkPolicy;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.RunStatus;
import com.salian.orchestrator.model.StageStatus;
import com.salian.orchestrator.support.Json;
import com.salian.orchestrator.workspace.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestratorTest {

    @TempDir
    Path runs;

    private final MutableClock clock = new MutableClock();
    private final RecordingSleeper sleeper = new RecordingSleeper();

    private List<AuditEvent> events(Orchestrator o, String runId) {
        return o.auditLog(runId).events();
    }

    @Test
    void runsIndependentStagesInParallelAndJoinsBeforeDependents() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        FakeAgent parallel = new FakeAgent("parallel", (ctx, n) -> {
            bothRunning.countDown();
            // Only succeeds if the sibling stage is running at the same time.
            if (!bothRunning.await(5, TimeUnit.SECONDS)) {
                return StageResult.fail(FailureKind.PERMANENT, "sibling never started: not parallel");
            }
            ctx.output(ctx.stage().writes().get(0), Map.of("summary", ctx.stageId()));
            return StageResult.ok("done");
        });
        Orchestrator o = orchestrator(runs, graph(
                agent("start", "producer", list(), list(), list("seed")),
                agent("left", "parallel", list("start"), list("seed"), list("l")),
                agent("right", "parallel", list("start"), list("seed"), list("r")),
                agent("join", "producer", list("left", "right"), list("l", "r"), list("out"))),
                clock, sleeper, producing("producer"), parallel);

        RunState state = o.start("par", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        List<AuditEvent> events = events(o, "par");
        long joinStarted = seqOf(events, EventType.STAGE_STARTED, "join");
        assertThat(joinStarted).isGreaterThan(seqOf(events, EventType.STAGE_SUCCEEDED, "left"))
                .isGreaterThan(seqOf(events, EventType.STAGE_SUCCEEDED, "right"));
        assertThat(state.latest("out").orElseThrow().getDerivedFrom()).containsExactlyInAnyOrder("l@1", "r@1");
    }

    @Test
    void retriesRetryableFailuresWithExponentialBackoffThenSucceeds() {
        FakeAgent flaky = new FakeAgent("flaky", (ctx, n) -> n < 3
                ? StageResult.fail(FailureKind.RETRYABLE, "transient " + n)
                : okWithOutputs(ctx));
        Orchestrator o = orchestrator(runs, graph(
                withRetry(agent("build", "flaky", list(), list(), list("report")), new RetryPolicy(3, 100, 2.0))),
                clock, sleeper, flaky);

        RunState state = o.start("retry", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(sleeper.delays).containsExactly(100L, 200L);
        List<AuditEvent> events = events(o, "retry");
        assertThat(count(events, EventType.RETRY_SCHEDULED)).isEqualTo(2);
        assertThat(count(events, EventType.STAGE_ATTEMPT_FAILED)).isEqualTo(2);
        assertThat(state.stage("build").getAttempts()).isEqualTo(3);
    }

    @Test
    void doesNotRetryPermanentFailuresButActivatesFallback() {
        FakeAgent primary = new FakeAgent("primary", (ctx, n) -> StageResult.fail(FailureKind.PERMANENT, "model unavailable"));
        FakeAgent fallback = producing("template");
        Orchestrator o = orchestrator(runs, graph(
                withFallback(withRetry(agent("docs", "primary", list(), list(), list("docs")), new RetryPolicy(3, 10, 2)),
                        "template")),
                clock, sleeper, primary, fallback);

        RunState state = o.start("fb", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(primary.invocations()).isEqualTo(1);
        assertThat(state.stage("docs").isUsedFallback()).isTrue();
        assertThat(state.stage("docs").getAgentUsed()).isEqualTo("template");
        assertThat(count(events(o, "fb"), EventType.FALLBACK_ACTIVATED)).isEqualTo(1);
    }

    @Test
    void rollsBackSandboxChangesOfFailedAttempt() throws Exception {
        Workspace seed = new Workspace(runs.resolve("rb/workspace"), runs.resolve("rb/journal"));
        Files.writeString(seed.root().resolve("existing.txt"), "original");
        FakeAgent writer = new FakeAgent("writer", (ctx, n) -> {
            ctx.workspace().write(ctx.stageId(), "existing.txt", "corrupted");
            ctx.workspace().write(ctx.stageId(), "new.txt", "partial");
            return n == 1 ? StageResult.fail(FailureKind.RETRYABLE, "crashed midway") : okWithOutputs(ctx);
        });
        Orchestrator o = orchestrator(runs, graph(withRetry(agent("impl", "writer", list(), list(), list("code")),
                new RetryPolicy(2, 0, 1))), clock, sleeper, writer);

        RunState state = o.start("rb", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        AuditEvent rollback = events(o, "rb").stream().filter(e -> e.type().equals(EventType.ROLLBACK_COMPLETED))
                .findFirst().orElseThrow();
        assertThat(rollback.data().path("scope").asText()).isEqualTo("failed-attempt");
        assertThat(rollback.data().path("restoredFiles").toString()).contains("existing.txt", "new.txt");
    }

    @Test
    void terminalFailureSafeStopsWithRollbackAndResumeRecovers() throws Exception {
        FakeAgent broken = new FakeAgent("broken", (ctx, n) -> {
            ctx.workspace().write(ctx.stageId(), "half-done.txt", "x");
            return StageResult.fail(FailureKind.PERMANENT, "compile error");
        });
        Orchestrator o = orchestrator(runs, graph(
                agent("a", "producer", list(), list(), list("a")),
                agent("b", "broken", list("a"), list("a"), list("b"))), clock, sleeper, producing("producer"), broken);

        RunState stopped = o.start("stop", "test", Map.of(), FaultInjector.NONE);

        assertThat(stopped.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stopped.getStatusReason()).startsWith("STAGE_FAILED");
        assertThat(stopped.stage("a").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(Files.exists(o.workspace("stop").root().resolve("half-done.txt"))).isFalse();

        broken.setBehavior((ctx, n) -> okWithOutputs(ctx));
        RunState resumed = o.resume("stop", FaultInjector.NONE);

        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.stage("a").getExecutions()).isEqualTo(1); // completed work is not redone
        assertThat(types(events(o, "stop"))).contains(EventType.SAFE_STOP, EventType.RUN_RESUMED, EventType.RUN_COMPLETED);
    }

    @Test
    void policyBlockReworksUpstreamStageWithFeedback() {
        FakeAgent implementer = new FakeAgent("implementer", (ctx, n) -> {
            boolean hasFeedback = ctx.optionalInput("feedback").isPresent();
            ctx.workspace().write(ctx.stageId(), "Schema.sql", hasFeedback ? "visitor_hash" : "client_ip");
            ctx.output("code", Map.of("summary", hasFeedback ? "remediated" : "first draft"));
            return StageResult.ok("implemented");
        });
        FakeAgent scanner = new FakeAgent("scanner", (ctx, n) -> {
            String schema = ctx.workspace().read("Schema.sql").orElse("");
            if (schema.contains("client_ip")) {
                return StageResult.fail(FailureKind.POLICY_BLOCK, "raw IP persisted",
                        Map.of("rule", "pii-raw-ip", "file", "Schema.sql"));
            }
            ctx.output("scan", Map.of("summary", "clean"));
            return StageResult.ok("clean");
        });
        Orchestrator o = orchestrator(runs, graph(
                agent("implement", "implementer", list(), list("feedback?"), list("code")),
                withRework(agent("policy", "scanner", list("implement"), list("code"), list("scan")),
                        new ReworkPolicy("implement", 1, "feedback"))), clock, sleeper, implementer, scanner);

        RunState state = o.start("rework", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(implementer.executions).containsExactly(1, 2);
        assertThat(state.latest("feedback").orElseThrow().getContent().path("details").path("rule").asText())
                .isEqualTo("pii-raw-ip");
        assertThat(state.getArtifacts().get("code")).hasSize(2);
        assertThat(state.getArtifacts().get("code").get(0).isStale()).isTrue();
        assertThat(o.workspace("rework").read("Schema.sql")).contains("visitor_hash");
        List<AuditEvent> events = events(o, "rework");
        assertThat(count(events, EventType.REWORK_TRIGGERED)).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.type().equals(EventType.ROLLBACK_COMPLETED))
                .anyMatch(e -> e.data().path("scope").asText().equals("invalidation"))).isTrue();
    }

    @Test
    void reworkIsBoundedThenSafeStops() {
        FakeAgent implementer = producing("implementer");
        FakeAgent scanner = new FakeAgent("scanner", (ctx, n) -> StageResult.fail(FailureKind.POLICY_BLOCK, "still bad"));
        Orchestrator o = orchestrator(runs, graph(
                agent("implement", "implementer", list(), list("feedback?"), list("code")),
                withRework(agent("policy", "scanner", list("implement"), list("code"), list("scan")),
                        new ReworkPolicy("implement", 2, "feedback"))), clock, sleeper, implementer, scanner);

        RunState state = o.start("exhaust", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(state.getStatusReason()).startsWith("REWORK_EXHAUSTED");
        assertThat(implementer.invocations()).isEqualTo(3);
    }

    @Test
    void pausesForInteractiveApprovalAndResumesAfterDecision() {
        Orchestrator o = orchestrator(runs, graph(
                agent("plan", "producer", list(), list(), list("plan")),
                approval("approve-plan", list("plan"), list("plan")),
                agent("build", "producer", list("approve-plan"), list("plan"), list("build"))),
                clock, sleeper, producing("producer"));

        RunState paused = o.start("appr", "test", Map.of(), FaultInjector.NONE);
        assertThat(paused.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(paused.stage("build").getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(paused.getApprovals().get("approve-plan").getInputRefs()).containsExactly("plan@1");

        o.approve("appr", "approve-plan", "dharmender", "plan looks right");
        RunState done = o.resume("appr", FaultInjector.NONE);

        assertThat(done.getStatus()).isEqualTo(RunStatus.COMPLETED);
        ApprovalRecord rec = done.getApprovals().get("approve-plan");
        assertThat(rec.getMode()).isEqualTo(ApprovalRecord.Mode.INTERACTIVE);
        assertThat(events(o, "appr")).anyMatch(e -> e.type().equals(EventType.APPROVAL_GRANTED)
                && e.actor().equals("human:dharmender"));
    }

    @Test
    void rejectionSafeStopsAndDelegatedApprovalIsLabelled() {
        Orchestrator o = orchestrator(runs, graph(
                agent("plan", "producer", list(), list(), list("plan")),
                approval("approve-plan", list("plan"), list("plan"))), clock, sleeper, producing("producer"));

        o.start("rej", "test", Map.of(), FaultInjector.NONE);
        o.reject("rej", "approve-plan", "reviewer", "scope too broad");
        RunState rejected = o.resume("rej", FaultInjector.NONE);
        assertThat(rejected.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(rejected.getStatusReason()).startsWith("APPROVAL_REJECTED");
        // Resuming after a rejection re-requests approval instead of silently proceeding.
        RunState again = o.resume("rej", FaultInjector.NONE);
        assertThat(again.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(types(events(o, "rej"))).contains(EventType.APPROVAL_REJECTED, EventType.APPROVAL_VOIDED);

        RunState delegated = o.start("del", "test", Map.of(Orchestrator.PARAM_AUTO_APPROVE, "ci-bot"), FaultInjector.NONE);
        assertThat(delegated.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(delegated.getApprovals().get("approve-plan").getMode()).isEqualTo(ApprovalRecord.Mode.DELEGATED);
        assertThat(events(o, "del")).anyMatch(e -> e.type().equals(EventType.APPROVAL_GRANTED)
                && e.actor().equals("delegated:ci-bot"));
    }

    @Test
    void approvalTimeoutSafeStops() {
        Orchestrator o = orchestrator(runs, graph(
                agent("plan", "producer", list(), list(), list("plan")),
                withTimeout(approval("approve-plan", list("plan"), list("plan")), 60_000)),
                clock, sleeper, producing("producer"));
        o.start("timeout", "test", Map.of(), FaultInjector.NONE);

        clock.advanceMillis(120_000);
        RunState state = o.resume("timeout", FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(state.getStatusReason()).startsWith("APPROVAL_TIMEOUT");
    }

    @Test
    void clarificationPausesUntilAnswersThenAgentAppliesThem() throws Exception {
        FakeAgent analyst = new FakeAgent("analyst", (ctx, n) -> {
            ctx.output("ambiguity", Map.of("requiresClarification", true, "questions", List.of("What does secure mean?")));
            ctx.output("requirement", Map.of("summary", "draft"));
            return StageResult.ok("ambiguous");
        });
        FakeAgent applier = new FakeAgent("applier", (ctx, n) -> {
            JsonNode answers = ctx.clarificationAnswers().orElseThrow();
            ctx.output("requirement", Map.of("summary", "clarified", "secure", answers.path("secure").asText()));
            return StageResult.ok("applied");
        });
        Orchestrator o = orchestrator(runs, graph(
                agent("intake", "analyst", list(), list(), list("ambiguity", "requirement")),
                withWhen(clarification("clarify", "applier", list("intake"), list("ambiguity"), list("requirement")),
                        "ambiguity.requiresClarification"),
                agent("plan", "producer", list("clarify"), list("requirement"), list("plan"))),
                clock, sleeper, analyst, applier, producing("producer"));

        RunState waiting = o.start("clar", "test", Map.of(), FaultInjector.NONE);
        assertThat(waiting.getStatus()).isEqualTo(RunStatus.WAITING_CLARIFICATION);

        o.clarify("clar", "clarify", Json.mapper().readTree("{\"secure\":\"block private hosts\"}"), "po", false);
        RunState done = o.resume("clar", FaultInjector.NONE);

        assertThat(done.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.latest("requirement").orElseThrow().getVersion()).isEqualTo(2);
        assertThat(done.latest("plan").orElseThrow().getDerivedFrom()).containsExactly("requirement@2");
    }

    @Test
    void falseConditionSkipsStageAndUnblocksDependents() {
        Orchestrator o = orchestrator(runs, graph(
                agent("intake", "producer", list(), list(), list("req")),
                withWhen(agent("impact", "producer", list("intake"), list("req"), list("impact")), "param:baseline"),
                agent("plan", "producer", list("impact"), list("req", "impact?"), list("plan"))),
                clock, sleeper, producing("producer"));

        RunState state = o.start("skip", "test", Map.of(), FaultInjector.NONE);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.stage("impact").getStatus()).isEqualTo(StageStatus.SKIPPED);
    }

    @Test
    void amendmentReplansOnlyTheDependentSubgraph() {
        FakeAgent producer = producing("producer");
        Orchestrator o = orchestrator(runs, graph(
                agent("intake", "producer", list(), list(), list("req", "glossary")),
                agent("plan", "producer", list("intake"), list("req"), list("plan")),
                agent("glossary-docs", "producer", list("intake"), list("glossary"), list("gdocs")),
                agent("implement", "producer", list("plan"), list("plan"), list("code")),
                approval("release", list("implement", "glossary-docs"), list("code", "gdocs"))),
                clock, sleeper, producer);
        RunState first = o.start("replan", "test", Map.of(), FaultInjector.NONE);
        assertThat(first.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);

        RunState replanned = o.amend("replan", "req", Json.tree(Map.of("summary", "changed scope")), "po", "scope change");

        assertThat(replanned.getStatus()).isEqualTo(RunStatus.REPLANNED);
        assertThat(replanned.stage("intake").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(replanned.stage("glossary-docs").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(replanned.stage("plan").getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(replanned.stage("implement").getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(replanned.getApprovals()).doesNotContainKey("release");

        RunState resumed = o.resume("replan", FaultInjector.NONE);
        assertThat(resumed.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(resumed.latest("plan").orElseThrow().getDerivedFrom()).containsExactly("req@2");
        assertThat(resumed.stage("glossary-docs").getExecutions()).isEqualTo(1);
        assertThat(resumed.stage("plan").getExecutions()).isEqualTo(2);
        AuditEvent replan = events(o, "replan").stream().filter(e -> e.type().equals(EventType.REPLAN_TRIGGERED))
                .findFirst().orElseThrow();
        assertThat(replan.data().path("preserved").toString()).contains("intake", "glossary-docs");
    }

    @Test
    void killSwitchSafeStopsAtNextCheckpointAndResumeClearsIt() {
        Orchestrator[] holder = new Orchestrator[1];
        FakeAgent stopper = new FakeAgent("stopper", (ctx, n) -> {
            if (n == 1) {
                holder[0].requestStop(ctx.runId(), "operator", "suspicious output");
            }
            return okWithOutputs(ctx);
        });
        Orchestrator o = orchestrator(runs, graph(
                agent("a", "stopper", list(), list(), list("a")),
                agent("b", "producer", list("a"), list("a"), list("b"))), clock, sleeper, stopper, producing("producer"));
        holder[0] = o;

        RunState stopped = o.start("kill", "test", Map.of(), FaultInjector.NONE);
        assertThat(stopped.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stopped.getStatusReason()).startsWith("KILL_SWITCH");
        assertThat(stopped.stage("b").getStatus()).isEqualTo(StageStatus.PENDING);

        RunState resumed = o.resume("kill", FaultInjector.NONE);
        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(types(events(o, "kill"))).contains(EventType.KILL_SWITCH_SET, EventType.KILL_SWITCH_CLEARED);
    }

    @Test
    void budgetExhaustionSafeStops() {
        FakeAgent flaky = new FakeAgent("flaky", (ctx, n) -> StageResult.fail(FailureKind.RETRYABLE, "flaky"));
        Orchestrator o = orchestrator(runs, graph(new Budget(2, 60_000),
                withRetry(agent("a", "flaky", list(), list(), list("a")), new RetryPolicy(5, 0, 1))),
                clock, sleeper, flaky);

        RunState state = o.start("budget", "test", Map.of(), FaultInjector.NONE);

        // Retries are bounded by the stage policy (5) and the run budget (2 executions) caps the next drive.
        assertThat(state.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        RunState again = o.resume("budget", FaultInjector.NONE);
        assertThat(again.getStatusReason()).startsWith("BUDGET_EXCEEDED");
    }

    @Test
    void injectedFaultsAreLabelledAndRecoveredByRetry() {
        Orchestrator o = orchestrator(runs, graph(
                withRetry(agent("build", "producer", list(), list(), list("report")), new RetryPolicy(2, 50, 2))),
                clock, sleeper, producing("producer"));
        FaultInjector faults = new FaultInjector(List.of(
                new FaultInjector.Fault("build", 1, 1, FailureKind.RETRYABLE, "maven repository timeout")));

        RunState state = o.start("fault", "test", Map.of(), faults);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        AuditEvent injected = events(o, "fault").stream().filter(e -> e.type().equals(EventType.FAULT_INJECTED))
                .findFirst().orElseThrow();
        assertThat(injected.data().path("injected").asBoolean()).isTrue();
    }

    @Test
    void recoversStageInterruptedByCrash() throws Exception {
        Orchestrator o = orchestrator(runs, graph(
                agent("a", "producer", list(), list(), list("a")),
                approval("gate", list("a"), list("a")),
                agent("b", "producer", list("gate"), list("a"), list("b"))), clock, sleeper, producing("producer"));
        o.start("crash", "test", Map.of(), FaultInjector.NONE);
        o.approve("crash", "gate", "lead", "ok");
        // Simulate a process that died while stage b was mid-write.
        RunState state = o.store().load("crash");
        state.stage("b").setStatus(StageStatus.RUNNING);
        state.setStatus(RunStatus.RUNNING);
        o.store().save(state);
        o.workspace("crash").write("b", "partial.txt", "half");

        RunState recovered = o.resume("crash", FaultInjector.NONE);

        assertThat(recovered.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(Files.exists(o.workspace("crash").root().resolve("partial.txt"))).isFalse();
        assertThat(types(events(o, "crash"))).contains(EventType.STAGE_RECOVERED);
    }

    @Test
    void auditChainIsIntactAndPersistedStateRoundTrips() {
        Orchestrator o = orchestrator(runs, graph(agent("a", "producer", list(), list(), list("a"))),
                clock, sleeper, producing("producer"));
        o.start("audit", "test", Map.of(), FaultInjector.NONE);

        assertThat(o.auditLog("audit").verify().intact()).isTrue();
        assertThat(o.store().load("audit").latest("a")).isPresent();
    }

    private static List<String> types(List<AuditEvent> events) {
        return EngineFixture.types(events);
    }

    private static long seqOf(List<AuditEvent> events, String type, String stage) {
        return events.stream().filter(e -> e.type().equals(type) && stage.equals(e.stageId()))
                .mapToLong(AuditEvent::seq).findFirst().orElseThrow();
    }

    private static StageResult okWithOutputs(StageContext ctx) {
        for (String key : ctx.stage().writes()) {
            ctx.output(key, Map.of("summary", key));
        }
        return StageResult.ok("ok");
    }
}

package com.salian.orchestrator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.EventType;
import com.salian.orchestrator.cli.DemoDriver;
import com.salian.orchestrator.cli.OrchestratorFactory;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.metrics.MetricsCalculator;
import com.salian.orchestrator.metrics.RunMetrics;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.RunStatus;
import com.salian.orchestrator.model.StageStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the three scenarios through the real workflow, agents, gates and policies. Only the Maven
 * build is replaced by a stub (the demos run the real build); everything asserted here is produced
 * by the engine at test time.
 */
class ScenarioFlowTest {

    @TempDir
    Path runs;

    private ScenarioService scenarios;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    /** Stand-in for the Maven build: reports a green build without running Maven. */
    static final class StubVerifier implements Agent {
        @Override
        public String name() {
            return "maven-verifier";
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.SCRIPTED;
        }

        @Override
        public StageResult execute(StageContext ctx) {
            ctx.output("test-report", Map.of("summary", "STUB build (tests not executed)", "buildSuccess", true,
                    "tests", 1, "failures", 0, "errors", 0, "skipped", 0));
            return StageResult.ok("stub build");
        }
    }

    @BeforeEach
    void setUp() {
        OrchestratorFactory.Paths paths = OrchestratorFactory.locate(Path.of("."));
        scenarios = OrchestratorFactory.create(paths, runs, new StubVerifier(), Clock.systemUTC(), millis -> { });
    }

    private List<AuditEvent> events(RunState state) {
        return scenarios.orchestrator().auditLog(state.getRunId()).events();
    }

    private JsonNode artifact(RunState state, String key) {
        return state.latest(key).orElseThrow().getContent();
    }

    @Test
    void greenfieldBuildsTheServiceWithRetryFallbackAndChangeControl() {
        RunState state = scenarios.start("greenfield", "gf", "ci-bot", false);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.stage("clarify").getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(state.stage("impact-analysis").getStatus()).isEqualTo(StageStatus.SKIPPED);
        assertThat(state.stage("change-control").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(state.stage("write-docs").isUsedFallback()).isTrue();
        assertThat(state.stage("build-test").getAttempts()).isEqualTo(2);
        assertThat(artifact(state, "requirement").path("features").get(0).path("id").asText()).isEqualTo("core-links");
        assertThat(artifact(state, "design").path("migrations").toString()).contains("V1__create_short_links.sql");
        assertThat(artifact(state, "design").path("endpoints").toString()).contains("POST /api/v1/links", "GET /r/{code}");
        assertThat(scenarios.orchestrator().workspace("gf").read("url-shortener/src/main/java/com/salian/shortener/web/RedirectController.java"))
                .hasValueSatisfying(src -> assertThat(src).doesNotContain("HttpServletRequest"));

        List<String> types = events(state).stream().map(AuditEvent::type).toList();
        assertThat(types).contains(EventType.FAULT_INJECTED, EventType.RETRY_SCHEDULED, EventType.FALLBACK_ACTIVATED);
        RunMetrics metrics = MetricsCalculator.forRun("gf", "greenfield", "COMPLETED", events(state));
        assertThat(metrics.retries()).isEqualTo(1);
        assertThat(metrics.recoveredIncidents()).isGreaterThanOrEqualTo(2);
        assertThat(metrics.auditChainIntact()).isTrue();
    }

    @Test
    void brownfieldAnalyzesImpactBlocksRawIpAndReworks() {
        RunState state = scenarios.start("brownfield", "bf", "ci-bot", false);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        JsonNode impact = artifact(state, "impact-report");
        assertThat(impact.path("modifiedFiles").toString()).contains("RedirectController.java", "LinkResolvedEvent.java");
        assertThat(impact.path("baseline").path("endpoints").toString()).contains("GET /r/{code}");
        assertThat(impact.path("dataFlows").toString()).contains("RedirectController -> LinkResolvedEvent -> ClickRecorder -> table click_events");
        assertThat(impact.path("baseline").path("testMethods").asInt()).isGreaterThan(10);

        assertThat(state.getReworkCounts()).containsEntry("policy-scan", 1);
        assertThat(state.stage("implement").getExecutions()).isEqualTo(2);
        assertThat(state.stage("write-tests").getExecutions()).isEqualTo(1);
        assertThat(artifact(state, "policy-feedback").path("details").path("findings").toString()).contains("pii-raw-ip");
        assertThat(artifact(state, "code-changes").path("injectedDefects")).isEmpty();
        assertThat(scenarios.orchestrator().workspace("bf")
                .read("url-shortener/src/main/resources/db/migration/V2__create_click_events.sql"))
                .hasValueSatisfying(sql -> assertThat(sql).doesNotContain("client_ip"));
        assertThat(artifact(state, "contract-report").path("removed")).isEmpty();
        assertThat(artifact(state, "contract-report").path("added").toString()).contains("GET /api/v1/links/{code}/stats");
    }

    @Test
    void ambiguousRequirementPausesForClarificationThenReplansOnAmendment() {
        DemoDriver demo = new DemoDriver(scenarios, new PrintStream(console));

        RunState state = demo.ambiguous(false);

        assertThat(state.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(state.getReplanCount()).isEqualTo(1);
        assertThat(state.stage("intake").getExecutions()).isEqualTo(1);
        assertThat(state.stage("clarify").getExecutions()).isEqualTo(1);
        assertThat(state.stage("plan").getExecutions()).isEqualTo(2);
        assertThat(state.stage("change-control").getStatus()).isEqualTo(StageStatus.SKIPPED);
        JsonNode requirement = artifact(state, "requirement");
        assertThat(requirement.path("features").toString()).contains("url-safety", "rate-limit");
        assertThat(requirement.path("outOfScope")).isEmpty();
        assertThat(artifact(state, "plan").path("features").toString()).contains("rate-limit");
        assertThat(scenarios.orchestrator().workspace(state.getRunId())
                .exists("url-shortener/src/main/java/com/salian/shortener/web/CreateRateLimitFilter.java")).isTrue();

        List<AuditEvent> events = events(state);
        assertThat(events).anyMatch(e -> e.type().equals(EventType.CLARIFICATION_REQUESTED)
                && e.data().path("questions").size() == 3);
        assertThat(events).anyMatch(e -> e.type().equals(EventType.REPLAN_TRIGGERED)
                && e.data().path("preserved").toString().contains("clarify"));
        assertThat(events).anyMatch(e -> e.type().equals(EventType.APPROVAL_VOIDED));
        // Scripted human stand-ins are never recorded as real people.
        assertThat(events).filteredOn(e -> e.type().equals(EventType.APPROVAL_GRANTED)
                        || e.type().equals(EventType.ARTIFACT_AMENDED) || e.type().equals(EventType.CLARIFICATION_RECEIVED))
                .isNotEmpty()
                .allMatch(e -> e.actor().startsWith("delegated:"));
    }

    @Test
    void rejectedPlanSafeStopsBeforeAnyCodeIsWritten() {
        DemoDriver demo = new DemoDriver(scenarios, new PrintStream(console));

        RunState state = demo.safeStop();

        assertThat(state.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(state.stage("implement").getExecutions()).isZero();
        List<String> types = events(state).stream().map(AuditEvent::type).toList();
        assertThat(types).contains(EventType.APPROVAL_REJECTED, EventType.SAFE_STOP, EventType.APPROVAL_VOIDED);
        assertThat(scenarios.orchestrator().workspace(state.getRunId()).list("url-shortener")).isEmpty();
    }
}

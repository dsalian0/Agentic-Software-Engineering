package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.Gate;
import com.salian.orchestrator.engine.GateRegistry;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.support.CodebaseScanner;
import java.util.Map;

/** Domain exit gates referenced by name from the workflow definition. */
public final class Gates {

    private Gates() {
    }

    public static GateRegistry registry() {
        return new GateRegistry()
                .register(new BuildGreen())
                .register(new TestsNotDecreased())
                .register(new ReleaseReady());
    }

    /** The build must succeed with zero failing or erroring tests, and at least one test must have run. */
    static final class BuildGreen implements Gate {
        @Override
        public String name() {
            return "build-green";
        }

        @Override
        public GateResult evaluate(StageContext ctx, Map<String, JsonNode> outputs) {
            JsonNode report = outputs.get("test-report");
            boolean green = report != null && report.path("buildSuccess").asBoolean()
                    && report.path("failures").asInt() == 0 && report.path("errors").asInt() == 0
                    && report.path("tests").asInt() > 0;
            return green ? GateResult.pass(name(), report.path("summary").asText())
                    : GateResult.fail(name(), report == null ? "no test report" : report.path("summary").asText(), FailureKind.PERMANENT);
        }
    }

    /** Brownfield safety: the change may not delete existing tests (static count vs. impact-analysis baseline). */
    static final class TestsNotDecreased implements Gate {
        @Override
        public String name() {
            return "tests-not-decreased";
        }

        @Override
        public GateResult evaluate(StageContext ctx, Map<String, JsonNode> outputs) {
            int baseline = ctx.optionalInput("impact-report").map(i -> i.path("baseline").path("testMethods").asInt()).orElse(0);
            int current = CodebaseScanner.countTests(ctx.workspace(), Scenarios.PROJECT);
            return current >= baseline
                    ? GateResult.pass(name(), "test methods " + baseline + " -> " + current)
                    : GateResult.fail(name(), "test methods dropped from " + baseline + " to " + current, FailureKind.POLICY_BLOCK);
        }
    }

    static final class ReleaseReady implements Gate {
        @Override
        public String name() {
            return "release-ready";
        }

        @Override
        public GateResult evaluate(StageContext ctx, Map<String, JsonNode> outputs) {
            JsonNode summary = outputs.get("release-summary");
            return summary != null && summary.path("ready").asBoolean()
                    ? GateResult.pass(name(), summary.path("summary").asText())
                    : GateResult.fail(name(), summary == null ? "no summary" : summary.path("summary").asText(), FailureKind.PERMANENT);
        }
    }
}

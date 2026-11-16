package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Release readiness (REAL): evaluates a checklist over the verification artifacts and writes a
 * reviewer-facing summary (release-summary.md in the run directory) for the human release approval.
 */
public final class ReleaseManagerAgent implements Agent {

    @Override
    public String name() {
        return "release-manager";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) throws IOException {
        JsonNode requirement = ctx.input("requirement");
        JsonNode plan = ctx.input("plan");
        JsonNode design = ctx.input("design");
        JsonNode tests = ctx.input("test-report");
        JsonNode policy = ctx.input("policy-report");
        JsonNode contract = ctx.input("contract-report");
        JsonNode docs = ctx.input("docs");
        Optional<JsonNode> impact = ctx.optionalInput("impact-report");
        JsonNode code = ctx.input("code-changes");

        List<Map<String, Object>> checklist = new ArrayList<>();
        check(checklist, "Build green and all tests pass", tests.path("buildSuccess").asBoolean()
                && tests.path("failures").asInt() == 0 && tests.path("errors").asInt() == 0, tests.path("summary").asText());
        check(checklist, "No blocking policy findings", policy.path("blocking").asInt() == 0, policy.path("summary").asText());
        check(checklist, "No breaking API changes", !contract.path("breaking").asBoolean(), contract.path("summary").asText());
        check(checklist, "Designed endpoints delivered", contract.path("undeliveredDesign").isEmpty(), "");
        check(checklist, "Documentation updated", docs.path("files").size() > 0, docs.path("summary").asText());
        check(checklist, "No injected defects remain", code.path("injectedDefects").isEmpty(),
                code.path("remediatedRules").isEmpty() ? "" : "remediated " + code.path("remediatedRules"));
        boolean ready = checklist.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));

        StringBuilder md = new StringBuilder();
        md.append("# Release readiness: ").append(ctx.parameter(Scenarios.PARAM_SCENARIO)).append(" (run `")
                .append(ctx.runId()).append("`)\n\n");
        md.append("**Verdict:** ").append(ready ? "READY for human release approval" : "NOT READY").append("\n\n");
        md.append("**Requirement:** ").append(requirement.path("summary").asText()).append("\n\n");
        md.append("**Plan:** ").append(plan.path("summary").asText()).append("\n\n");
        md.append("**Design:** ").append(design.path("summary").asText()).append("\n\n");
        impact.ifPresent(i -> md.append("**Impact:** ").append(i.path("summary").asText()).append("\n\n"));
        md.append("| Check | Result | Evidence |\n|---|---|---|\n");
        for (Map<String, Object> c : checklist) {
            md.append("| ").append(c.get("check")).append(" | ").append(Boolean.TRUE.equals(c.get("passed")) ? "PASS" : "FAIL")
                    .append(" | ").append(c.get("evidence")).append(" |\n");
        }
        md.append("\n**How the code was produced:** ").append(code.path("executionMode").asText()).append("\n\n");
        md.append("Risks carried into release:\n\n");
        plan.path("risks").forEach(r -> md.append("- ").append(r.asText()).append("\n"));
        impact.ifPresent(i -> i.path("regressionRisks").forEach(r -> md.append("- ").append(r.asText()).append("\n")));
        Files.writeString(ctx.runDir().resolve("release-summary.md"), md.toString());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summary", (ready ? "READY" : "NOT READY") + ": " + checklist.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("passed"))).count() + "/" + checklist.size() + " checks passed");
        summary.put("ready", ready);
        summary.put("checklist", checklist);
        summary.put("report", "release-summary.md");
        ctx.output("release-summary", summary);
        return StageResult.ok((String) summary.get("summary"));
    }

    private static void check(List<Map<String, Object>> checklist, String name, boolean passed, String evidence) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("check", name);
        c.put("passed", passed);
        c.put("evidence", evidence);
        checklist.add(c);
    }
}

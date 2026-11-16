package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.support.CodebaseScanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API compatibility check (REAL): compares the endpoints in the sandbox after the change against the
 * baseline recorded by impact analysis, and against the designed contract. Removing an existing
 * endpoint is a breaking change and fails the stage.
 */
public final class ContractCheckerAgent implements Agent {

    @Override
    public String name() {
        return "contract-checker";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        List<String> current = CodebaseScanner.scan(ctx.workspace(), Scenarios.PROJECT).endpoints();
        List<String> baseline = new ArrayList<>();
        ctx.optionalInput("impact-report").ifPresent(i -> i.path("baseline").path("endpoints")
                .forEach(e -> baseline.add(e.asText())));
        List<String> designed = new ArrayList<>();
        JsonNode design = ctx.input("design");
        design.path("endpoints").forEach(e -> designed.add(e.asText()));

        List<String> removed = baseline.stream().filter(e -> !current.contains(e)).toList();
        List<String> added = current.stream().filter(e -> !baseline.contains(e)).toList();
        List<String> undelivered = designed.stream().filter(e -> !current.contains(e)).toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", added.size() + " endpoint(s) added, " + removed.size() + " removed, "
                + undelivered.size() + " designed but missing");
        report.put("baseline", baseline);
        report.put("current", current);
        report.put("added", added);
        report.put("removed", removed);
        report.put("undeliveredDesign", undelivered);
        report.put("breaking", !removed.isEmpty());
        if (!removed.isEmpty()) {
            return StageResult.fail(FailureKind.POLICY_BLOCK, "Breaking API change: removed " + removed, report);
        }
        if (!undelivered.isEmpty()) {
            return StageResult.fail(FailureKind.PERMANENT, "Implementation does not match design: missing " + undelivered, report);
        }
        ctx.output("contract-report", report);
        return StageResult.ok((String) report.get("summary"));
    }
}
